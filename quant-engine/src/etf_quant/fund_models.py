"""基金5观测上涨概率与20/60观测净值变化；独立测试、不可变工件与冻结预测。"""

import hashlib
import json
import os
import threading
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal
from itertools import pairwise
from pathlib import Path
from typing import Literal
from zoneinfo import ZoneInfo

import joblib
import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier, LGBMRegressor
from pydantic import AwareDatetime, BaseModel, Field, field_validator
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import mean_absolute_error
from threadpoolctl import threadpool_limits
from xgboost import XGBClassifier, XGBRegressor

from etf_quant.forward_models import probability_diagnostics
from etf_quant.fund_data import FundCode, FundObservation, canonical_hash
from etf_quant.models import estimator, logit

FEATURES = ["return_5", "return_20", "return_60", "vol_20", "vol_60", "drawdown_60"]
FEATURE_VERSION = "fund-nav-trend-vol-drawdown-v1"
TARGET_VERSION = "fund-nav-change-20-60-v1"
SHORT_TARGET_VERSION = "fund-nav-up-5-v1"
TRAIN_LOCK = threading.Lock()
MODEL_FAMILIES = {
    "lightgbm": "FUND_NAV_LGBM_REGRESSOR",
    "xgboost": "FUND_NAV_XGB_REGRESSOR",
}
SHORT_MODEL_FAMILIES = {
    "lightgbm": "FUND_NAV_LGBM_CLASSIFIER",
    "xgboost": "FUND_NAV_XGB_CLASSIFIER",
}


class FundTrainRequest(BaseModel):
    """基金独立工件族，不能接收旧ETF方向模型。"""

    fund_code: FundCode
    observations: list[FundObservation] = Field(max_length=20000)
    horizons: list[int] = Field(default_factory=lambda: [20, 60], min_length=1, max_length=3)
    code_version: str = Field(default="UNKNOWN", max_length=80)
    algorithms: list[Literal["lightgbm", "xgboost"]] = Field(
        default_factory=lambda: ["lightgbm", "xgboost"], min_length=1, max_length=2
    )

    @field_validator("algorithms")
    @classmethod
    def unique_algorithms(cls, value):
        """每个算法在同次请求只训练一次，避免相同方案重复生成工件。"""
        if len(value) != len(set(value)):
            raise ValueError("DUPLICATE_FUND_ALGORITHM")
        return value


class FundPredictRequest(BaseModel):
    """只读取登记工件；未核验未来开放日时，不猜精确目标日期。"""

    fund_code: FundCode
    observations: list[FundObservation] = Field(max_length=20000)
    models: list[dict] = Field(max_length=6)
    generated_at: AwareDatetime | None = None
    target_dates: dict[str, date] = Field(default_factory=dict)
    calendar_source: str | None = None


class FundEvaluateRequest(BaseModel):
    """只返回可追加验证对象，原始冻结预测由Java保留且不可覆盖。"""

    forecast: dict
    observations: list[FundObservation] = Field(max_length=20000)
    evaluated_at: AwareDatetime | None = None


def fund_frame(observations, fund_code, cutoff=None):
    """只用本基金真实净值，按截止时间选择已观测修订；历史下载不宣称严格PIT。"""
    rows = []
    for item in observations:
        value = item if isinstance(item, FundObservation) else FundObservation.model_validate(item)
        if value.fund_code != fund_code:
            raise ValueError("FUND_CODE_MISMATCH")
        if value.nature != "REAL":
            raise ValueError("REAL_MODEL_REJECTS_DEMO")
        if value.quality_status == "REJECTED":
            continue
        # 初次可得时间不能替代本次修订的抓取时间；截止后取得的内容不能回灌过去预测。
        if cutoff and (value.first_observed_at > cutoff or value.fetched_at > cutoff):
            continue
        rows.append(value.model_dump(mode="json"))
    if not rows:
        raise ValueError("NAV_NOT_AVAILABLE_AT_CUTOFF")
    frame = pd.DataFrame(rows)
    frame = frame.sort_values(["nav_date", "first_observed_at", "fetched_at"])
    frame = frame.drop_duplicates("nav_date", keep="last").reset_index(drop=True)
    frame["nav"] = frame["unit_nav"].map(float)
    frame["session_index"] = np.arange(len(frame))
    daily = frame["nav"].pct_change(fill_method=None)
    for lag in (5, 20, 60):
        frame[f"return_{lag}"] = frame["nav"].pct_change(lag, fill_method=None)
    for lag in (20, 60):
        frame[f"vol_{lag}"] = daily.rolling(lag).std()
    frame["drawdown_60"] = frame["nav"] / frame["nav"].rolling(60).max() - 1
    return frame


