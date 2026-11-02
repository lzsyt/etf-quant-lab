"""按时间训练、校准和评估；所有标签跨分割边界的样本均清除。"""

import gzip
import hashlib
import importlib.metadata
import json
import os
import threading
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Literal

import joblib
import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier
from pydantic import BaseModel, Field
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, log_loss, roc_auc_score
from sklearn.model_selection import TimeSeriesSplit
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from etf_quant.data import validate_final_bar
from etf_quant.features import FEATURE_VERSION, FEATURES, SUPPORTED_FEATURES, feature_frame
from etf_quant.schemas import Bar

MODEL_LOCK = threading.Lock()
TRAINING_VERSION = "purged-evaluation-plus-recent-production-v1.1"


class TrainRequest(BaseModel):
    symbol: str = Field(pattern=r"^\d{6}$")
    algorithm: Literal["lightgbm", "xgboost", "logistic"] = "lightgbm"
    horizon: Literal[1, 5] = 1
    seed: int = 42
    bars: list[Bar] = Field(min_length=320, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(default_factory=list)


class PredictRequest(BaseModel):
    model_id: str = Field(pattern=r"^[a-zA-Z0-9_-]+$")
    bars: list[Bar] = Field(min_length=61, max_length=12000)
    benchmark: list[Bar] = Field(default_factory=list, max_length=12000)
    trading_dates: list[date] = Field(default_factory=list)


def estimator(algorithm: str, seed: int):
    """单线程 CPU 模型和固定种子，避免4核服务器训练并发挤占业务。"""
    if algorithm == "lightgbm":
        return LGBMClassifier(
            n_estimators=100,
            max_depth=3,
            num_leaves=7,
            learning_rate=0.04,
            min_child_samples=25,
            n_jobs=1,
            random_state=seed,
            deterministic=True,
            force_col_wise=True,
            verbosity=-1,
        )
    if algorithm == "xgboost":
        return XGBClassifier(
            n_estimators=100,
            max_depth=3,
            learning_rate=0.04,
            n_jobs=1,
            random_state=seed,
            tree_method="hist",
            device="cpu",
            reg_lambda=2,
            eval_metric="logloss",
        )
    return make_pipeline(StandardScaler(), LogisticRegression(max_iter=1500, random_state=seed))


def logit(probability):
    """截断仅用于数值稳定，不能把情绪分数直接当成概率。"""
    p = np.clip(np.asarray(probability), 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p)).reshape(-1, 1)


def fit_calibrated(train, calibration, algorithm, seed):
    """模型和校准器使用不同时间段，校准标签也必须在下一分割前可见。"""
    if train["target"].nunique() < 2 or calibration["target"].nunique() < 2:
        raise ValueError("训练和校准时间段均需要包含涨跌两类样本")
    model = estimator(algorithm, seed)
    model.fit(train[FEATURES], train["target"])
    calibrator = LogisticRegression(random_state=seed, max_iter=500)
    calibrator.fit(logit(model.predict_proba(calibration[FEATURES])[:, 1]), calibration["target"])
    return model, calibrator


def probability(model, calibrator, frame, features=None):
    """输出在独立校准集上拟合的上涨概率。"""
    columns = features if features is not None else FEATURES
    return calibrator.predict_proba(logit(model.predict_proba(frame[columns])[:, 1]))[:, 1]


def metrics(labels, probabilities):
    """概率与方向指标独立报告；单类样本的 AUC 保持未定义。"""
    y, p = np.asarray(labels), np.asarray(probabilities)
    return {
        "samples": len(y),
        "accuracy": float(accuracy_score(y, p >= 0.5)),
        "brier_score": float(brier_score_loss(y, p)),
        "log_loss": float(log_loss(y, np.column_stack([1 - p, p]), labels=[0, 1])),
        "auc": float(roc_auc_score(y, p)) if len(np.unique(y)) == 2 else None,
        "positive_rate": float(y.mean()),
    }


def prepare(bars, benchmark=None, calendar=None):
    """价格口径和数据性质必须一致，禁止用演示样本填补真实数据缺口。"""
    if len({bar.nature for bar in bars}) != 1 or len({bar.price_basis for bar in bars}) != 1:
        raise ValueError("不能混用数据性质或价格口径")
    if len({bar.symbol for bar in bars}) != 1:
        raise ValueError("单模型训练只接受一个ETF的行情")
    if bars[0].nature == "REAL" and not calendar:
        raise ValueError("真实行情模型必须提供可信交易日历")
    calendar_set = set(calendar or [])
    if bars[0].nature == "REAL" and any(bar.trade_date not in calendar_set for bar in bars):
        raise ValueError("真实行情日期不在已核验交易日历内")
    for bar in list(bars) + list(benchmark or []):
        validate_final_bar(bar)
    if any("LARGE_PRICE_JUMP_REVIEW_REQUIRED" in bar.quality_flags for bar in bars):
        raise ValueError("行情异常跳变需要先核实分红或拆分")
    reference = None
    if benchmark:
        if (
            any(
                bar.nature != bars[0].nature or bar.price_basis != bars[0].price_basis
                for bar in benchmark
            )
            or len({bar.symbol for bar in benchmark}) != 1
        ):
            raise ValueError("基准行情口径不一致")
        reference = pd.Series(
            [float(bar.close) for bar in benchmark],
            index=pd.to_datetime([bar.trade_date for bar in benchmark]),
        )
    return feature_frame([bar.model_dump(mode="json") for bar in bars], reference, calendar)


