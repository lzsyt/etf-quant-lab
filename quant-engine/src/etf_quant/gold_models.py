"""黄金63/126交易日独立研究：成熟标签清除、顺序校准、滚动验证及不可变工件。"""

import gzip
import hashlib
import importlib.metadata
import json
import os
import threading
from datetime import UTC, datetime, time
from decimal import ROUND_HALF_UP, Decimal
from itertools import product
from pathlib import Path
from typing import Literal
from zoneinfo import ZoneInfo

import joblib
import numpy as np
import pandas as pd
import xgboost as xgb
from lightgbm import LGBMClassifier, LGBMRegressor
from pydantic import BaseModel, Field, field_validator
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, mean_absolute_error
from threadpoolctl import threadpool_limits

from etf_quant.gold_data import (
    BENCHMARK,
    GoldObservation,
    calendar_quality,
    canonical_hash,
    estimated_target,
)

FEATURE_VERSION = "gold-close-trend-vol-v1"
LABEL_VERSION = "SGE_CLOSE_RETURN_63_126_V1"
CALIBRATION_VERSION = "gold-purged-platt-residual-v1"
TRAINING_VERSION = "gold-expanding-3fold-final504-v1"
FEATURES = ["return_21", "return_63", "return_126", "volatility_21", "volatility_63", "distance_63"]
MINIMUM_ROWS = 1200
NOMINAL_COVERAGE = 0.8
GOLD_LOCK = threading.Lock()
ALGORITHM_NAMES = {"lightgbm": "LightGBM_small_fixed", "xgboost": "XGBoost_small_fixed"}


class GoldTrainRequest(BaseModel):
    """黄金工件不接受ETF权重或ETF训练标签。"""

    observations: list[GoldObservation] = Field(max_length=100000)
    code_version: str = Field(default="UNKNOWN", max_length=80)
    fit_at: datetime | None = None
    algorithms: list[Literal["lightgbm", "xgboost"]] = Field(
        default_factory=lambda: ["lightgbm", "xgboost"], min_length=1, max_length=2
    )

    @field_validator("algorithms")
    @classmethod
    def unique_algorithms(cls, value):
        """同次请求每算法只训练一次，避免重复生成同身份工件。"""
        if len(value) != len(set(value)):
            raise ValueError("黄金训练算法不得重复")
        return value


class GoldPredictRequest(BaseModel):
    """预测只加载显式登记的黄金工件；不在浏览或推理时训练。"""

    observations: list[GoldObservation] = Field(max_length=100000)
    models: list[dict] = Field(default_factory=list, max_length=20)
    generated_at: datetime | None = None
    code_version: str = Field(default="UNKNOWN", max_length=80)


def benchmark_frame(observations):
    """只选官方国内收盘当前版本；CSV未核验值和其他价格口径不能进入模型。"""
    rows = []
    for value in observations:
        row = value if isinstance(value, GoldObservation) else GoldObservation.model_validate(value)
        if row.series_id != BENCHMARK:
            continue
        if row.quality not in ("REAL_OFFICIAL_CURRENT_VINTAGE", "REAL_VERIFIED_CSV"):
            continue
        if row.unit != "CNY/g" or row.value <= 0:
            raise ValueError("黄金基准必须为正数CNY/g日收盘")
        rows.append(row.model_dump(mode="json"))
    if not rows:
        return pd.DataFrame()
    rows.sort(key=lambda row: (row["observation_date"], row["fetched_at"]))
    frame = pd.DataFrame(rows).drop_duplicates("observation_date", keep="last")
    frame["date"] = pd.to_datetime(frame["observation_date"])
    frame["close"] = frame["value"].map(float)
    frame = frame.sort_values("date").reset_index(drop=True)
    # 只使用实际收盘观测构成上金所历史会话，不借用沪深日历或人为填平缺日。
    frame["session_index"] = np.arange(len(frame))
    daily = frame["close"].pct_change(fill_method=None)
    for lag in (21, 63, 126):
        frame[f"return_{lag}"] = frame["close"].pct_change(lag, fill_method=None)
    frame["volatility_21"] = daily.rolling(21).std()
    frame["volatility_63"] = daily.rolling(63).std()
    frame["distance_63"] = frame["close"] / frame["close"].rolling(63).mean() - 1
    return frame