def labels(frame, horizon):
    """标签按后续有效净值观测数计数，未公布的目标值不参与训练。"""
    if horizon not in (5, 20, 60):
        raise ValueError("UNSUPPORTED_FUND_HORIZON")
    result = frame.copy()
    result["target_index"] = result["session_index"] + horizon
    result["target_date"] = result["nav_date"].shift(-horizon)
    # 先用真实十进制净值计算标签，最后才转为训练数值；平盘不能因浮点误差变成上涨。
    prices = result["unit_nav"].map(Decimal)
    future = prices.shift(-horizon)
    result["target_return"] = [
        float(end / start - Decimal(1)) if pd.notna(end) else np.nan
        for start, end in zip(prices, future, strict=True)
    ]
    result["target_up"] = [
        float(end > start) if pd.notna(end) else np.nan
        for start, end in zip(prices, future, strict=True)
    ]
    return result.dropna(subset=FEATURES + ["target_return", "target_date"])


def purged_split(labeled):
    """固定时间顺序三分，训练标签早于验证首日，验证标签早于测试首日。"""
    size = len(labeled)
    validation_start = int(size * 0.60)
    test_start = int(size * 0.80)
    if size < 400:
        raise ValueError("INSUFFICIENT_MATURED_SAMPLES")
    val_boundary = int(labeled.iloc[validation_start]["session_index"])
    test_boundary = int(labeled.iloc[test_start]["session_index"])
    train = labeled.iloc[:validation_start]
    train = train[train["target_index"] < val_boundary]
    validation = labeled.iloc[validation_start:test_start]
    validation = validation[validation["target_index"] < test_boundary]
    test = labeled.iloc[test_start:]
    if min(len(train), len(validation), len(test)) < 40:
        raise ValueError("INSUFFICIENT_PURGED_SAMPLES")
    return train, validation, test


def new_regressor(algorithm="lightgbm"):
    """两个算法各自创建固定CPU单线程回归器，不能静默替换为另一种模型。"""
    if algorithm == "xgboost":
        return XGBRegressor(
            objective="reg:squarederror",
            n_estimators=100,
            max_depth=3,
            learning_rate=0.04,
            min_child_weight=30,
            reg_lambda=1.0,
            n_jobs=1,
            random_state=42,
            tree_method="hist",
            device="cpu",
            verbosity=0,
        )
    if algorithm != "lightgbm":
        raise ValueError("UNSUPPORTED_FUND_ALGORITHM")
    return LGBMRegressor(
        n_estimators=100,
        num_leaves=7,
        max_depth=3,
        learning_rate=0.04,
        min_child_samples=30,
        reg_lambda=1.0,
        n_jobs=1,
        random_state=42,
        verbosity=-1,
    )


def evaluation_metrics(actual, predicted, training_mean, horizon):
    """同时间同标签比较零收益和训练均值，并披露重叠样本依赖。"""
    actual = np.asarray(actual)
    predicted = np.asarray(predicted)
    return {
        "samples": len(actual),
        "approx_nonoverlapping_samples": len(actual) // horizon,
        "mae": str(float(mean_absolute_error(actual, predicted))),
        "zero_return_mae": str(float(np.mean(np.abs(actual)))),
        "training_mean_mae": str(float(np.mean(np.abs(actual - training_mean)))),
        "direction_accuracy": str(float(np.mean((actual > 0) == (predicted > 0)))),
        "observations_are_independent": False,
        "overlap_warning": "OVERLAPPING_HORIZON_LABELS",
    }