def purge_before(frame, boundary):
    """删除标签结束日在下一段开始当日或之后的样本，防止1/5日标签跨界。"""
    return frame.loc[frame["target_date"] < boundary].copy()


def train_model(request: TrainRequest) -> dict:
    """固定时间切分 + 三折滚动验证，保存数据摘要、参数、依赖与模型工件。"""
    with MODEL_LOCK:
        if request.symbol != request.bars[0].symbol:
            raise ValueError("请求品种与行情不匹配")
        frame = prepare(request.bars, request.benchmark, request.trading_dates)
        future = frame["close"].shift(-request.horizon) / frame["close"] - 1
        frame["actual_return"] = future
        frame["target"] = np.where(future.notna(), (future > 0).astype(float), np.nan)
        frame["target_date"] = pd.Series(frame.index, index=frame.index).shift(-request.horizon)
        sample = frame.dropna(subset=FEATURES + ["target", "target_date"]).copy()
        if len(sample) < 250:
            raise ValueError("有效特征样本不足250条")
        n = len(sample)
        train_end, calibration_end, validation_end = int(n * 0.6), int(n * 0.7), int(n * 0.8)
        train = purge_before(sample.iloc[:train_end], sample.index[train_end])
        calibration = purge_before(
            sample.iloc[train_end:calibration_end], sample.index[calibration_end]
        )
        validation = purge_before(
            sample.iloc[calibration_end:validation_end], sample.index[validation_end]
        )
        test = sample.iloc[validation_end:]
        model, calibrator = fit_calibrated(train, calibration, request.algorithm, request.seed)
        test_probability = probability(model, calibrator, test)
        validation_probability = probability(model, calibrator, validation)
        folds = []
        for train_index, test_index in TimeSeriesSplit(n_splits=3, gap=request.horizon).split(
            sample
        ):
            history = sample.iloc[train_index]
            fold_test = sample.iloc[test_index]
            cut = int(len(history) * 0.8)
            fold_train = purge_before(history.iloc[:cut], history.index[cut])
            fold_calibration = purge_before(history.iloc[cut:], fold_test.index[0])
            fold_model, fold_calibrator = fit_calibrated(
                fold_train, fold_calibration, request.algorithm, request.seed
            )
            fold_metrics = metrics(
                fold_test["target"], probability(fold_model, fold_calibrator, fold_test)
            )
            folds.append(
                {
                    "train_end": str(fold_train.index[-1].date()),
                    "train_label_end": str(fold_train.target_date.max().date()),
                    "calibration_start": str(fold_calibration.index[0].date()),
                    "calibration_label_end": str(fold_calibration.target_date.max().date()),
                    "test_start": str(fold_test.index[0].date()),
                    "test_end": str(fold_test.index[-1].date()),
                    **fold_metrics,
                }
            )
        # 独立评估模型始终只fit训练段，历史OOS结果不受线上近期重训影响。
        # 线上模型固定采用最近504个已知标签样本，末20个交易日只供独立校准。
        # 20日单类时按预先规定增至40/60日，仍不足两类就保留失败而不伪造概率。
        recent = sample.iloc[-504:]
        production_train = production_calibration = None
        for calibration_size in (20, 40, 60):
            boundary = recent.index[-calibration_size]
            candidate_train = purge_before(recent.iloc[:-calibration_size], boundary)
            candidate_calibration = recent.iloc[-calibration_size:]
            if (
                candidate_train.target.nunique() == 2
                and candidate_calibration.target.nunique() == 2
            ):
                production_train, production_calibration = candidate_train, candidate_calibration
                break
        if production_train is None:
            raise ValueError("近期线上训练和校准缺少涨跌两类，不能生成生产模型")
        production_model, production_calibrator = fit_calibrated(
            production_train, production_calibration, request.algorithm, request.seed
        )
        snapshot = [
            {
                "date": str(bar.trade_date),
                "open": str(bar.open),
                "high": str(bar.high),
                "low": str(bar.low),
                "close": str(bar.close),
                "volume": str(bar.volume),
                "amount": str(bar.amount) if bar.amount is not None else None,
                "basis": bar.price_basis,
                "nature": bar.nature,
                "source": bar.source,
                "quality_flags": sorted(bar.quality_flags),
            }
            for bar in request.bars
        ]
        data_hash = hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()
        dependencies = {
            name: importlib.metadata.version(name)
            for name in ("numpy", "pandas", "scikit-learn", "lightgbm", "xgboost-cpu")
        }
        # get_params取得真正有效的参数；Pipeline中的对象改为类别名，禁止遗漏硬编码项。
        effective_parameters = {
            key: (str(value) if isinstance(value, float) and not np.isfinite(value) else value)
            if isinstance(value, (str, int, float, bool, type(None)))
            else type(value).__name__
            for key, value in production_model.get_params(deep=True).items()
        }
        parameter_config = {
            "estimator": effective_parameters,
            "calibrator": production_calibrator.get_params(),
            "training_version": TRAINING_VERSION,
            "production_window": 504,
            "production_calibration_candidates": [20, 40, 60],
            "evaluation_split": [0.6, 0.1, 0.1, 0.2],
            "dependencies": dependencies,
        }
        parameter_version = hashlib.sha256(
            json.dumps(parameter_config, sort_keys=True).encode()
        ).hexdigest()[:16]
        identity = hashlib.sha256(
            json.dumps(
                {
                    "data": data_hash,
                    "algorithm": request.algorithm,
                    "horizon": request.horizon,
                    "seed": request.seed,
                    "feature_version": FEATURE_VERSION,
                    "calendar": [str(day) for day in request.trading_dates],
                    "training_version": TRAINING_VERSION,
                    "model_parameter_version": parameter_version,
                    "benchmark": [
                        (str(bar.trade_date), str(bar.close)) for bar in request.benchmark
                    ],
                },
                sort_keys=True,
            ).encode()
        ).hexdigest()[:16]
        model_id = f"{request.symbol}-{request.algorithm}-h{request.horizon}-{identity}"
        partitions = {}
        for name, section in (
            ("train", train),
            ("calibration", calibration),
            ("validation", validation),
            ("test", test),
        ):
            partitions[name] = {
                "start": str(section.index[0].date()),
                "end": str(section.index[-1].date()),
                "last_label_date": str(section.target_date.max().date()),
                "samples": len(section),
            }
        production_partitions = {
            name: {
                "start": str(section.index[0].date()),
                "end": str(section.index[-1].date()),
                "last_label_date": str(section.target_date.max().date()),
                "samples": len(section),
            }
            for name, section in (
                ("fit", production_train),
                ("calibration", production_calibration),
            )
        }
        predictions = [
            {
                "signal_date": str(day.date()),
                "target_date": str(row.target_date.date()),
                "probability": float(p),
                "actual_return": float(row.actual_return),
                "actual_up": bool(row.target),
            }
            for (day, row), p in zip(test.iterrows(), test_probability, strict=True)
        ]
        metadata = {
            "id": model_id,
            "symbol": request.symbol,
            "algorithm": request.algorithm,
            "horizon": request.horizon,
            "feature_version": FEATURE_VERSION,
            "features": FEATURES,
            "benchmark_symbol": request.benchmark[0].symbol if request.benchmark else None,
            "nature": request.bars[0].nature,
            "price_basis": request.bars[0].price_basis,
            "data_sha256": data_hash,
            "data_cutoff": str(frame.index[-1].date()),
            "training_range": partitions,
            "production_training_range": production_partitions,
            "fit_data_cutoff": production_partitions["fit"]["last_label_date"],
            "calibration_data_cutoff": production_partitions["calibration"]["last_label_date"],
            "hyperparameters": parameter_config,
            "model_parameter_version": parameter_version,
            "training_version": TRAINING_VERSION,
            "prediction_model_role": "FORWARD_PRODUCTION",
            "historical_predictions_model_role": "PURGED_HELD_OUT_EVALUATION",
            "metrics": {
                "validation": metrics(validation.target, validation_probability),
                "test": metrics(test.target, test_probability),
                "walk_forward": folds,
            },
            "oos_predictions": predictions,
            "created_at": datetime.now(UTC).isoformat(),
            "dependencies": dependencies,
            "evaluation_type": "HISTORICAL_OOS",
            "purge_sessions": request.horizon,
        }
        directory = Path(os.environ.get("ETF_MODEL_DIR", ".runtime/models"))
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / (model_id + ".joblib")
        if not path.exists():
            temporary = path.with_suffix(".tmp")
            joblib.dump(
                {
                    "model": production_model,
                    "calibrator": production_calibrator,
                    "evaluation_model": model,
                    "evaluation_calibrator": calibrator,
                    "metadata": metadata,
                },
                temporary,
            )
            temporary.replace(path)
            path.with_suffix(".json").write_text(
                json.dumps(metadata, ensure_ascii=False, indent=2, allow_nan=False)
            )
        else:
            # 相同冻结数据和参数直接复用原工件的版本时间，不伪装成新训练。
            metadata = json.loads(path.with_suffix(".json").read_text())
        if request.algorithm == "xgboost":
            export_xgboost(path)
        snapshot_path = directory / (model_id + ".data.json.gz")
        if not snapshot_path.exists():
            with gzip.open(snapshot_path, "wt", encoding="utf-8") as snapshot_file:
                snapshot_file.write(request.model_dump_json())
        return metadata