def labeled_frame(frame, horizon):
    """标签严格按后续第h个已观察上金所会话，缺未来价格不生成标签。"""
    result = frame.copy()
    result["target_date"] = result["date"].shift(-horizon)
    result["target_index"] = result["session_index"] + horizon
    result["target_return"] = result["close"].shift(-horizon) / result["close"] - 1
    result["up"] = (result["target_return"] > 0).astype(int)
    return result.dropna(subset=FEATURES + ["target_return", "target_date"])


def temporal_split(labeled, fit_index, calibration_size=252):
    """校准标签先于拟合时点成熟，训练标签先于校准首日成熟，清除两处分割污染。"""
    matured = labeled[labeled["target_index"] < fit_index]
    calibration = matured.tail(calibration_size)
    if len(calibration) < 126:
        raise ValueError("黄金校准成熟样本不足126")
    train = labeled[labeled["target_index"] < int(calibration["session_index"].iloc[0])]
    if len(train) < 252:
        raise ValueError("黄金剔除跨界标签后训练样本不足252")
    return train, calibration


def logit(probability):
    """只作校准数值变换，不把未经校准的分数宣称上涨概率。"""
    p = np.clip(probability, 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p)).reshape(-1, 1)


def fit_bundle(train, calibration, algorithm="lightgbm"):
    """固定小树无网格搜索；概率和残差区间仅用训练后独立校准段。"""
    params = {
        "n_estimators": 60,
        "max_depth": 3,
        "learning_rate": 0.04,
        "reg_lambda": 5.0,
        "n_jobs": 1,
        "random_state": 42,
    }
    if algorithm == "lightgbm":
        params.update(
            num_leaves=7,
            min_child_samples=40,
            deterministic=True,
            force_col_wise=True,
            verbosity=-1,
        )
        median_model = LGBMRegressor(objective="quantile", alpha=0.5, **params)
        classifier_type = LGBMClassifier
    elif algorithm == "xgboost":
        # 显式使用0.5分位回归，保持与黄金既有中位收益/残差区间一致的目标语义。
        params.update(tree_method="hist", min_child_weight=10, verbosity=0)
        median_model = xgb.XGBRegressor(objective="reg:quantileerror", quantile_alpha=0.5, **params)
        classifier_type = xgb.XGBClassifier
    else:
        raise ValueError("不支持的黄金模型算法")
    median_model.fit(train[FEATURES], train["target_return"])
    residuals = calibration["target_return"].to_numpy() - median_model.predict(
        calibration[FEATURES]
    )
    classifier, calibrator = None, None
    # 单类或少数类太少时概率留空，不用固定0.5伪造校准结果。
    if train["up"].value_counts().min() >= 20 and train["up"].nunique() == 2:
        classifier = classifier_type(**params)
        classifier.fit(train[FEATURES], train["up"])
        if calibration["up"].nunique() == 2 and calibration["up"].value_counts().min() >= 20:
            calibrator = LogisticRegression(C=0.5, max_iter=300, random_state=42)
            calibrator.fit(
                logit(classifier.predict_proba(calibration[FEATURES])[:, 1]), calibration["up"]
            )
    return {
        "algorithm": algorithm,
        "median_model": median_model,
        "classifier": classifier,
        "calibrator": calibrator,
        "residual_quantiles": np.quantile(residuals, [0.1, 0.5, 0.9]).tolist(),
        "baseline_probability": float(train["up"].mean()),
        "baseline_quantiles": np.quantile(train["target_return"], [0.1, 0.5, 0.9]).tolist(),
        "calibration_status": "CALIBRATED"
        if calibrator is not None
        else "INSUFFICIENT_CLASS_COVERAGE",
    }