def artifact_root():
    """工件限定在已有模型根目录下的基金命名空间，禁止任意路径加载。"""
    return Path(os.getenv("ETF_MODEL_DIR", ".runtime/models")).resolve() / "fund-nav"


def save_fund_artifact(model, metadata, calibrator=None):
    """每个目标和算法另存不可变工件，XGBoost保持跨平台原生JSON格式。"""
    path = artifact_root() / (metadata["id"] + ".joblib")
    path.parent.mkdir(parents=True, exist_ok=True)
    artifact = {"metadata": metadata}
    if metadata["algorithm"] == "xgboost":
        artifact["model_json"] = bytes(model.get_booster().save_raw(raw_format="json"))
    else:
        artifact["model"] = model
    if metadata["horizon"] == 5:
        artifact["calibrator"] = calibrator
    with path.open("xb") as handle:
        joblib.dump(artifact, handle)
    metadata["artifact_path"] = str(path)
    metadata["artifact_hash"] = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_suffix(".json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8"
    )
    return metadata


def probability_split(labeled):
    """5观测分类固定60/10/10/20切分，每段标签在下一段首个观测前结束。"""
    size = len(labeled)
    if size < 400:
        raise ValueError("INSUFFICIENT_MATURED_SAMPLES")
    cuts = (0, int(size * 0.6), int(size * 0.7), int(size * 0.8), size)
    sections = []
    for start, end in pairwise(cuts):
        part = labeled.iloc[start:end]
        if end < size:
            part = part[part["target_index"] < labeled.iloc[end]["session_index"]]
        sections.append(part)
    if min(map(len, sections)) < 30:
        raise ValueError("INSUFFICIENT_PURGED_PROBABILITY_SAMPLES")
    if sections[0]["target_up"].nunique() < 2:
        raise ValueError("SINGLE_CLASS_FIT")
    return tuple(sections)


def short_probability(model, frame, calibrator=None):
    """短期输出是真实分类概率，绝不从收益点估计伪造概率。"""
    raw = np.asarray(model.predict_proba(frame[FEATURES])[:, 1], dtype=float)
    values = raw if calibrator is None else calibrator.predict_proba(logit(raw))[:, 1]
    if not np.isfinite(values).all() or (values < 0).any() or (values > 1).any():
        raise ValueError("INVALID_MODEL_PROBABILITY")
    return values


