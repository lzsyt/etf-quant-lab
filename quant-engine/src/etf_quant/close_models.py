"""ETF20/60交易日收盘价格变化研究；与旧分类、开盘协议及工件完全区分。"""

import hashlib
import importlib.metadata
import json
import os
from datetime import UTC, date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Literal

import joblib
import numpy as np
import pandas as pd
from lightgbm import LGBMRegressor
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field
from threadpoolctl import threadpool_limits
from xgboost import XGBRegressor

from etf_quant.features import FEATURES
from etf_quant.forward_models import _available_bars, _range, market_time, purge_before_time
from etf_quant.fund_models import evaluation_metrics, new_regressor
from etf_quant.models import MODEL_LOCK, prepare
from etf_quant.schemas import Bar

LABEL_VERSION = "CLOSE_TO_CLOSE_H20_H60_V1"
PROTOCOL_VERSION = "CLOSE_RETURN_RESEARCH_V1"
TRAINING_VERSION = "close-return-purged-v1"
TARGET_SEMANTICS = "SIGNAL_CLOSE_TO_FUTURE_CLOSE_PRICE_RETURN"
MODEL_ROLE = "CLOSE_RETURN_RESEARCH"


class CloseIdentity(BaseModel):
    """收盘幅度使用独立目标身份，不能加载旧1/5分类或H1开盘工件。"""

    model_config = ConfigDict(extra="forbid")
    symbol: Literal["510300", "510500", "159915"]
    algorithm: Literal["lightgbm", "xgboost"]
    horizon: Literal[20, 60]
    feature_version: Literal["ohlcv-v2"] = "ohlcv-v2"
    label_version: Literal["CLOSE_TO_CLOSE_H20_H60_V1"] = LABEL_VERSION
    protocol_version: Literal["CLOSE_RETURN_RESEARCH_V1"] = PROTOCOL_VERSION