def export_xgboost(path: Path):
    """将原训练环境的XGBoost内存快照导出为官方JSON模型，原工件保持不可变。"""
    portable = path.with_suffix(".portable.joblib")
    model_file = path.with_suffix(".xgb.json")
    if portable.exists() and model_file.exists():
        return
    artifact = joblib.load(path)
    if artifact["metadata"]["algorithm"] != "xgboost":
        raise ValueError("仅XGBoost需要转换为跨平台JSON模型")
    temporary_model = path.with_suffix(".xgb.tmp.json")
    artifact["model"].save_model(temporary_model)
    temporary_model.replace(model_file)
    temporary = portable.with_suffix(".tmp")
    joblib.dump({"calibrator": artifact["calibrator"], "metadata": artifact["metadata"]}, temporary)
    temporary.replace(portable)


def predict(request: PredictRequest) -> dict:
    """仅从受控模型目录读取工件，校验品种、口径、特征和时间边界。"""
    path = Path(os.environ.get("ETF_MODEL_DIR", ".runtime/models")) / (request.model_id + ".joblib")
    portable = path.with_suffix(".portable.joblib")
    if portable.is_file():
        artifact = joblib.load(portable)
        model = XGBClassifier(n_jobs=1, device="cpu")
        model.load_model(path.with_suffix(".xgb.json"))
        artifact["model"] = model
    elif path.is_file():
        artifact = joblib.load(path)
    else:
        raise ValueError("模型工件不存在")
    metadata = artifact["metadata"]
    frame = prepare(request.bars, request.benchmark, request.trading_dates)
    if metadata.get("benchmark_symbol") and (
        not request.benchmark or request.benchmark[0].symbol != metadata["benchmark_symbol"]
    ):
        raise ValueError("预测必须提供与训练一致的相对强弱基准")
    if request.bars[0].symbol != metadata["symbol"] or request.bars[0].nature != metadata["nature"]:
        raise ValueError("模型与行情品种或性质不匹配")
    if request.bars[0].price_basis != metadata["price_basis"]:
        raise ValueError("模型与行情价格口径不匹配")
    features = SUPPORTED_FEATURES.get(metadata["feature_version"])
    if features is None or frame.iloc[-1][features].isna().any():
        raise ValueError("特征版本失效或最新指标不完整")
    day = str(frame.index[-1].date())
    if day < metadata["data_cutoff"]:
        raise ValueError("在线模型不得回填早于训练数据截止日的预测")
    value = float(
        probability(artifact["model"], artifact["calibrator"], frame.iloc[[-1]], features)[0]
    )
    raw = float(artifact["model"].predict_proba(frame.iloc[[-1]][features])[0, 1])
    production = metadata.get("production_training_range", {})
    fit_cutoff = (
        metadata.get("fit_data_cutoff") or metadata["training_range"]["train"]["last_label_date"]
    )
    calibration_cutoff = (
        metadata.get("calibration_data_cutoff")
        or metadata["training_range"]["calibration"]["last_label_date"]
    )
    return {
        "model_id": request.model_id,
        "symbol": metadata["symbol"],
        "horizon": metadata["horizon"],
        "signal_date": day,
        "probability": value,
        "direction": "UP" if value >= 0.5 else "DOWN",
        "nature": metadata["nature"],
        "price_basis": metadata["price_basis"],
        "training_data_cutoff": max(fit_cutoff, calibration_cutoff),
        "model_input_data_cutoff": metadata["data_cutoff"],
        "fit_data_cutoff": fit_cutoff,
        "calibration_data_cutoff": calibration_cutoff,
        "model_parameter_version": metadata.get("model_parameter_version", "legacy-v1-incomplete"),
        "feature_version": metadata["feature_version"],
        "raw_score": raw,
        "score_meaning": "模型未校准的上涨类别输出，0至1；probability是独立历史段校准值，不是预期收益率",
        "generated_at": datetime.now(UTC).isoformat(),
        "model_role": "FORWARD_PRODUCTION" if production else "LEGACY_EVALUATION_MODEL",
    }