def train_short_fund(request, frame, snapshot_hash, now):
    """独立校准段拟合Platt，验证段选择RAW/Platt，最终测试从不参与模型选择。"""
    fit, calibration, validation, test = probability_split(labels(frame, 5))
    records = []
    for algorithm in request.algorithms:
        model = estimator(algorithm, 42)
        model.fit(fit[FEATURES], fit["target_up"])
        candidates = {"RAW": None}
        reports = {}
        # 校准段缺类时保留原始概率，并记录未校准原因，不能借用验证或测试标签。
        if calibration["target_up"].nunique() == 2:
            calibrator = LogisticRegression(random_state=42, max_iter=500)
            calibrator.fit(logit(short_probability(model, calibration)), calibration["target_up"])
            candidates["PLATT"] = calibrator
        else:
            reports["PLATT"] = {"status": "SKIPPED", "reason": "SINGLE_CLASS_CALIBRATION"}
        frequency = float(fit["target_up"].mean())
        baseline = probability_diagnostics(
            validation["target_up"], np.full(len(validation), frequency)
        )
        for name, calibrator in candidates.items():
            reports[name] = {
                "status": "SUCCEEDED",
                "validation": probability_diagnostics(
                    validation["target_up"], short_probability(model, validation, calibrator)
                ),
            }
        selected = min(
            candidates,
            key=lambda name: (
                reports[name]["validation"]["brier_score"],
                reports[name]["validation"]["log_loss"],
                0 if name == "RAW" else 1,
            ),
        )
        selected_calibrator = candidates[selected]
        val_report = reports[selected]["validation"]
        # 单类验证虽能计算Brier，但不能作为已证明两类区分能力的依据。
        eligible = (
            validation["target_up"].nunique() == 2
            and val_report["brier_score"] < baseline["brier_score"]
            and val_report["log_loss"] < baseline["log_loss"]
        )
        test_report = probability_diagnostics(
            test["target_up"], short_probability(model, test, selected_calibrator)
        )
        reasons = ["NO_FORWARD_EVIDENCE", "NOT_TOTAL_RETURN", "NOT_STRICT_PIT"]
        if not eligible:
            reasons.append("VALIDATION_BASELINES_NOT_BEATEN")
        if validation["target_up"].nunique() < 2:
            reasons.append("SINGLE_CLASS_VALIDATION")
        if test["target_up"].nunique() < 2:
            reasons.append("SINGLE_CLASS_TEST_AUC_UNDEFINED")
        for report in (val_report, test_report):
            report.update(
                approx_nonoverlapping_samples=report["samples"] // 5,
                observations_are_independent=False,
                overlap_warning="OVERLAPPING_HORIZON_LABELS",
            )
        metadata = {
            "id": f"fund-{request.fund_code}-{algorithm}-h5-{uuid.uuid4()}",
            "fund_code": request.fund_code,
            "horizon": 5,
            "algorithm": algorithm,
            "model_family": SHORT_MODEL_FAMILIES[algorithm],
            "status": "TRAINED",
            "nature": "REAL",
            "evidence_type": "HISTORICAL_RESEARCH",
            "capability": "PROBABILITY",
            "target_basis": "NAV_CHANGE",
            "target_version": SHORT_TARGET_VERSION,
            "feature_version": FEATURE_VERSION,
            "generated_at": now.isoformat(),
            "fit_end_at": datetime.now(UTC).isoformat(),
            "training_cutoff": fit.iloc[-1]["target_date"],
            "data_available_through": frame.iloc[-1]["nav_date"],
            "data_snapshot_id": snapshot_hash,
            "input_revisions": frame[["nav_date", "revision_hash", "first_observed_at"]].to_dict(
                "records"
            ),
            "code_version": request.code_version,
            "validation_status": "RESEARCH_ONLY" if eligible else "FAILED",
            "adjustment_eligible": False,
            "selection_policy": "VALIDATION_BRIER_LOGLOSS_RAW_OR_PLATT_V1",
            "candidate_selected_on_validation": eligible,
            "calibration_scheme": selected,
            "probability_validation_status": "HISTORICAL_ONLY",
            "reason_codes": reasons,
            "metrics": {
                "validation": val_report,
                "test": test_report,
                "validation_frequency_baseline": baseline,
                "test_frequency_baseline": probability_diagnostics(
                    test["target_up"], np.full(len(test), frequency)
                ),
                "frequency_baseline": frequency,
                "calibration_candidates": reports,
                "selection_frozen_before_test": True,
            },
            "split": {
                **{
                    f"{name}_{field}": value
                    for name, part in zip(
                        ("train", "calibration", "validation", "test"),
                        (fit, calibration, validation, test),
                        strict=True,
                    )
                    for field, value in {
                        "count": len(part),
                        "start": part.iloc[0]["nav_date"],
                        "last_anchor": part.iloc[-1]["nav_date"],
                        "last_label_end": part.iloc[-1]["target_date"],
                    }.items()
                },
                "purged": True,
            },
            "global_feature_importance": dict(
                zip(FEATURES, map(float, model.feature_importances_), strict=True)
            ),
            "feature_explanation_type": "GLOBAL_IMPORTANCE_NOT_CAUSAL",
        }
        records.append(save_fund_artifact(model, metadata, selected_calibrator))
    return records