def infer(bundle, frame):
    """中位收益与经验校准残差区间来自各自算法，不伪称均值置信区间。"""
    raw_median = bundle["median_model"].predict(frame[FEATURES])
    low, middle, high = bundle["residual_quantiles"]
    probability = None
    if bundle["calibrator"] is not None:
        probability = bundle["calibrator"].predict_proba(
            logit(bundle["classifier"].predict_proba(frame[FEATURES])[:, 1])
        )[:, 1]
    return {
        "median": raw_median + middle,
        "lower": np.maximum(-0.999, raw_median + low),
        "upper": np.maximum(-0.999, raw_median + high),
        "probability": probability,
    }


def sample_metrics(actual, predictions, probability):
    """方向效果与收益误差、区间覆盖分开汇报；概率不可用时相关指标为空。"""
    y = actual["up"].to_numpy()
    ret = actual["target_return"].to_numpy()
    return {
        "brier": float(brier_score_loss(y, probability)) if probability is not None else None,
        "accuracy": float(accuracy_score(y, probability >= 0.5))
        if probability is not None
        else None,
        "mae_return": float(mean_absolute_error(ret, predictions["median"])),
        "interval_coverage": float(
            np.mean((ret >= predictions["lower"]) & (ret <= predictions["upper"]))
        ),
        "interval_width_return": float(np.mean(predictions["upper"] - predictions["lower"])),
        "positive_rate": float(y.mean()),
        "zero_return_mae": float(np.mean(np.abs(ret))),
    }


def evaluate_fold(labeled, fit_index, end_index, fold, algorithm="lightgbm"):
    """每折独立拟合，测试样本从拟合日开始，最终测试绝不参与选型或校准。"""
    train, calibration = temporal_split(labeled, fit_index)
    test = labeled[(labeled["session_index"] >= fit_index) & (labeled["session_index"] < end_index)]
    if test.empty:
        raise ValueError("黄金测试段为空")
    bundle = fit_bundle(train, calibration, algorithm)
    prediction = infer(bundle, test)
    bq = bundle["baseline_quantiles"]
    baseline = {
        "lower": np.full(len(test), bq[0]),
        "median": np.full(len(test), bq[1]),
        "upper": np.full(len(test), bq[2]),
    }
    bp = np.full(len(test), bundle["baseline_probability"])
    candidate_metrics = sample_metrics(test, prediction, prediction["probability"])
    baseline_metrics = sample_metrics(test, baseline, bp)
    horizon = int(test["target_index"].iloc[0] - test["session_index"].iloc[0])
    selected = np.arange(0, len(test), horizon)
    nonoverlap_predictions = {
        key: value[selected] for key, value in prediction.items() if value is not None
    }
    nonoverlap = sample_metrics(
        test.iloc[selected],
        nonoverlap_predictions,
        prediction["probability"][selected] if prediction["probability"] is not None else None,
    )
    nonoverlap_baseline = sample_metrics(
        test.iloc[selected], {key: value[selected] for key, value in baseline.items()}, bp[selected]
    )
    detail = {
        "algorithm": algorithm,
        "fold": fold,
        "train_start": str(train["date"].iloc[0].date()),
        "train_end": str(train["date"].iloc[-1].date()),
        "train_label_end": str(train["target_date"].max().date()),
        "calibration_start": str(calibration["date"].iloc[0].date()),
        "calibration_end": str(calibration["date"].iloc[-1].date()),
        "calibration_label_end": str(calibration["target_date"].max().date()),
        "test_start": str(test["date"].iloc[0].date()),
        "test_end": str(test["date"].iloc[-1].date()),
        "test_label_end": str(test["target_date"].max().date()),
        "train_samples": len(train),
        "calibration_samples": len(calibration),
        "test_samples": len(test),
        "label_maturity_cutoff": str(test["date"].iloc[0].date()),
        "candidate": candidate_metrics,
        "baseline": baseline_metrics,
        "calibration_status": bundle["calibration_status"],
        "nonoverlap": {
            "samples": len(selected),
            "candidate_brier": nonoverlap["brier"],
            "baseline_brier": nonoverlap_baseline["brier"],
            "candidate_accuracy": nonoverlap["accuracy"],
            "baseline_accuracy": nonoverlap_baseline["accuracy"],
            "interval_coverage": nonoverlap["interval_coverage"],
            "start_offset": 0,
            "stride_sessions": horizon,
            "warning": "非重叠起点固定；样本少，不能据此声称统计显著",
        },
    }
    rows = []
    for j, (_, row) in enumerate(test.iterrows()):
        rows.append(
            {
                "algorithm": algorithm,
                "reference_date": str(row["date"].date()),
                "target_date": str(row["target_date"].date()),
                "actual_return": float(row["target_return"]),
                "median_return": float(prediction["median"][j]),
                "probability_up": float(prediction["probability"][j])
                if prediction["probability"] is not None
                else None,
                "baseline_probability": bundle["baseline_probability"],
                "lower_return": float(prediction["lower"][j]),
                "upper_return": float(prediction["upper"][j]),
                "fold": fold,
                "nature": "HISTORICAL_REPLAY_CURRENT_VINTAGE",
            }
        )
    return detail, rows


