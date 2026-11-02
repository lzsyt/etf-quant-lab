"""独立的开盘到开盘目标；不改变旧模型、冻结预测或收盘到收盘标签。"""

import gzip
import hashlib
import importlib.metadata
import json
import os
from datetime import UTC, date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Literal
from zoneinfo import ZoneInfo

import joblib
import numpy as np
import pandas as pd
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import TimeSeriesSplit
from xgboost import XGBClassifier

from etf_quant.features import FEATURES
from etf_quant.models import MODEL_LOCK, estimator, logit, metrics, prepare
from etf_quant.schemas import Bar

LABEL_VERSION = "OPEN_TO_OPEN_H1_V1"
TARGET_SEMANTICS = "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
PROTOCOL_VERSION = "OPEN_NET_REBALANCE_V1"
TRAINING_VERSION = "open-target-calibration-candidates-v1"
MARKET_ZONE = ZoneInfo("Asia/Shanghai")
CANDIDATES = (0, 20, 60, 120)


class ForwardIdentity(BaseModel):
    """新实验身份必须完整提供，错误版本在进入模型前即被拒绝。"""

    model_config = ConfigDict(extra="forbid")
    symbol: str = Field(pattern=r"^\d{6}$")
    algorithm: Literal["lightgbm", "xgboost"]
    horizon: Literal[1] = 1
    feature_version: Literal["ohlcv-v2"] = "ohlcv-v2"
    label_version: Literal["OPEN_TO_OPEN_H1_V1"] = LABEL_VERSION
    protocol_version: Literal["OPEN_NET_REBALANCE_V1"] = PROTOCOL_VERSION