def train_funds(request: FundTrainRequest):
    """先隔离最终测试，再仅用验证集选择候选；保存失败研究证据和不可变工件。"""
    if len(set(request.horizons)) != len(request.horizons):
        raise ValueError("DUPLICATE_HORIZON")
    now = datetime.now(UTC)
    frame = fund_frame(request.observations, request.fund_code, now)
    snapshot_hash = canonical_hash(
        frame[["nav_date", "unit_nav", "revision_hash", "first_observed_at"]].to_dict("records")
    )
    result = []
    with TRAIN_LOCK, threadpool_limits(limits=1):
        for horizon in request.horizons:
            if horizon == 5:
                try:
                    result.extend(train_short_fund(request, frame, snapshot_hash, now))
                except ValueError as error:
                    for algorithm in request.algorithms:
                        result.append(
                            {
                                "fund_code": request.fund_code,
                                "horizon": 5,
                                "algorithm": algorithm,
                                "model_family": SHORT_MODEL_FAMILIES[algorithm],
                                "status": "INVALID_DATA",
                                "validation_status": "FAILED",
                                "reason_codes": [str(error)],
                            }
                        )
                continue
            try:
                train, validation, test = purged_split(labels(frame, horizon))
            except ValueError as error:
                # 数据条件共同不满足时仍为每个计划算法分别返回缺口，不制造成功模型。
                for algorithm in request.algorithms:
                    result.append(
                        {
                            "fund_code": request.fund_code,
                            "horizon": horizon,
                            "algorithm": algorithm,
                            "model_family": MODEL_FAMILIES[algorithm],
                            "status": "INVALID_DATA",
                            "validation_status": "FAILED",
                            "reason_codes": [str(error)],
                        }
                    )
                continue
            for algorithm in request.algorithms:
                model = new_regressor(algorithm)
                model.fit(train[FEATURES], train["target_return"])
                mean = float(train["target_return"].mean())
                val_result = evaluation_metrics(
                    validation["target_return"], model.predict(validation[FEATURES]), mean, horizon
                )
                candidate_selected = float(val_result["mae"]) < min(
                    float(val_result["zero_return_mae"]), float(val_result["training_mean_mae"])
                )
                test_result = evaluation_metrics(
                    test["target_return"], model.predict(test[FEATURES]), mean, horizon
                )
                model_id = f"fund-{request.fund_code}-{algorithm}-h{horizon}-{uuid.uuid4()}"
                metadata = {
                    "id": model_id,
                    "fund_code": request.fund_code,
                    "horizon": horizon,
                    "status": "TRAINED",
                    "nature": "REAL",
                    "evidence_type": "HISTORICAL_RESEARCH",
                    "capability": "RETURN_POINT",
                    "target_basis": "NAV_CHANGE",
                    "target_version": TARGET_VERSION,
                    "feature_version": FEATURE_VERSION,
                    "algorithm": algorithm,
                    "model_family": MODEL_FAMILIES[algorithm],
                    "generated_at": now.isoformat(),
                    "fit_end_at": datetime.now(UTC).isoformat(),
                    "training_cutoff": train.iloc[-1]["target_date"],
                    "data_available_through": frame.iloc[-1]["nav_date"],
                    "data_snapshot_id": snapshot_hash,
                    "input_revisions": frame[
                        ["nav_date", "revision_hash", "first_observed_at"]
                    ].to_dict("records"),
                    "code_version": request.code_version,
                    "validation_status": "RESEARCH_ONLY" if candidate_selected else "FAILED",
                    "adjustment_eligible": False,
                    "selection_policy": "VALIDATION_MAE_BEATS_ZERO_AND_TRAIN_MEAN_V1",
                    "candidate_selected_on_validation": candidate_selected,
                    "reason_codes": ["NO_FORWARD_EVIDENCE", "NOT_TOTAL_RETURN", "NOT_STRICT_PIT"],
                    "metrics": {"validation": val_result, "test": test_result},
                    "split": {
                        "train_count": len(train),
                        "validation_count": len(validation),
                        "test_count": len(test),
                        "train_start": train.iloc[0]["nav_date"],
                        "train_last_anchor": train.iloc[-1]["nav_date"],
                        "train_last_label_end": train.iloc[-1]["target_date"],
                        "validation_start": validation.iloc[0]["nav_date"],
                        "validation_last_label_end": validation.iloc[-1]["target_date"],
                        "test_start": test.iloc[0]["nav_date"],
                        "test_last_label_end": test.iloc[-1]["target_date"],
                        "purged": True,
                    },
                    "global_feature_importance": dict(
                        zip(FEATURES, map(float, model.feature_importances_), strict=True)
                    ),
                    "feature_explanation_type": "GLOBAL_IMPORTANCE_NOT_CAUSAL",
                }
                if not candidate_selected:
                    metadata["reason_codes"].append("VALIDATION_BASELINES_NOT_BEATEN")
                result.append(save_fund_artifact(model, metadata))
    return {"fund_code": request.fund_code, "models": result, "nature": "REAL"}