def build_evaluation(frame, horizon, algorithm="lightgbm"):
    """预先固定末504成熟参考日留出；较短数据按固定比例缩小，绝不按结果挑区间。"""
    labeled = labeled_frame(frame, horizon)
    last_exclusive = int(labeled["session_index"].max()) + 1
    # 1200只是输入初门槛；不足产生至少两折时返回明确阻塞，不牺牲purge。
    final_size = 504 if len(frame) >= 2000 else 252
    final_start = last_exclusive - final_size
    rolling = []
    replay = []
    for i, start in enumerate(range(final_start - 3 * 252, final_start, 252)):
        try:
            detail, rows = evaluate_fold(labeled, start, start + 252, f"ROLLING_{i + 1}", algorithm)
        except ValueError:
            continue
        rolling.append(detail)
        replay.extend(rows)
    if len(rolling) < 2:
        raise ValueError("黄金成熟标签不足以完成两折滚动与独立最终测试")
    final, rows = evaluate_fold(labeled, final_start, last_exclusive, "FINAL_UNTOUCHED", algorithm)
    replay.extend(rows)
    years = (frame["date"].iloc[-1] - frame["date"].iloc[0]).days / 365.2425
    return {
        "algorithm": algorithm,
        "rolling": rolling,
        "final_test": final,
        "nominal_coverage": NOMINAL_COVERAGE,
        "coverage": {
            "start": str(frame["date"].iloc[0].date()),
            "end": str(frame["date"].iloc[-1].date()),
            "years": round(years, 3),
            "observations": len(frame),
            "matured_samples": len(labeled),
            "approx_independent_windows": len(labeled) // horizon,
        },
        "advantage_status": "EXPERIMENTAL_NO_STABLE_ADVANTAGE",
        "pit_status": "CURRENT_VINTAGE_REPLAY_NOT_STRICT_PIT",
        "baseline_name": "训练期常数上涨频率/多数类方向/收益分位数；另报告零收益基线",
        "warnings": [
            "高度重叠日标签不等于独立实验；非重叠最终样本仍很少",
            "历史为今日下载的官方当前修订，不具有逐日历史版本和首次发布时间证明",
            "无宏观/新闻历史因子，避免修订及发布时间污染；不报告因果效果",
            "未来区间来自校准残差分位数，时间相关和市场漂移会使实际覆盖偏离80%",
            "未进行阈值/窗口/权重优化；最终504日仅评估，不用于模型选型",
        ],
    }, replay