class CloseTrainRequest(CloseIdentity):
    """历史训练只接收截止前已收盘并实际取得的行情，日历缺口保留缺值。"""

    seed: Literal[42] = 42
    bars: list[Bar] = Field(min_length=320, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(min_length=3, max_length=20000)
    training_cutoff_at: AwareDatetime
    code_version: str = Field(pattern=r"^[a-f0-9]{7,40}$")
    corporate_action_dates: list[date] = Field(default_factory=list)
    corporate_action_coverage: Literal["UNKNOWN", "VERIFIED"] = "UNKNOWN"


class ClosePredictRequest(CloseIdentity):
    """研究锚点是已知收盘价，不表示可以按该历史价格成交。"""

    model_id: str = Field(pattern=r"^[a-zA-Z0-9_-]+$")
    bars: list[Bar] = Field(min_length=61, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(min_length=3, max_length=20000)
    signal_date: date
    cutoff_at: AwareDatetime
    corporate_action_dates: list[date] = Field(default_factory=list)


def close_target_date(signal_date, horizon, trading_dates):
    """只用已确认交易日，不用自然日或工作日推算未公布的跨年目标。"""
    calendar = sorted(set(trading_dates))
    if signal_date not in calendar:
        raise ValueError("SIGNAL_DATE_NOT_IN_CONFIRMED_CALENDAR")
    index = calendar.index(signal_date) + horizon
    return calendar[index] if index < len(calendar) else None


def labeled_close_frame(request: CloseTrainRequest):
    """标签为当日收盘到第h个已确认交易日收盘；缺中间行情不会压缩期限。"""
    if request.training_cutoff_at > datetime.now(UTC):
        raise ValueError("TRAINING_CUTOFF_CANNOT_BE_IN_FUTURE")
    if any(bar.symbol != request.symbol for bar in request.bars):
        raise ValueError("TRAINING_SYMBOL_MISMATCH")
    if any(bar.price_basis != "RAW" for bar in request.bars + request.benchmark):
        raise ValueError("CLOSE_RETURN_REQUIRES_RAW_PRICE")
    if len({bar.nature for bar in request.bars}) != 1:
        raise ValueError("MIXED_TRAINING_DATA_NATURE")
    bars = _available_bars(request.bars, request.training_cutoff_at)
    benchmark = _available_bars(request.benchmark, request.training_cutoff_at)
    if len(bars) < 320:
        raise ValueError("TRAINING_AVAILABLE_BARS_INSUFFICIENT")
    if request.benchmark and not benchmark:
        raise ValueError("BENCHMARK_NOT_AVAILABLE_AT_TRAINING_CUTOFF")
    frame = prepare(bars, benchmark, request.trading_dates)
    frame["target_date"] = pd.Series(frame.index, index=frame.index).shift(-request.horizon)
    prices = {bar.trade_date: bar.close for bar in bars}
    frame["actual_return"] = [
        float(prices[end.date()] / prices[anchor.date()] - Decimal(1))
        if pd.notna(end) and anchor.date() in prices and end.date() in prices
        else np.nan
        for anchor, end in zip(frame.index, frame["target_date"], strict=True)
    ]
    frame["feature_at"] = pd.to_datetime(
        [market_time(day.date(), time(15)) for day in frame.index], utc=True
    )
    frame["label_end_at"] = pd.to_datetime(
        [
            market_time(day.date(), time(15)) if pd.notna(day) else pd.NaT
            for day in frame["target_date"]
        ],
        utc=True,
    )
    fetched = pd.Series({pd.Timestamp(bar.trade_date): bar.fetched_at for bar in bars}).reindex(
        frame.index
    )
    frame["label_available_at"] = pd.to_datetime(
        [
            max(values) if all(pd.notna(value) for value in values) else pd.NaT
            for values in zip(
                fetched, fetched.shift(-request.horizon), frame["label_end_at"], strict=True
            )
        ],
        utc=True,
    )
    action_mask = pd.Series(False, index=frame.index)
    for action in request.corporate_action_dates:
        for offset, end in enumerate(frame["target_date"]):
            if pd.notna(end) and frame.index[max(0, offset - 60)].date() <= action <= end.date():
                action_mask.iloc[offset] = True
    # 连续交易日窗口内任一天缺价即排除该标签，避免将稀疏行情伪装为完整20/60日历史。
    full_window = (
        frame["close"].notna().rolling(request.horizon + 1).sum().shift(-request.horizon)
        == request.horizon + 1
    )
    complete = frame[FEATURES + ["actual_return", "target_date"]].notna().all(axis=1) & full_window
    matured = (frame["label_end_at"] <= pd.Timestamp(request.training_cutoff_at)) & (
        frame["label_available_at"] <= pd.Timestamp(request.training_cutoff_at)
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


def close_split(sample):
    """60/20/20时间切分后按真实标签结束清除边界，不让长窗口进入下一段。"""
    if len(sample) < 400:
        raise ValueError("INSUFFICIENT_MATURED_CLOSE_SAMPLES")
    first, second = int(len(sample) * 0.6), int(len(sample) * 0.8)
    train = purge_before_time(sample.iloc[:first], sample.feature_at.iloc[first])
    validation = purge_before_time(sample.iloc[first:second], sample.feature_at.iloc[second])
    test = sample.iloc[second:]
    if min(len(train), len(validation), len(test)) < 40:
        raise ValueError("INSUFFICIENT_PURGED_CLOSE_SAMPLES")
    return train, validation, test


def close_artifact_root():
    """独立目录防止close工件与历史分类/开盘工件串用。"""
    return Path(os.environ.get("ETF_MODEL_DIR", ".runtime/models")).resolve() / "close-return"


def load_close_model(model_id):
    """同时核对受控路径、摘要、内外身份与真实回归器类型。"""
    if Path(model_id).name != model_id:
        raise ValueError("MODEL_PATH_DENIED")
    directory = close_artifact_root()
    path = directory / f"{model_id}.json"
    if not path.is_file():
        raise ValueError("MODEL_METADATA_MISSING")
    metadata = json.loads(path.read_text(encoding="utf-8"))
    name = f"{model_id}.joblib"
    artifact_path = directory / name
    hashes = metadata.get("artifact_sha256", {})
    if (
        metadata.get("id") != model_id
        or set(hashes) != {name}
        or not artifact_path.is_file()
        or hashlib.sha256(artifact_path.read_bytes()).hexdigest() != hashes[name]
    ):
        raise ValueError("MODEL_ARTIFACT_MISSING_OR_DIGEST_MISMATCH")
    payload = joblib.load(artifact_path)
    embedded = payload["metadata"]
    for key in (
        *CloseIdentity.model_fields,
        "id",
        "target_semantics",
        "prediction_model_role",
        "nature",
        "price_basis",
        "available_at",
        "features",
        "benchmark_symbol",
    ):
        if embedded.get(key) != metadata.get(key):
            raise ValueError("MODEL_IDENTITY_MISMATCH_" + key.upper())
    if embedded != {key: value for key, value in metadata.items() if key != "artifact_sha256"}:
        # 摘要保护模型字节；完整内外元数据比较同时保护新鲜度、训练规则和历史评价身份。
        raise ValueError("MODEL_METADATA_MISMATCH")
    if (
        metadata.get("label_version") != LABEL_VERSION
        or metadata.get("protocol_version") != PROTOCOL_VERSION
        or metadata.get("target_semantics") != TARGET_SEMANTICS
        or metadata.get("prediction_model_role") != MODEL_ROLE
        or metadata.get("features") != FEATURES
    ):
        raise ValueError("MODEL_TARGET_IDENTITY_MISMATCH")
    if metadata["algorithm"] == "xgboost":
        if "model" in payload or not isinstance(payload.get("model_json"), bytes):
            raise ValueError("MODEL_ALGORITHM_MISMATCH")
        model = XGBRegressor(n_jobs=1, device="cpu")
        model.load_model(bytearray(payload["model_json"]))
        if model.objective != "reg:squarederror" or model.get_booster().feature_names != FEATURES:
            raise ValueError("MODEL_ALGORITHM_MISMATCH")
    elif metadata["algorithm"] == "lightgbm":
        model = payload.get("model")
        if not isinstance(model, LGBMRegressor) or "model_json" in payload:
            raise ValueError("MODEL_ALGORITHM_MISMATCH")
    else:
        raise ValueError("MODEL_ALGORITHM_MISMATCH")
    return metadata, model


def train_close_model(request: CloseTrainRequest):
    """固定回归器只拟合训练段，验证判定基线优势，最终测试独立留存。"""
    with MODEL_LOCK, threadpool_limits(limits=1):
        frame, sample, evidence = labeled_close_frame(request)
        train, validation, test = close_split(sample)
        dependencies = {
            name: importlib.metadata.version(name)
            for name in ("numpy", "pandas", "scikit-learn", "lightgbm", "xgboost-cpu")
        }
        source_digests = {
            name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
            for name in (
                "close_models.py",
                "features.py",
                "fund_models.py",
                "forward_models.py",
                "models.py",
            )
        }
        identity = hashlib.sha256(
            json.dumps(
                {
                    "request": request.model_dump(mode="json"),
                    "dependencies": dependencies,
                    "sources": source_digests,
                    "training_version": TRAINING_VERSION,
                },
                sort_keys=True,
            ).encode()
        ).hexdigest()
        model_id = f"{request.symbol}-{request.algorithm}-close-h{request.horizon}-{identity[:16]}"
        directory = close_artifact_root()
        directory.mkdir(parents=True, exist_ok=True)
        metadata_path = directory / f"{model_id}.json"
        if metadata_path.exists():
            return load_close_model(model_id)[0]
        model = new_regressor(request.algorithm)
        model.fit(train[FEATURES], train["actual_return"])
        mean = float(train["actual_return"].mean())
        validation_metrics = evaluation_metrics(
            validation["actual_return"], model.predict(validation[FEATURES]), mean, request.horizon
        )
        selected = float(validation_metrics["mae"]) < min(
            float(validation_metrics["zero_return_mae"]),
            float(validation_metrics["training_mean_mae"]),
        )
        test_predictions = model.predict(test[FEATURES])
        final_metrics = evaluation_metrics(
            test["actual_return"], test_predictions, mean, request.horizon
        )
        now = datetime.now(UTC).isoformat()
        limits = [
            "HISTORICAL_DATA_REVISIONS_AND_POINT_IN_TIME_AVAILABILITY_UNVERIFIED",
            "PRICE_RETURN_NOT_TOTAL_RETURN_OR_EXECUTABLE_PROFIT",
            "NO_FORWARD_EVIDENCE",
        ]
        if request.corporate_action_coverage != "VERIFIED":
            limits.append("CORPORATE_ACTION_COVERAGE_UNVERIFIED")
        if not selected:
            limits.append("VALIDATION_BASELINES_NOT_BEATEN")
        parameters = {
            "estimator": {
                key: str(value) if isinstance(value, float) and not np.isfinite(value) else value
                for key, value in model.get_params().items()
            },
            "evaluation_split": [0.6, 0.2, 0.2],
            "selection_policy": "VALIDATION_MAE_BEATS_ZERO_AND_TRAIN_MEAN_V1",
            "training_version": TRAINING_VERSION,
        }
        metadata = {
            **request.model_dump(include=set(CloseIdentity.model_fields)),
            "id": model_id,
            "target_semantics": TARGET_SEMANTICS,
            "features": FEATURES,
            "capability": "RETURN_POINT",
            "status": "TRAINED",
            "nature": request.bars[0].nature,
            "price_basis": "RAW",
            "benchmark_symbol": request.benchmark[0].symbol if request.benchmark else None,
            "training_version": TRAINING_VERSION,
            "prediction_model_role": MODEL_ROLE,
            "model_role": MODEL_ROLE,
            "data_cutoff": str(frame.index[-1].date()),
            "training_cutoff_at": request.training_cutoff_at.isoformat(),
            "label_end_cutoff_at": sample.label_end_at.max().isoformat(),
            "label_available_cutoff_at": sample.label_available_at.max().isoformat(),
            "fit_data_cutoff": str(train.target_date.max().date()),
            "training_range": {
                "train": _range(train),
                "validation": _range(validation),
                "test": _range(test),
            },
            "validation_status": "RESEARCH_ONLY" if selected else "FAILED",
            "candidate_selected_on_validation": selected,
            "selection_policy": "VALIDATION_MAE_BEATS_ZERO_AND_TRAIN_MEAN_V1",
            "hyperparameters": parameters,
            "model_parameter_version": hashlib.sha256(
                json.dumps(parameters, sort_keys=True).encode()
            ).hexdigest()[:16],
            "metrics": {
                "validation": validation_metrics,
                "test": final_metrics,
                "selection_frozen_before_test": True,
            },
            "oos_predictions": [
                {
                    "signal_date": str(day.date()),
                    "target_date": str(row.target_date.date()),
                    "point_return": str(float(value)),
                    "actual_return": str(row.actual_return),
                    "evaluation_type": "HISTORICAL_OOS",
                }
                for (day, row), value in zip(test.iterrows(), test_predictions, strict=True)
            ],
            "sample_evidence": evidence,
            "code_version": request.code_version,
            "source_code_sha256": source_digests,
            "dependencies": dependencies,
            "data_sha256": identity,
            "created_at": now,
            "available_at": now,
            "corporate_action_dates": [str(day) for day in request.corporate_action_dates],
            "corporate_action_coverage": request.corporate_action_coverage,
            "limitations": limits,
            "evaluation_type": "HISTORICAL_OOS",
            "global_feature_importance": dict(
                zip(FEATURES, map(float, model.feature_importances_), strict=True)
            ),
        }
        artifact = {"metadata": metadata}
        if request.algorithm == "xgboost":
            artifact["model_json"] = bytes(model.get_booster().save_raw(raw_format="json"))
        else:
            artifact["model"] = model
        artifact_path = directory / f"{model_id}.joblib"
        with artifact_path.open("xb") as handle:
            joblib.dump(artifact, handle)
        metadata["artifact_sha256"] = {
            artifact_path.name: hashlib.sha256(artifact_path.read_bytes()).hexdigest()
        }
        metadata_path.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8"
        )
        return metadata


def predict_close(request: ClosePredictRequest):
    """当前已知收盘价为研究锚点；未来日历未公布时只冻结期限，不伪造终点。"""
    metadata, model = load_close_model(request.model_id)
    for key in CloseIdentity.model_fields:
        if metadata.get(key) != getattr(request, key):
            raise ValueError("MODEL_IDENTITY_MISMATCH_" + key.upper())
    generated_at = datetime.now(UTC)
    if request.cutoff_at > generated_at:
        raise ValueError("PREDICTION_CUTOFF_CANNOT_BE_IN_FUTURE")
    if datetime.fromisoformat(metadata["available_at"]) > request.cutoff_at:
        raise ValueError("MODEL_NOT_AVAILABLE_AT_PREDICTION_CUTOFF")
    data_cutoff = date.fromisoformat(metadata["data_cutoff"])
    if request.signal_date < data_cutoff or (request.signal_date - data_cutoff).days > 45:
        raise ValueError("MODEL_DATA_CUTOFF_FUTURE_OR_STALE")
    target = close_target_date(request.signal_date, request.horizon, request.trading_dates)
    if target and market_time(target, time(15)) <= generated_at:
        raise ValueError("LATE_PREDICTION_TARGET_ALREADY_MATURED")
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
    if metadata["benchmark_symbol"] != (benchmark[0].symbol if benchmark else None):
        raise ValueError("PREDICTION_BENCHMARK_MISMATCH")
    frame = prepare(bars, benchmark, request.trading_dates)
    if frame.iloc[-1][FEATURES].isna().any():
        raise ValueError("LATEST_FEATURES_INCOMPLETE")
    actions = set(request.corporate_action_dates) | {
        date.fromisoformat(day) for day in metadata.get("corporate_action_dates", [])
    }
    if any(
        frame.index[max(0, len(frame) - 61)].date() <= action
        and (target is None or action <= target)
        for action in actions
    ):
        raise ValueError("CORPORATE_ACTION_AFFECTED_PREDICTION_QUARANTINED")
    with threadpool_limits(limits=1):
        value = float(model.predict(frame.iloc[-1:][FEATURES])[0])
    if not np.isfinite(value) or value < -1:
        raise ValueError("INVALID_MODEL_OUTPUT")
    frozen = request.model_dump(mode="json")
    for key in ("bars", "benchmark"):
        frozen[key] = sorted(frozen[key], key=lambda row: (row["symbol"], row["trade_date"]))
    frozen["trading_dates"] = sorted(set(frozen["trading_dates"]))
    frozen["corporate_action_dates"] = sorted(str(day) for day in actions)
    limitations = list(metadata["limitations"])
    if target is None:
        limitations.append("FUTURE_CALENDAR_UNAVAILABLE")
    anchor = next(bar for bar in bars if bar.trade_date == request.signal_date)
    return {
        **request.model_dump(include=set(CloseIdentity.model_fields)),
        "model_id": request.model_id,
        "target_semantics": TARGET_SEMANTICS,
        "model_role": MODEL_ROLE,
        "capability": "RETURN_POINT",
        "nature": metadata["nature"],
        "price_basis": "RAW",
        "signal_date": str(request.signal_date),
        "anchor_date": str(request.signal_date),
        "anchor_value": str(anchor.close),
        "target_date": str(target) if target else None,
        "evaluation_date": str(target) if target else None,
        "calendar_status": "CONFIRMED" if target else "UNCONFIRMED",
        "point_return": str(value),
        "probability": None,
        "probability_up": None,
        "generated_at": datetime.now(UTC).isoformat(),
        "input_cutoff_at": request.cutoff_at.isoformat(),
        "model_available_at": metadata["available_at"],
        "model_parameter_version": metadata["model_parameter_version"],
        "training_data_cutoff": metadata["label_available_cutoff_at"],
        "model_input_data_cutoff": metadata["data_cutoff"],
        "fit_data_cutoff": metadata["fit_data_cutoff"],
        "input_sha256": hashlib.sha256(json.dumps(frozen, sort_keys=True).encode()).hexdigest(),
        "feature_values": {name: float(frame.iloc[-1][name]) for name in FEATURES},
        "limitations": limitations,
        "reason_codes": limitations,
        "score_meaning": "当前收盘到未来20/60交易日收盘的RAW价格变化；不是可按锚点成交的收益",
    }