def load_fund_model(record, fund_code):
    """校验受控路径和摘要，工件内外身份、算法及真实模型类型必须一致。"""
    path = Path(record["artifact_path"]).resolve()
    if not path.is_relative_to(artifact_root()) or path.suffix != ".joblib":
        raise ValueError("MODEL_PATH_DENIED")
    if not path.is_file():
        raise ValueError("MODEL_ARTIFACT_MISSING")
    if hashlib.sha256(path.read_bytes()).hexdigest() != record.get("artifact_hash"):
        raise ValueError("MODEL_ARTIFACT_HASH_MISMATCH")
    payload = joblib.load(path)
    metadata = payload["metadata"]
    algorithm = model_algorithm(metadata)
    registered_algorithm = model_algorithm(record)
    if metadata.get("horizon") not in (5, 20, 60):
        raise ValueError("MODEL_IDENTITY_MISMATCH_HORIZON")
    short = metadata.get("horizon") == 5
    target_version = SHORT_TARGET_VERSION if short else TARGET_VERSION
    if (
        metadata["fund_code"] != fund_code
        or metadata["feature_version"] != FEATURE_VERSION
        or metadata["target_version"] != target_version
        or algorithm != registered_algorithm
        or any(
            metadata.get(key) != record.get(key)
            for key in (
                "id",
                "fund_code",
                "horizon",
                "feature_version",
                "target_version",
                "nature",
                "capability",
                "target_basis",
                "calibration_scheme",
            )
        )
    ):
        raise ValueError("MODEL_IDENTITY_MISMATCH")
    if short and metadata != {
        key: value for key, value in record.items() if key not in ("artifact_path", "artifact_hash")
    }:
        # 新分类工件完整核验时间、规则和验证证据，不能仅改外层登记绕过时点或方案身份。
        # 旧20/60工件继续沿用既有字段兼容，不强制补写历史元数据。
        raise ValueError("MODEL_METADATA_MISMATCH")
    if algorithm == "xgboost":
        if "model" in payload or not isinstance(payload.get("model_json"), bytes):
            raise ValueError("MODEL_ALGORITHM_MISMATCH")
        model = (XGBClassifier if short else XGBRegressor)(n_jobs=1, device="cpu")
        model.load_model(bytearray(payload["model_json"]))
        objective = "binary:logistic" if short else "reg:squarederror"
        if model.objective != objective or model.get_booster().feature_names != FEATURES:
            raise ValueError("MODEL_ALGORITHM_MISMATCH")
        payload["model"] = model
    elif (
        not isinstance(payload.get("model"), LGBMClassifier if short else LGBMRegressor)
        or "model_json" in payload
    ):
        raise ValueError("MODEL_ALGORITHM_MISMATCH")
    if short:
        calibrator = payload.get("calibrator")
        scheme = metadata.get("calibration_scheme")
        if not (
            scheme == "RAW"
            and calibrator is None
            or scheme == "PLATT"
            and isinstance(calibrator, LogisticRegression)
        ):
            raise ValueError("MODEL_CALIBRATION_IDENTITY_MISMATCH")
    return payload


def model_algorithm(metadata):
    """旧无算法字段的原基金工件仅兼容LightGBM，未知算法或矛盾族标记一律拒绝。"""
    algorithm = metadata.get("algorithm", "lightgbm")
    if not isinstance(algorithm, str) or algorithm not in MODEL_FAMILIES:
        raise ValueError("UNSUPPORTED_FUND_ALGORITHM")
    families = SHORT_MODEL_FAMILIES if metadata.get("horizon") == 5 else MODEL_FAMILIES
    if metadata.get("model_family") != families[algorithm]:
        raise ValueError("MODEL_ALGORITHM_MISMATCH")
    return algorithm