def model_directory():
    """复用现有工件目录，文件以前缀隔离且保持不可变。"""
    path = Path(os.environ.get("ETF_MODEL_DIR", ".runtime/models"))
    path.mkdir(parents=True, exist_ok=True)
    return path


def validate_input_times(observations, cutoff):
    """逐条核验获得/公开时刻和国内收盘边界，不能只检查序列最后一行。"""
    if cutoff.tzinfo is None:
        raise ValueError("黄金数据截止时间必须包含时区")
    for row in observations:
        if row.fetched_at > cutoff or (row.available_at is not None and row.available_at > cutoff):
            raise ValueError("黄金输入在拟合或生成时刻尚未可用")
        if row.observation_date > cutoff.astimezone(ZoneInfo("Asia/Shanghai")).date():
            raise ValueError("黄金输入观察日期来自未来")
        if row.series_id == BENCHMARK:
            closing = datetime.combine(
                row.observation_date, time(15, 30), ZoneInfo("Asia/Shanghai")
            )
            if closing > cutoff:
                raise ValueError("黄金参考日尚未完成上金所日盘收盘")


def code_fingerprint():
    """除了Git版本还记录实际黄金源码摘要及依赖，开发工件也能准确追溯。"""
    folder = Path(__file__).parent
    digests = {
        name: hashlib.sha256((folder / name).read_bytes()).hexdigest()
        for name in ("gold_data.py", "gold_models.py")
    }
    return {
        "files": digests,
        "sha256": canonical_hash(digests),
        "dependencies": {
            name: importlib.metadata.version(name)
            for name in ("numpy", "pandas", "scikit-learn", "lightgbm", "joblib")
        }
        | {"xgboost": xgb.__version__},
    }


def write_immutable(path, data):
    """相同内容可重试，已有不同内容禁止覆盖。"""
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError("黄金不可变工件已存在且内容不一致")
        return
    with path.open("xb") as handle:
        handle.write(data)


def freeze_bundle(bundle, algorithm):
    """XGB以原生JSON保存两套估计器，避免跨系统依赖Python估计器内存快照。"""
    if algorithm == "lightgbm":
        return bundle
    frozen = dict(bundle)
    for name in ("median_model", "classifier"):
        model = frozen.pop(name)
        frozen[name + "_json"] = (
            bytes(model.get_booster().save_raw(raw_format="json")) if model is not None else None
        )
    return frozen


def restore_bundle(frozen, algorithm):
    """保留旧LGBM工件格式，XGB从原生JSON恢复CPU估计器后再执行独立校准。"""
    if algorithm == "lightgbm":
        return frozen
    bundle = dict(frozen)
    for name, estimator in (("median_model", xgb.XGBRegressor), ("classifier", xgb.XGBClassifier)):
        data = bundle.pop(name + "_json")
        model = estimator(n_jobs=1, device="cpu") if data is not None else None
        if model is not None:
            model.load_model(bytearray(data))
        bundle[name] = model
    return bundle