class ForwardTrainRequest(ForwardIdentity):
    """训练截止是实际信息可用时刻，不是最后行情日期的别名。"""

    seed: Literal[42] = 42
    bars: list[Bar] = Field(min_length=320, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(min_length=3, max_length=20000)
    training_cutoff_at: AwareDatetime
    code_version: str = Field(pattern=r"^[a-f0-9]{7,40}$")
    corporate_action_dates: list[date] = Field(default_factory=list)
    corporate_action_coverage: Literal["UNKNOWN", "VERIFIED"] = "UNKNOWN"


class ForwardPredictRequest(ForwardIdentity):
    """推理按指定真实截止和信号日验证，不能为过期开盘补造预测。"""

    model_id: str = Field(pattern=r"^[a-zA-Z0-9_-]+$")
    bars: list[Bar] = Field(min_length=61, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(min_length=3, max_length=20000)
    signal_date: date
    cutoff_at: AwareDatetime
    corporate_action_dates: list[date] = Field(default_factory=list)


def market_time(day: date, clock: time) -> datetime:
    """把交易所市场时刻转换为UTC，禁止用自然日平移标签。"""
    return datetime.combine(day, clock, MARKET_ZONE).astimezone(UTC)


def trading_window(signal_date: date, trading_dates: list[date]) -> tuple[date, date]:
    """严格读取确认日历相邻两日，覆盖不足时拒绝而不是猜测工作日。"""
    calendar = sorted(set(trading_dates))
    if signal_date not in calendar:
        raise ValueError("SIGNAL_DATE_NOT_IN_CONFIRMED_CALENDAR")
    offset = calendar.index(signal_date)
    if offset + 2 >= len(calendar):
        raise ValueError("ENTRY_OR_EVALUATION_CALENDAR_UNCONFIRMED")
    return calendar[offset + 1], calendar[offset + 2]


def _available_bars(bars: list[Bar], cutoff: datetime) -> list[Bar]:
    """仅纳入截止前实际取得且已完整收盘的日线；历史修订限制另行披露。"""
    return [
        bar
        for bar in bars
        if bar.fetched_at <= cutoff and market_time(bar.trade_date, time(15)) <= cutoff
    ]


def labeled_frame(request: ForwardTrainRequest) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """构造真实结束时间标签；缺交易日价格和公司行动影响样本显式隔离。"""
    if request.training_cutoff_at > datetime.now(UTC):
        raise ValueError("TRAINING_CUTOFF_CANNOT_BE_IN_FUTURE")
    if len({bar.nature for bar in request.bars}) != 1:
        raise ValueError("MIXED_TRAINING_DATA_NATURE")
    if any(bar.symbol != request.symbol for bar in request.bars):
        raise ValueError("TRAINING_SYMBOL_MISMATCH")
    bars = _available_bars(request.bars, request.training_cutoff_at)
    benchmark = _available_bars(request.benchmark, request.training_cutoff_at)
    if len(bars) < 320 or any(bar.symbol != request.symbol for bar in bars):
        raise ValueError("TRAINING_AVAILABLE_BARS_INSUFFICIENT_OR_SYMBOL_MISMATCH")
    if any(bar.price_basis != "RAW" for bar in bars + benchmark):
        raise ValueError("FORWARD_REQUIRES_RAW_PRICE")
    if request.benchmark and not benchmark:
        raise ValueError("BENCHMARK_NOT_AVAILABLE_AT_TRAINING_CUTOFF")
    frame = prepare(bars, benchmark, request.trading_dates)
    # prepare按确认日历补NaN而非补价格；shift的两步是两次已确认开盘，不是两条有价行。
    frame["entry_date"] = pd.Series(frame.index, index=frame.index).shift(-1)
    frame["target_date"] = pd.Series(frame.index, index=frame.index).shift(-2)
    prices = {bar.trade_date: bar.open for bar in bars}
    entry_prices = [prices.get(day.date()) if pd.notna(day) else None for day in frame.entry_date]
    end_prices = [prices.get(day.date()) if pd.notna(day) else None for day in frame.target_date]
    # 标签比较和价格收益先按Decimal计算，随后才转为模型所需数值，平盘严格归0。
    frame["actual_return"] = [
        float(end / entry - Decimal(1)) if entry is not None and end is not None else np.nan
        for entry, end in zip(entry_prices, end_prices, strict=True)
    ]
    frame["target"] = [
        float(end > entry) if entry is not None and end is not None else np.nan
        for entry, end in zip(entry_prices, end_prices, strict=True)
    ]
    frame["feature_at"] = pd.to_datetime(
        [market_time(day.date(), time(15)) for day in frame.index], utc=True
    )
    frame["label_end_at"] = pd.to_datetime(
        [
            market_time(day.date(), time(9, 30)) if pd.notna(day) else pd.NaT
            for day in frame.target_date
        ],
        utc=True,
    )
    fetched = pd.Series({pd.Timestamp(bar.trade_date): bar.fetched_at for bar in bars}).reindex(
        frame.index
    )
    # 市场标签结束与本系统取得标签证据是两个时刻；分段用前者，当前训练可用性用后者。
    frame["label_available_at"] = pd.to_datetime(
        [
            max(values) if all(pd.notna(value) for value in values) else pd.NaT
            for values in zip(fetched, fetched.shift(-1), fetched.shift(-2), frame.label_end_at)
        ],
        utc=True,
    )
    action_mask = pd.Series(False, index=frame.index)
    for action in request.corporate_action_dates:
        # 原始价格在份额变化附近不能混作同一可比尺度。保守隔离60个特征交易日及标签窗口。
        for offset, signal in enumerate(frame.index):
            left = frame.index[max(0, offset - 60)].date()
            end = frame.iloc[offset].target_date
            if pd.notna(end) and left <= action <= end.date():
                action_mask.iloc[offset] = True
    complete = frame[FEATURES + ["target", "target_date"]].notna().all(axis=1)
    matured = (frame.label_end_at <= pd.Timestamp(request.training_cutoff_at)) & (
        frame.label_available_at <= pd.Timestamp(request.training_cutoff_at)
    )
    sample = frame.loc[complete & matured & ~action_mask].copy()
    evidence = {
        "input_bars": len(request.bars),
        "available_bars": len(bars),
        "unavailable_input_bars": len(request.bars) - len(bars),
        "incomplete_feature_or_label_rows": int((~complete).sum()),
        "unmatured_labels": int((complete & ~matured).sum()),
        "corporate_action_quarantined_rows": int((complete & action_mask).sum()),
        "usable_samples": len(sample),
    }
    return frame, sample, evidence


def purge_before_time(section: pd.DataFrame, boundary: datetime) -> pd.DataFrame:
    """标签真实结束必须早于下一段首个特征可用时刻，不照搬旧h=1间隔。"""
    return section.loc[section.label_end_at < boundary].copy()


def _range(section: pd.DataFrame) -> dict:
    """保存可独立重算分割边界的交易日期、UTC时刻和样本数。"""
    if section.empty:
        return {"samples": 0, "start": None, "end": None, "last_label_date": None}
    return {
        "samples": len(section),
        "start": str(section.index[0].date()),
        "end": str(section.index[-1].date()),
        "first_feature_at": section.feature_at.iloc[0].isoformat(),
        "last_feature_at": section.feature_at.iloc[-1].isoformat(),
        "last_label_date": str(section.target_date.max().date()),
        "last_label_end_at": section.label_end_at.max().isoformat(),
        "last_label_available_at": section.label_available_at.max().isoformat(),
    }


def _raw(model, section: pd.DataFrame) -> np.ndarray:
    """只获取行情模型的类别概率，拒绝非有限或超界结果。"""
    values = np.asarray(model.predict_proba(section[FEATURES])[:, 1], dtype=float)
    if not np.isfinite(values).all() or (values < 0).any() or (values > 1).any():
        raise ValueError("INVALID_MODEL_PROBABILITY")
    return values


def _calibrated(raw: np.ndarray, calibrator) -> np.ndarray:
    """RAW候选保持原概率，其余使用独立历史校准器。"""
    return raw if calibrator is None else calibrator.predict_proba(logit(raw))[:, 1]


def probability_diagnostics(labels, probabilities, raw=None) -> dict:
    """可靠性区间包含空箱计数；Brier只接受真正概率，不接受融合分数。"""
    y, p = np.asarray(labels), np.asarray(probabilities)
    if not len(y) or not np.isfinite(p).all() or (p < 0).any() or (p > 1).any():
        raise ValueError("INVALID_PROBABILITY_DIAGNOSTIC_INPUT")
    report = metrics(y, p)
    bins = []
    for index in range(10):
        lower, upper = index / 10, (index + 1) / 10
        mask = (p >= lower) & ((p < upper) if index < 9 else (p <= upper))
        bins.append(
            {
                "lower": lower,
                "upper": upper,
                "count": int(mask.sum()),
                "mean_probability": float(p[mask].mean()) if mask.any() else None,
                "observed_up_frequency": float(y[mask].mean()) if mask.any() else None,
            }
        )
    report["reliability_curve"] = bins
    if raw is not None:
        difference = p - np.asarray(raw)
        report["raw_calibrated_difference"] = {
            "mean": float(difference.mean()),
            "mean_absolute": float(np.abs(difference).mean()),
            "maximum_absolute": float(np.abs(difference).max()),
        }
    return report


def _fit_candidate(model, pool: pd.DataFrame, size: int, seed: int):
    """预先声明窗口不足或单类时跳过，禁止临时扩窗借用验证或测试样本。"""
    if size == 0:
        return None, pool.iloc[:0], None
    if len(pool) < size:
        return None, pool.iloc[:0], "INSUFFICIENT_CALIBRATION_SAMPLES"
    section = pool.iloc[-size:]
    if section.target.nunique() < 2:
        return None, section, "SINGLE_CLASS_CALIBRATION"
    calibrator = LogisticRegression(random_state=seed, max_iter=500)
    calibrator.fit(logit(_raw(model, section)), section.target)
    return calibrator, section, None


def _walk_forward(sample: pd.DataFrame, algorithm: str, seed: int) -> list:
    """三折只用最终测试前数据；各折候选选择也仅使用该折过去的验证段。"""
    folds = []
    for history_index, test_index in TimeSeriesSplit(n_splits=3).split(sample):
        test = sample.iloc[test_index]
        history = purge_before_time(sample.iloc[history_index], test.feature_at.iloc[0])
        a, b = int(len(history) * 0.6), int(len(history) * 0.8)
        fit = purge_before_time(history.iloc[:a], history.feature_at.iloc[a])
        pool = purge_before_time(history.iloc[a:b], history.feature_at.iloc[b])
        validation = history.iloc[b:]
        evidence = {
            "fit": _range(fit),
            "calibration_pool": _range(pool),
            "validation": _range(validation),
            "test": _range(test),
        }
        if len(fit) < 100 or fit.target.nunique() < 2:
            folds.append({**evidence, "status": "SKIPPED", "reason": "INSUFFICIENT_FIT_HISTORY"})
            continue
        model = estimator(algorithm, seed)
        model.fit(fit[FEATURES], fit.target)
        raw_validation = _raw(model, validation)
        options = []
        for size in CANDIDATES:
            calibrator, _, reason = _fit_candidate(model, pool, size, seed)
            if not reason:
                diagnostic = metrics(validation.target, _calibrated(raw_validation, calibrator))
                options.append(
                    (diagnostic["brier_score"], diagnostic["log_loss"], size, calibrator)
                )
        _, _, selected_size, calibrator = min(options, key=lambda option: option[:3])
        raw = _raw(model, test)
        folds.append(
            {
                **evidence,
                "status": "SUCCEEDED",
                "selected_calibration": "RAW" if selected_size == 0 else f"PLATT_{selected_size}",
                "metrics": probability_diagnostics(test.target, _calibrated(raw, calibrator), raw),
            }
        )
    return folds


def _sha(path: Path) -> str:
    """记录工件内容摘要，读取可信目录前再次校验，不把目录存在当作工件成功。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _model_directory() -> Path:
    """复用既有模型目录，测试通过环境变量隔离，不新增部署基础设施。"""
    return Path(os.environ.get("ETF_MODEL_DIR", ".runtime/models"))


def train_forward_model(request: ForwardTrainRequest) -> dict:
    """串行训练新标签工件；候选仅用验证选取，最终测试结果不能反向修改方案。"""
    with MODEL_LOCK:
        frame, sample, sample_evidence = labeled_frame(request)
        if len(sample) < 250:
            raise ValueError("FORWARD_EFFECTIVE_SAMPLES_BELOW_250")
        dependencies = {
            name: importlib.metadata.version(name)
            for name in ("numpy", "pandas", "scikit-learn", "lightgbm", "xgboost-cpu")
        }
        frozen_request = request.model_dump(mode="json")
        source_digests = {
            name: _sha(Path(__file__).with_name(name))
            for name in ("forward_models.py", "features.py", "models.py")
        }
        # 身份包含标签、协议、完整输入、真实截止、代码及依赖；同任务重试复用原工件。
        identity_source = {
            "request": frozen_request,
            "dependencies": dependencies,
            "training_version": TRAINING_VERSION,
            "source_code_sha256": source_digests,
        }
        identity = hashlib.sha256(json.dumps(identity_source, sort_keys=True).encode()).hexdigest()
        model_id = f"{request.symbol}-{request.algorithm}-open-h1-{identity[:16]}"
        directory = _model_directory()
        directory.mkdir(parents=True, exist_ok=True)
        metadata_path = directory / f"{model_id}.json"
        if metadata_path.exists():
            return _load_metadata(model_id)
        n = len(sample)
        a, b, c = int(n * 0.6), int(n * 0.7), int(n * 0.8)
        fit = purge_before_time(sample.iloc[:a], sample.feature_at.iloc[a])
        calibration_pool = purge_before_time(sample.iloc[a:b], sample.feature_at.iloc[b])
        validation = purge_before_time(sample.iloc[b:c], sample.feature_at.iloc[c])
        test = sample.iloc[c:]
        if fit.target.nunique() < 2:
            raise ValueError("SINGLE_CLASS_FIT")
        evaluation_model = estimator(request.algorithm, request.seed)
        evaluation_model.fit(fit[FEATURES], fit.target)
        raw_validation = _raw(evaluation_model, validation)
        candidate_reports, candidates = {}, {}
        for size in CANDIDATES:
            name = "RAW" if size == 0 else f"PLATT_{size}"
            calibrator, section, reason = _fit_candidate(
                evaluation_model, calibration_pool, size, request.seed
            )
            if reason:
                candidate_reports[name] = {
                    "status": "SKIPPED",
                    "reason": reason,
                    "calibration": _range(section),
                }
                continue
            diagnostic = probability_diagnostics(
                validation.target, _calibrated(raw_validation, calibrator), raw_validation
            )
            candidate_reports[name] = {
                "status": "SUCCEEDED",
                "calibration": _range(section),
                "validation": diagnostic,
            }
            candidates[name] = (size, calibrator)
        selected = min(
            candidates,
            key=lambda name: (
                candidate_reports[name]["validation"]["brier_score"],
                candidate_reports[name]["validation"]["log_loss"],
                CANDIDATES.index(candidates[name][0]),
            ),
        )
        selected_size, evaluation_calibrator = candidates[selected]
        # 从此以后方案冻结；下面才第一次读取最终测试概率并评价。
        raw_test = _raw(evaluation_model, test)
        test_probability = _calibrated(raw_test, evaluation_calibrator)
        frequency = float(fit.target.mean())
        final_metrics = probability_diagnostics(test.target, test_probability, raw_test)
        recent = sample.iloc[-504:]
        if selected_size:
            production_calibration = recent.iloc[-selected_size:]
            production_fit = purge_before_time(
                recent.iloc[:-selected_size], production_calibration.feature_at.iloc[0]
            )
        else:
            production_fit, production_calibration = recent, recent.iloc[:0]
        if production_fit.target.nunique() < 2:
            raise ValueError("PRODUCTION_SINGLE_CLASS_FIT")
        production_model = estimator(request.algorithm, request.seed)
        production_model.fit(production_fit[FEATURES], production_fit.target)
        production_calibrator, _, reason = _fit_candidate(
            production_model, production_calibration, selected_size, request.seed
        )
        if reason:
            raise ValueError("PRODUCTION_" + reason)
        now = datetime.now(UTC).isoformat()
        parameters = {
            "estimator": production_model.get_params(),
            "calibration_candidates": ["RAW", "PLATT_20", "PLATT_60", "PLATT_120"],
            "selection_rule": "VALIDATION_BRIER_THEN_LOG_LOSS_THEN_PREDECLARED_ORDER",
            "selected_calibration": selected,
            "production_window": 504,
            "evaluation_split": [0.6, 0.1, 0.1, 0.2],
            "threshold_search": False,
            "training_version": TRAINING_VERSION,
        }
        # XGBoost缺省的NaN只是参数表示，不可流入JSON/数据库。
        parameters["estimator"] = {
            key: str(value) if isinstance(value, float) and not np.isfinite(value) else value
            for key, value in parameters["estimator"].items()
        }
        metadata = {
            **request.model_dump(include=set(ForwardIdentity.model_fields)),
            "id": model_id,
            "target_semantics": TARGET_SEMANTICS,
            "features": FEATURES,
            "nature": request.bars[0].nature,
            "price_basis": "RAW",
            "benchmark_symbol": request.benchmark[0].symbol if request.benchmark else None,
            "data_sha256": hashlib.sha256(
                json.dumps(frozen_request, sort_keys=True).encode()
            ).hexdigest(),
            "data_cutoff": str(frame.index[-1].date()),
            "training_cutoff_at": request.training_cutoff_at.isoformat(),
            "label_end_cutoff_at": sample.label_end_at.max().isoformat(),
            "label_available_cutoff_at": sample.label_available_at.max().isoformat(),
            "training_range": {
                "train": _range(fit),
                "calibration": _range(calibration_pool),
                "validation": _range(validation),
                "test": _range(test),
            },
            "production_training_range": {
                "fit": _range(production_fit),
                "calibration": _range(production_calibration),
            },
            "fit_data_cutoff": _range(production_fit)["last_label_date"],
            "calibration_data_cutoff": _range(production_calibration)["last_label_date"],
            "calibration_scheme": selected,
            "hyperparameters": parameters,
            "model_parameter_version": hashlib.sha256(
                json.dumps(parameters, sort_keys=True).encode()
            ).hexdigest()[:16],
            "code_version": request.code_version,
            "source_code_sha256": source_digests,
            "training_version": TRAINING_VERSION,
            "prediction_model_role": "FORWARD_PRODUCTION",
            "historical_predictions_model_role": "PURGED_HELD_OUT_EVALUATION",
            "metrics": {
                "calibration_candidates": candidate_reports,
                "validation": candidate_reports[selected]["validation"],
                "validation_frequency_baseline": probability_diagnostics(
                    validation.target, np.full(len(validation), frequency)
                ),
                "test": final_metrics,
                "test_raw": probability_diagnostics(test.target, raw_test),
                "test_frequency_baseline": probability_diagnostics(
                    test.target, np.full(len(test), frequency)
                ),
                "frequency_baseline": frequency,
                "walk_forward": _walk_forward(
                    purge_before_time(sample.iloc[:c], test.feature_at.iloc[0]),
                    request.algorithm,
                    request.seed,
                ),
                "selection_frozen_before_test": True,
            },
            "oos_predictions": [
                {
                    "signal_date": str(day.date()),
                    "entry_date": str(row.entry_date.date()),
                    "evaluation_date": str(row.target_date.date()),
                    "label_end_at": row.label_end_at.isoformat(),
                    "raw_probability": float(raw),
                    "probability": float(value),
                    "actual_return": float(row.actual_return),
                    "actual_up": bool(row.target),
                    "evaluation_type": "HISTORICAL_OOS",
                }
                for (day, row), raw, value in zip(
                    test.iterrows(), raw_test, test_probability, strict=True
                )
            ],
            "sample_evidence": sample_evidence,
            "corporate_action_coverage": request.corporate_action_coverage,
            "corporate_action_dates": [str(day) for day in request.corporate_action_dates],
            "limitations": [
                "HISTORICAL_DATA_REVISIONS_AND_POINT_IN_TIME_AVAILABILITY_UNVERIFIED",
                "PRICE_RETURN_LABEL_NOT_NET_PROFIT_OR_TOTAL_RETURN",
            ]
            + (
                ["CORPORATE_ACTION_COVERAGE_UNVERIFIED"]
                if request.corporate_action_coverage != "VERIFIED"
                else []
            ),
            "created_at": now,
            "available_at": now,
            "dependencies": dependencies,
            "evaluation_type": "HISTORICAL_OOS",
        }
        path = directory / f"{model_id}.joblib"
        if path.exists():
            raise ValueError("INCOMPLETE_EXISTING_MODEL_ARTIFACT_DO_NOT_OVERWRITE")
        artifact = {
            "model": production_model,
            "calibrator": production_calibrator,
            "evaluation_model": evaluation_model,
            "evaluation_calibrator": evaluation_calibrator,
            "metadata": metadata,
        }
        joblib.dump(artifact, path)
        files = [path]
        if request.algorithm == "xgboost":
            model_path = directory / f"{model_id}.xgb.json"
            portable_path = directory / f"{model_id}.portable.joblib"
            production_model.save_model(model_path)
            joblib.dump({"calibrator": production_calibrator, "metadata": metadata}, portable_path)
            files.extend([model_path, portable_path])
        snapshot_path = directory / f"{model_id}.data.json.gz"
        with gzip.open(snapshot_path, "wt", encoding="utf-8") as stream:
            json.dump(frozen_request, stream, ensure_ascii=False)
        files.append(snapshot_path)
        metadata["artifact_sha256"] = {item.name: _sha(item) for item in files}
        metadata["available_at"] = datetime.now(UTC).isoformat()
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8"
        )
        return metadata


def _load_metadata(model_id: str) -> dict:
    """元数据和工件缺失或摘要不一致时拒绝；绝不静默加载另一最新算法版本。"""
    directory = _model_directory()
    path = directory / f"{model_id}.json"
    if not path.is_file():
        raise ValueError("MODEL_METADATA_MISSING")
    metadata = json.loads(path.read_text(encoding="utf-8"))
    hashes = metadata.get("artifact_sha256")
    if metadata.get("id") != model_id or not hashes:
        raise ValueError("MODEL_ID_OR_ARTIFACT_EVIDENCE_MISSING")
    for name, expected in hashes.items():
        if Path(name).name != name:
            raise ValueError("INVALID_MODEL_ARTIFACT_PATH")
        item = directory / name
        if not item.is_file() or _sha(item) != expected:
            raise ValueError("MODEL_ARTIFACT_MISSING_OR_DIGEST_MISMATCH")
    return metadata


def predict_forward(request: ForwardPredictRequest) -> dict:
    """逐项检查新协议、期限、可用时刻和新鲜度，缺失概率从不替换为中性值。"""
    metadata = _load_metadata(request.model_id)
    for key in ForwardIdentity.model_fields:
        if metadata.get(key) != getattr(request, key):
            raise ValueError("MODEL_IDENTITY_MISMATCH_" + key.upper())
    if metadata.get("prediction_model_role") != "FORWARD_PRODUCTION":
        raise ValueError("LEGACY_EVALUATION_MODEL_NOT_ALLOWED")
    if metadata.get("target_semantics") != TARGET_SEMANTICS:
        raise ValueError("MODEL_TARGET_SEMANTICS_MISMATCH")
    if datetime.fromisoformat(metadata["available_at"]) > request.cutoff_at:
        raise ValueError("MODEL_NOT_AVAILABLE_AT_PREDICTION_CUTOFF")
    data_cutoff = date.fromisoformat(metadata["data_cutoff"])
    if request.signal_date < data_cutoff or (request.signal_date - data_cutoff).days > 45:
        raise ValueError("MODEL_DATA_CUTOFF_FUTURE_OR_STALE")
    entry, evaluation = trading_window(request.signal_date, request.trading_dates)
    generated_at = datetime.now(UTC)
    if max(request.cutoff_at, generated_at) >= market_time(entry, time(9, 30)):
        raise ValueError("LATE_PREDICTION_CANNOT_ENTER_PAST_OPEN")
    if request.cutoff_at > generated_at:
        raise ValueError("PREDICTION_CUTOFF_CANNOT_BE_IN_FUTURE")
    bars = _available_bars(request.bars, request.cutoff_at)
    benchmark = _available_bars(request.benchmark, request.cutoff_at)
    if len(bars) != len(request.bars) or len(benchmark) != len(request.benchmark):
        raise ValueError("PREDICTION_INPUT_NOT_AVAILABLE_AT_CUTOFF")
    if not bars or max(bar.trade_date for bar in bars) != request.signal_date:
        raise ValueError("LATEST_COMPLETE_BAR_DOES_NOT_MATCH_SIGNAL_DATE")
    if any(
        bar.symbol != request.symbol or bar.nature != metadata["nature"] or bar.price_basis != "RAW"
        for bar in bars
    ):
        raise ValueError("PREDICTION_BAR_IDENTITY_MISMATCH")
    if metadata["benchmark_symbol"] and (
        not benchmark or benchmark[0].symbol != metadata["benchmark_symbol"]
    ):
        raise ValueError("PREDICTION_BENCHMARK_MISMATCH")
    frame = prepare(bars, benchmark, request.trading_dates)
    if frame.iloc[-1][FEATURES].isna().any():
        raise ValueError("LATEST_FEATURES_INCOMPLETE")
    # 训练后新登记的公司行动同样有效，不能等到30天重训时才隔离受影响信号。
    action_dates = set(request.corporate_action_dates) | {
        date.fromisoformat(action) for action in metadata.get("corporate_action_dates", [])
    }
    if any(
        frame.index[max(0, len(frame) - 61)].date() <= action <= evaluation
        for action in action_dates
    ):
        raise ValueError("CORPORATE_ACTION_AFFECTED_PREDICTION_QUARANTINED")
    directory = _model_directory()
    if request.algorithm == "xgboost":
        artifact = joblib.load(directory / f"{request.model_id}.portable.joblib")
        model = XGBClassifier(n_jobs=1, device="cpu")
        model.load_model(directory / f"{request.model_id}.xgb.json")
    else:
        artifact = joblib.load(directory / f"{request.model_id}.joblib")
        model = artifact["model"]
    raw = _raw(model, frame.iloc[[-1]])
    probability = float(_calibrated(raw, artifact["calibrator"])[0])
    if not np.isfinite(probability) or not 0 <= probability <= 1:
        raise ValueError("INVALID_CALIBRATED_PROBABILITY")
    # 耗时推理若跨过开盘也必须拒绝，不能把请求开始时刻冒充信号生成时刻。
    generated_at = datetime.now(UTC)
    if generated_at >= market_time(entry, time(9, 30)):
        raise ValueError("LATE_PREDICTION_CANNOT_ENTER_PAST_OPEN")
    frozen_input = request.model_dump(
        mode="json",
        include={
            "bars",
            "benchmark",
            "trading_dates",
            "signal_date",
            "cutoff_at",
            "feature_version",
            "label_version",
            "protocol_version",
        },
    )
    for key in ("bars", "benchmark"):
        frozen_input[key] = sorted(
            frozen_input[key], key=lambda bar: (bar["symbol"], bar["trade_date"])
        )
    frozen_input["trading_dates"] = sorted(set(frozen_input["trading_dates"]))
    frozen_input["corporate_action_dates"] = sorted(str(action) for action in action_dates)
    input_sha256 = hashlib.sha256(
        json.dumps(frozen_input, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
    ).hexdigest()
    return {
        **request.model_dump(include=set(ForwardIdentity.model_fields)),
        "model_id": request.model_id,
        "target_semantics": TARGET_SEMANTICS,
        "signal_date": str(request.signal_date),
        "entry_date": str(entry),
        "evaluation_date": str(evaluation),
        "probability": probability,
        "raw_probability": float(raw[0]),
        "raw_score": float(raw[0]),
        "direction": "UP" if probability >= 0.5 else "DOWN",
        "nature": metadata["nature"],
        "price_basis": "RAW",
        "calibration_scheme": metadata["calibration_scheme"],
        "training_data_cutoff": metadata["label_available_cutoff_at"],
        "model_input_data_cutoff": metadata["data_cutoff"],
        "fit_data_cutoff": metadata["fit_data_cutoff"],
        "calibration_data_cutoff": metadata["calibration_data_cutoff"],
        "model_parameter_version": metadata["model_parameter_version"],
        "model_available_at": metadata["available_at"],
        "generated_at": generated_at.isoformat(),
        "input_cutoff_at": request.cutoff_at.isoformat(),
        "input_sha256": input_sha256,
        "corporate_action_dates": frozen_input["corporate_action_dates"],
        "feature_values": {name: float(frame.iloc[-1][name]) for name in FEATURES},
        "model_role": "FORWARD_PRODUCTION",
        "limitations": metadata["limitations"],
        "score_meaning": "下一开盘到再下一开盘的RAW价格上涨概率；不是扣费盈利概率或账户总回报",
    }