def predict_funds(request: FundPredictRequest):
    """生成真实端点，不插值每日路径；资格不足仍保留研究预测及原因。"""
    generated = request.generated_at or datetime.now(UTC)
    if generated > datetime.now(UTC):
        raise ValueError("GENERATION_IN_FUTURE")
    frame = fund_frame(request.observations, request.fund_code, generated)
    latest = frame.iloc[-1]
    if latest[FEATURES].isna().any():
        raise ValueError("INSUFFICIENT_FEATURE_HISTORY")
    snapshot = canonical_hash(
        frame[["nav_date", "unit_nav", "revision_hash", "first_observed_at"]].to_dict("records")
    )
    forecasts = []
    for record in request.models:
        payload = load_fund_model(record, request.fund_code)
        metadata = payload["metadata"]
        if datetime.fromisoformat(metadata["fit_end_at"]) > generated:
            raise ValueError("MODEL_FIT_AFTER_GENERATION")
        horizon = metadata["horizon"]
        short = horizon == 5
        target = request.target_dates.get(str(horizon))
        if target and (not request.calendar_source or str(target) <= latest["nav_date"]):
            raise ValueError("TARGET_CALENDAR_EVIDENCE_REQUIRED")
        reasons = list(metadata["reason_codes"])
        if not target:
            reasons.append("CALENDAR_UNVERIFIED")
        if (generated.date() - date.fromisoformat(latest["nav_date"])).days > 7:
            reasons.append("NAV_STALE")
        if (generated - datetime.fromisoformat(metadata["fit_end_at"])).days > 45:
            reasons.append("MODEL_STALE")
        with threadpool_limits(limits=1):
            value = float(
                short_probability(payload["model"], frame.iloc[-1:], payload.get("calibrator"))[0]
                if short
                else payload["model"].predict(frame.iloc[-1:][FEATURES])[0]
            )
        if not np.isfinite(value):
            raise ValueError("INVALID_MODEL_OUTPUT")
        forecasts.append(
            {
                "id": str(uuid.uuid4()),
                "fund_code": request.fund_code,
                "nature": "REAL",
                "evidence_type": "FORWARD_VALIDATION",
                "status": "FROZEN",
                "capability": "PROBABILITY" if short else "RETURN_POINT",
                "target_basis": "NAV_CHANGE",
                "target_version": SHORT_TARGET_VERSION if short else TARGET_VERSION,
                "feature_version": FEATURE_VERSION,
                "horizon_unit": "FUND_NAV_OBSERVATIONS",
                "generated_at": generated.isoformat(),
                "frozen_at": generated.isoformat(),
                "input_cutoff_at": generated.isoformat(),
                "data_available_through": latest["nav_date"],
                "anchor_date": latest["nav_date"],
                "anchor_value": latest["unit_nav"],
                "anchor_revision_hash": latest["revision_hash"],
                "model_version_id": metadata["id"],
                "algorithm": model_algorithm(metadata),
                "calibration_scheme": metadata.get("calibration_scheme"),
                "probability_validation_status": ("HISTORICAL_ONLY" if short else None),
                "model_family": metadata["model_family"],
                "model_artifact_hash": record["artifact_hash"],
                "data_snapshot_id": snapshot,
                "training_cutoff": metadata["training_cutoff"],
                "fit_end_at": metadata["fit_end_at"],
                "validation_status": metadata["validation_status"],
                "adjustment_eligible": False,
                "reason_codes": reasons,
                "calendar_source": request.calendar_source,
                "daily_path": None,
                "news_analysis_id": None,
                "news_usage": "NOT_USED",
                "usage_mode": "MODEL_ONLY",
                "points": [
                    {
                        "horizon": horizon,
                        "target_date": str(target) if target else None,
                        "point_return": None if short else str(value),
                        "probability_up": str(value) if short else None,
                        "quantiles": None,
                        "actual_value": None,
                        "actual_return": None,
                        "evaluation_status": "NOT_DUE" if target else "CALENDAR_UNVERIFIED",
                    }
                ],
            }
        )
    return {"fund_code": request.fund_code, "forecasts": forecasts, "nature": "REAL"}