def train_gold(request: GoldTrainRequest):
    """先完成独立样本外评估，再以当前已成熟数据拟合上线工件；保存不同角色边界。"""
    fit_at = request.fit_at or datetime.now(UTC)
    if fit_at.tzinfo is None or fit_at > datetime.now(UTC):
        raise ValueError("黄金拟合时刻必须为非未来的含时区时间")
    validate_input_times(request.observations, fit_at)
    frame = benchmark_frame(request.observations)
    if frame.empty or len(frame) < MINIMUM_ROWS:
        return {
            "status": "BLOCKED",
            "reason": "至少需要1200条经核验国内收盘，且须通过成熟标签分割门槛",
            "models": [],
        }
    if frame["date"].iloc[-1].date() > fit_at.date():
        raise ValueError("黄金训练包含拟合之后的价格")
    # snapshot不含ETF/用户账户，所有输入及数据修订均以完整JSON压缩保存。
    snapshot = [
        row.model_dump(mode="json") for row in request.observations if row.series_id == BENCHMARK
    ]
    snapshot_hash = canonical_hash(snapshot)
    models = []
    with GOLD_LOCK, threadpool_limits(limits=1):
        for algorithm, horizon in product(request.algorithms, (63, 126)):
            try:
                metrics, replay = build_evaluation(frame, horizon, algorithm)
                labeled = labeled_frame(frame, horizon)
                train, calibration = temporal_split(labeled, len(frame))
            except ValueError as error:
                return {"status": "BLOCKED", "reason": str(error), "models": models}
            bundle = fit_bundle(train, calibration, algorithm)
            identity = canonical_hash(
                [
                    snapshot_hash,
                    algorithm,
                    horizon,
                    request.code_version,
                    fit_at.isoformat(),
                    TRAINING_VERSION,
                ]
            )[:24]
            model_id = f"gold-au9999-h{horizon}-{identity}"
            metadata = {
                "model_id": model_id,
                "horizon": horizon,
                "business_domain": "GOLD_RESEARCH",
                "benchmark": BENCHMARK,
                "unit": "CNY/g",
                "price_field": "official_daily_close",
                "algorithm": ALGORITHM_NAMES[algorithm],
                "feature_version": FEATURE_VERSION,
                "features": FEATURES,
                "label_version": LABEL_VERSION,
                "calibration_version": CALIBRATION_VERSION,
                "training_version": TRAINING_VERSION,
                "fit_at": fit_at.isoformat(),
                "training_cutoff": str(frame["date"].iloc[-1].date()),
                "train_reference_end": str(train["date"].iloc[-1].date()),
                "train_label_end": str(train["target_date"].max().date()),
                "calibration_reference_start": str(calibration["date"].iloc[0].date()),
                "calibration_label_end": str(calibration["target_date"].max().date()),
                "code_version": request.code_version,
                "input_sha256": snapshot_hash,
                "input_snapshot": model_id + ".data.json.gz",
                "replay_file": model_id + ".replay.json.gz",
                "calibration_status": bundle["calibration_status"],
                "model_role": "LIVE_PRODUCTION_AFTER_UNTOUCHED_EVALUATION",
                "pit_status": "CURRENT_VINTAGE_REPLAY_NOT_STRICT_PIT",
                "source_code": code_fingerprint(),
                "calendar": calendar_quality(snapshot),
                "probability_validation_status": "FINAL_HOLDOUT_UNAVAILABLE"
                if metrics["final_test"]["candidate"]["brier"] is None
                else "EXPERIMENTAL_HOLDOUT_MEASURED",
                "ohlc_inconsistent_rows": sum(
                    r["details"].get("ohlc_consistency") == "INCONSISTENT_SOURCE_ROW"
                    for r in snapshot
                ),
            }
            directory = model_directory()
            artifact = model_id + ".joblib"
            path = directory / artifact
            if path.exists():
                raise ValueError("黄金模型身份已存在；请复用已有模型，不能重复训练覆盖")
            joblib.dump({"bundle": freeze_bundle(bundle, algorithm), "metadata": metadata}, path)
            metadata["artifact_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            record = {
                "model_id": model_id,
                "algorithm": algorithm,
                "horizon": horizon,
                "artifact": artifact,
                "metadata": metadata,
                "metrics": metrics,
            }
            write_immutable(
                directory / (model_id + ".json"),
                json.dumps(record, ensure_ascii=False, indent=2).encode(),
            )
            write_immutable(
                directory / metadata["input_snapshot"],
                gzip.compress(json.dumps(snapshot, ensure_ascii=False).encode(), mtime=0),
            )
            write_immutable(
                directory / metadata["replay_file"],
                gzip.compress(json.dumps(replay, ensure_ascii=False).encode(), mtime=0),
            )
            models.append(record)
    return {"status": "SUCCEEDED", "models": models}