def fund_forecast_error(point, actual_return):
    """上涨概率按Brier评价，幅度按绝对误差评价，两类输出不互相冒充。"""
    actual_up = actual_return > 0
    if point.get("probability_up") is not None:
        probability = Decimal(str(point["probability_up"]))
        if not probability.is_finite() or not 0 <= probability <= 1:
            raise ValueError("INVALID_FROZEN_PROBABILITY")
        return {
            "actual_up": actual_up,
            "brier_score": str((probability - Decimal(int(actual_up))) ** 2),
        }
    return {"absolute_error": str(abs(actual_return - Decimal(point["point_return"])))}


def evaluate_fund(request: FundEvaluateRequest):
    """按冻结目标或后续实际净值观测验证，不回写或猜测未来目标日期。"""
    now = request.evaluated_at or datetime.now(UTC)
    forecast = request.forecast
    point = forecast["points"][0]
    target = point.get("target_date")
    result = {
        "forecast_id": forecast["id"],
        "evaluated_at": now.isoformat(),
        "actual_value": None,
        "actual_return": None,
        "actual_data_version": None,
        "evaluation_status": "CALENDAR_UNVERIFIED",
    }
    if not target:
        # 模型标签按有效净值观测数计算；交易所日历不能替代基金实际公布的净值。
        # 对旧冻结预测只追加评价，不回填当时无法预知的目标日期。
        if forecast.get("horizon_unit") != "FUND_NAV_OBSERVATIONS":
            return result
        horizon = point.get("horizon")
        if horizon not in (5, 20, 60) or not forecast.get("anchor_date"):
            return result
        anchor = date.fromisoformat(forecast["anchor_date"])
        available = {}
        for row in request.observations:
            if (
                row.fund_code != forecast["fund_code"]
                or row.nature != "REAL"
                or row.quality_status == "REJECTED"
                or row.first_observed_at > now
                or row.fetched_at > now
                or not anchor < row.nav_date <= now.astimezone(ZoneInfo("Asia/Shanghai")).date()
            ):
                continue
            previous = available.get(row.nav_date)
            if previous is None or (row.first_observed_at, row.fetched_at) > (
                previous.first_observed_at,
                previous.fetched_at,
            ):
                available[row.nav_date] = row
        days = sorted(available)
        if len(days) < horizon:
            return result | {
                "evaluation_status": "WAITING_OBSERVATIONS",
                "observations_after_anchor": len(days),
                "required_observations": horizon,
            }
        actual = available[days[horizon - 1]]
        actual_return = actual.unit_nav / Decimal(forecast["anchor_value"]) - 1
        return result | {
            "evaluation_status": "EVALUATED",
            "actual_target_date": actual.nav_date.isoformat(),
            "target_date_origin": "OBSERVED_FUND_NAV",
            "observations_after_anchor": horizon,
            "required_observations": horizon,
            "calendar_warning": "NAV_OBSERVATION_COMPLETENESS_UNVERIFIED",
            "actual_value": str(actual.unit_nav),
            "actual_return": str(actual_return),
            "actual_data_version": actual.revision_hash,
            **fund_forecast_error(point, actual_return),
        }
    if date.fromisoformat(target) > now.date():
        return result | {"evaluation_status": "NOT_DUE"}
    candidates = [
        row
        for row in request.observations
        if row.fund_code == forecast["fund_code"]
        and str(row.nav_date) == target
        and row.first_observed_at <= now
        and row.fetched_at <= now
        and row.nature == "REAL"
        and row.quality_status != "REJECTED"
    ]
    if not candidates:
        return result | {"evaluation_status": "WAITING_DATA"}
    actual = max(candidates, key=lambda row: row.first_observed_at)
    actual_return = actual.unit_nav / Decimal(forecast["anchor_value"]) - 1
    return result | {
        "evaluation_status": "EVALUATED",
        "actual_value": str(actual.unit_nav),
        "actual_return": str(actual_return),
        "actual_data_version": actual.revision_hash,
        **fund_forecast_error(point, actual_return),
    }