def money(value):
    """预测收益映射价格时使用Decimal和统一分位舍入。"""
    return str(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def model_algorithm(record):
    """新记录核对显式算法与训练元数据，旧LightGBM工件沿用原元数据识别。"""
    declared = record.get("algorithm")
    trained = record.get("metadata", {}).get("algorithm")
    inferred = next((key for key, name in ALGORITHM_NAMES.items() if name == trained), None)
    algorithm = declared or inferred
    if algorithm not in ALGORITHM_NAMES or (trained is not None and inferred != algorithm):
        raise ValueError("黄金模型算法身份不一致或未知")
    return algorithm


def predict_gold(request: GoldPredictRequest):
    """正式预测只在数据/工件门槛通过时产生；旧数据不重新标成今日预测。"""
    now = request.generated_at or datetime.now(UTC)
    if now.tzinfo is None or now > datetime.now(UTC):
        raise ValueError("黄金生成时刻必须为非未来的含时区时间")
    validate_input_times(request.observations, now)
    frame = benchmark_frame(request.observations)
    if frame.empty or len(frame) < 127:
        return {"status": "BLOCKED", "reason": "黄金有效收盘或特征历史不足", "predictions": []}
    last = frame.iloc[-1]
    reference_date = last["date"].date()
    age = (now.astimezone(ZoneInfo("Asia/Shanghai")).date() - reference_date).days
    if age < 0 or age > 10:
        return {
            "status": "BLOCKED",
            "reason": "黄金基准过期或来自未来，保留上次冻结结果",
            "predictions": [],
        }
    latest_fetch = datetime.fromisoformat(last["fetched_at"])
    if latest_fetch > now:
        return {"status": "BLOCKED", "reason": "输入在生成时刻尚未被系统获得", "predictions": []}
    records = []
    for record in request.models:
        model_id = str(record.get("model_id", ""))
        if not __import__("re").fullmatch(r"gold-au9999-h(?:63|126)-[a-f0-9]{24}", model_id):
            raise ValueError("黄金工件身份无效")
        directory = model_directory()
        manifest = json.loads((directory / (model_id + ".json")).read_text())
        metadata = manifest["metadata"]
        algorithm = model_algorithm(manifest)
        if (
            manifest["model_id"] != model_id
            or manifest["artifact"] != model_id + ".joblib"
            or metadata["model_id"] != model_id
            or manifest["horizon"] != metadata["horizon"]
            or f"-h{metadata['horizon']}-" not in model_id
        ):
            raise ValueError("黄金工件身份不一致")
        if (record.get("algorithm") or record.get("metadata", {}).get("algorithm")) and (
            model_algorithm(record) != algorithm
        ):
            raise ValueError("黄金请求算法与工件不一致")
        path = directory / manifest["artifact"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != metadata["artifact_sha256"]:
            raise ValueError("黄金工件摘要不一致")
        artifact = joblib.load(path)
        # JSON登记必须与冻结在工件内的身份一致；不能靠修改算法标签把LGBM冒充XGB。
        if artifact["metadata"] != {
            key: value for key, value in metadata.items() if key != "artifact_sha256"
        }:
            raise ValueError("黄金工件元数据不一致")
        bundle = restore_bundle(artifact["bundle"], algorithm)
        regressor_type, classifier_type = (
            (LGBMRegressor, LGBMClassifier)
            if algorithm == "lightgbm"
            else (xgb.XGBRegressor, xgb.XGBClassifier)
        )
        if (
            bundle.get("algorithm", algorithm) != algorithm
            or not isinstance(bundle["median_model"], regressor_type)
            or (
                bundle["classifier"] is not None
                and not isinstance(bundle["classifier"], classifier_type)
            )
        ):
            raise ValueError("黄金工件估计器与算法不一致")
        if datetime.fromisoformat(metadata["fit_at"]) > now or metadata["training_cutoff"] > str(
            reference_date
        ):
            raise ValueError("黄金模型训练时点晚于预测数据")
        with threadpool_limits(limits=1):
            prediction = infer(bundle, frame.iloc[[-1]])
        median = float(prediction["median"][0])
        lower, upper = [float(prediction[key][0]) for key in ("lower", "upper")]
        reference = Decimal(str(last["value"]))
        horizon = int(metadata["horizon"])
        feature_snapshot = {name: float(last[name]) for name in FEATURES}
        records.append(
            {
                "algorithm": algorithm,
                "horizon": horizon,
                "reference_date": str(reference_date),
                "reference_price": str(reference),
                "data_cutoff": now.isoformat(),
                "generated_at": now.isoformat(),
                "reference_close_cutoff": str(reference_date) + "T15:30:00+08:00",
                "target_date": str(estimated_target(reference_date, horizon)),
                "target_date_status": "ESTIMATED_WEEKDAYS_NOT_CONFIRMED_SGE",
                "calendar_version": "SGE_OBSERVED_SESSIONS_V1_FUTURE_ESTIMATED",
                "median_return": median,
                "median_price": money(reference * (1 + Decimal(str(median)))),
                "probability_up": float(prediction["probability"][0])
                if prediction["probability"] is not None
                else None,
                "interval_lower": money(reference * (1 + Decimal(str(lower)))),
                "interval_upper": money(reference * (1 + Decimal(str(upper)))),
                "nominal_coverage": NOMINAL_COVERAGE,
                "model_id": model_id,
                "label_version": LABEL_VERSION,
                "feature_version": FEATURE_VERSION,
                "calibration_version": CALIBRATION_VERSION,
                "code_version": request.code_version,
                "training_cutoff": metadata["training_cutoff"],
                "fit_at": metadata["fit_at"],
                "artifact_sha256": metadata["artifact_sha256"],
                "evaluation_status": "PENDING_MATURITY",
                "quality": {
                    "status": "EXPERIMENTAL_NO_STABLE_ADVANTAGE",
                    "data_age_days": age,
                    "pit_status": "LIVE_SNAPSHOT_WITH_CURRENT_VINTAGE_HISTORY",
                    "ohlc_inconsistent_rows": metadata["ohlc_inconsistent_rows"],
                    "calibration_status": metadata["calibration_status"],
                },
                "input_snapshot": {
                    "features": feature_snapshot,
                    "reference_observation": last["revision"],
                    "observations_sha256": canonical_hash(
                        [
                            r.model_dump(mode="json")
                            for r in request.observations
                            if r.series_id == BENCHMARK
                        ]
                    ),
                    "training_snapshot": metadata["input_snapshot"],
                    "feature_observation_revisions": frame.tail(127)[
                        ["observation_date", "revision"]
                    ].to_dict(orient="records"),
                    "observations": frame.tail(127)[
                        [
                            "observation_date",
                            "value",
                            "revision",
                            "source",
                            "source_url",
                            "available_at",
                            "fetched_at",
                            "quality",
                        ]
                    ].to_dict(orient="records"),
                },
                "algorithm_sources": {
                    "median_return": (
                        f"固定小型{'LightGBM' if algorithm == 'lightgbm' else 'XGBoost'}"
                        "中位数回归+独立校准残差中位数"
                    ),
                    "probability_up": "独立时间段Platt校准的P(基准收益>0)，不是实物扣费后盈利概率",
                    "interval": "独立校准残差10%/90%分位数，名义80%未来价格区间；非均值置信区间",
                },
                "probability_validation_status": metadata.get(
                    "probability_validation_status", "UNVERIFIED"
                ),
                "source_code": metadata.get("source_code"),
                "validation_warning": "校准器能拟合不等于概率有效；最终测试校准不可用时，当前概率缺对应最终效果证明。概率和中位数来自不同算法，并非同一预测分布；区间实际覆盖见历史验证。",
            }
        )
    return {
        "status": "SUCCEEDED" if records else "BLOCKED",
        "reason": None if records else "尚无黄金模型",
        "predictions": records,
    }
