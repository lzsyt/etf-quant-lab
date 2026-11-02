"""冻结生产基线的离线审计：旧引擎复现、引擎/仓位归因及一次预注册V2研究。"""

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
from collections import defaultdict
from copy import deepcopy
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from etf_quant.backtesting import BacktestRequest, run_backtest
from etf_quant.features import FEATURE_VERSION, FEATURES
from etf_quant.models import TRAINING_VERSION, TrainRequest, metrics, train_model


def write_json(path, value):
    """完整证据用严格JSON落盘，不输出巨型模型或任何运行凭据。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def source_module(root, revision, name, filename, output):
    """只读取得确切Git版本源码到独立证据目录，不切换或修改现有工作区。"""
    content = subprocess.check_output(
        ["git", "show", f"{revision}:quant-engine/src/etf_quant/{filename}"], cwd=root
    )
    path = output / f"{name}.py"
    path.write_bytes(content)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def signals(models, allocation):
    """使用冻结OOS概率，旧版固定20%和新版组合等权只改变仓位分配。"""
    daily = defaultdict(list)
    for model in models:
        for point in model["oos_predictions"]:
            daily[point["signal_date"]].append((model["symbol"], point["probability"] >= 0.55))
    result = []
    for day, group in sorted(daily.items()):
        winners = sum(eligible for _, eligible in group)
        weight = min(Decimal(".20"), Decimal(".40") / winners) if winners else Decimal(0)
        for symbol, eligible in group:
            result.append(
                {
                    "signal_date": day,
                    "symbol": symbol,
                    "target_weight": str(
                        (weight if allocation == "unified" else Decimal(".20"))
                        if eligible
                        else Decimal(0)
                    ),
                }
            )
    return result


def payload(configuration, strategy, model_map, allocation):
    """沿用线上冻结的行情、风险、日历和公司行为，不使用事后新行情覆盖旧输入。"""
    request = {
        key: value for key, value in configuration.items() if key in BacktestRequest.model_fields
    }
    request["strategy"] = strategy
    if strategy in model_map:
        request["signals"] = signals(model_map[strategy], allocation)
    return request


def compare_same_windows(baseline_path, output):
    """新旧模型仅在共同OOS日期比较，保留特征造成有效样本/起始窗口变化的事实。"""
    baseline = json.loads(baseline_path.read_text())["data"]
    configuration = baseline["backtest_experiment"][0]["configuration"]
    old_models = {
        (item["symbol"], item["algorithm"], item["horizon"]): item["metadata"]
        for item in baseline["model_version"]
    }
    new_models = json.loads((output / "models-v11.json").read_text())
    paired = []
    for new in new_models:
        old = old_models[(new["symbol"], new["algorithm"], new["horizon"])]
        old_rows = {row["signal_date"]: row for row in old["oos_predictions"]}
        pairs = [
            (old_rows[row["signal_date"]], row)
            for row in new["oos_predictions"]
            if row["signal_date"] in old_rows
        ]
        assert all(
            left["target_date"] == right["target_date"] and left["actual_up"] == right["actual_up"]
            for left, right in pairs
        )
        labels = [right["actual_up"] for _, right in pairs]
        paired.append(
            {
                "symbol": new["symbol"],
                "algorithm": new["algorithm"],
                "horizon": new["horizon"],
                "old_model": old["id"],
                "new_model": new["id"],
                "first_signal_date": pairs[0][1]["signal_date"],
                "last_signal_date": pairs[-1][1]["signal_date"],
                "old": metrics(labels, [left["probability"] for left, _ in pairs]),
                "new": metrics(labels, [right["probability"] for _, right in pairs]),
            }
        )
    windows = []
    for item in json.loads((output / "new-model-summary.json").read_text()):
        config = deepcopy(configuration)
        config.update(start=item["start"], end=item["end"])
        result = {"start": item["start"], "end": item["end"], "models": {}}
        for algorithm in ("lightgbm", "xgboost"):
            old = run_backtest(
                BacktestRequest(**payload(config, algorithm, config["models"], "unified"))
            )
            new_metrics = item["strategies"][algorithm]
            result["models"][algorithm] = {
                "old": old["metrics"],
                "new": new_metrics,
                "return_difference": new_metrics["total_return"] - old["metrics"]["total_return"],
                "comparison": "同窗口、同风险、同费用、新引擎和统一仓位；只比较旧/新预设模型方案",
            }
        windows.append(result)
    evidence = {
        "paired_probability_metrics": paired,
        "same_window_trading": windows,
        "statement": "有效样本变化会改变分段边界；该比较是完整预设特征方案差异，不声称单特征因果收益。",
    }
    write_json(output / "same-window-model-comparison.json", evidence)
    return evidence


def main():
    """每组固定规则各运行一次；新测试结果只报告，不用于再选特征、阈值或参数。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    baseline = json.loads(args.baseline.read_text())["data"]
    experiments = sorted(
        baseline["backtest_experiment"], key=lambda value: value["configuration"]["start"]
    )
    # 线上只读快照包含最近3次五策略复测；历史验收文件另外保存3个九策略区间。
    # 使用线上冻结的原始行情/模型/风险交叉复现历史证据，逐日权益不一致即停止。
    historical_path = root / ".runtime/evidence/experiments-real.json"
    historical = json.loads(historical_path.read_text())
    for evidence in historical:
        config = deepcopy(experiments[0]["configuration"])
        config.update(start=evidence["start"], end=evidence["end"])
        experiments.append({"id": evidence["id"], "configuration": config, "result": evidence})
    strategies = [
        "ma",
        "rsi",
        "macd",
        "momentum",
        "rotation",
        "lightgbm",
        "xgboost",
        "buy_hold",
        "cash",
    ]
    preregistration = {
        "registered_at": datetime.now(UTC).isoformat(),
        "baseline_sha256": hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
        "historical_evidence_sha256": hashlib.sha256(historical_path.read_bytes()).hexdigest(),
        "baseline_revision": "9eb14c6",
        "symbols": ["510300", "510500", "159915"],
        "horizons": [1, 5],
        "seed": 42,
        "algorithms": ["lightgbm", "xgboost"],
        "feature_version": FEATURE_VERSION,
        "features": FEATURES,
        "training_version": TRAINING_VERSION,
        "buy_probability_threshold": 0.55,
        "calibration": "固定最近504已知标签，末20/40/60行独立校准；选择仅依据两类标签是否存在",
        "evaluation": "时间60/10/10/20切分、边界purge、3折walk-forward；最终测试结果不调参",
        "windows": [
            (row["configuration"]["start"], row["configuration"]["end"]) for row in experiments
        ],
        "limitations": [
            "仅RAW价格收益",
            "当前存续3ETF选择偏差",
            "窗口重叠，不是独立重复实验",
            "无点时新闻的LLM/fusion不可历史评估",
            "本轮历史研究不是前瞻样本",
        ],
    }
    write_json(output / "preregistration.json", preregistration)
    old_features = source_module(root, "9eb14c6", "baseline_features", "features.py", output)
    old_engine = source_module(root, "9eb14c6", "baseline_engine", "backtesting.py", output)
    old_engine.feature_frame = old_features.feature_frame
    attribution = []
    for experiment in experiments:
        configuration = experiment["configuration"]
        assert all(
            bar["nature"] == "REAL" and bar["price_basis"] == "RAW" for bar in configuration["bars"]
        )
        window = configuration["start"]
        model_map = configuration["models"]
        record = {
            "original_experiment_id": experiment["id"],
            "start": window,
            "end": configuration["end"],
            "results": {},
        }
        for strategy in strategies:
            if strategy not in experiment["result"]["results"]:
                continue
            original = experiment["result"]["results"][strategy]
            request = payload(configuration, strategy, model_map, "legacy")
            replay = old_engine.run_backtest(old_engine.BacktestRequest(**request))
            if replay["metrics"] != original["metrics"] or replay["curve"] != original["curve"]:
                raise AssertionError(f"冻结输入未复现原始记录: {window}/{strategy}")
            engine_only = run_backtest(BacktestRequest(**request))
            unified = run_backtest(
                BacktestRequest(**payload(configuration, strategy, model_map, "unified"))
            )
            record["results"][strategy] = {
                "original_metrics": original["metrics"],
                "old_engine_exact_replay": True,
                "engine_only": engine_only,
                "engine_and_allocation": unified,
                "engine_return_delta": engine_only["metrics"]["total_return"]
                - original["metrics"]["total_return"],
                "allocation_return_delta": unified["metrics"]["total_return"]
                - engine_only["metrics"]["total_return"],
            }
            print(f"REPLAY {window} {strategy} exact=true", flush=True)
        attribution.append(record)
        write_json(output / f"attribution-{window}-{experiment['id']}.json", record)
    write_json(
        output / "attribution-summary.json",
        [
            {
                "start": item["start"],
                "end": item["end"],
                "original_experiment_id": item["original_experiment_id"],
                "strategies": {
                    name: {
                        "original": value["original_metrics"],
                        "new": value["engine_and_allocation"]["metrics"],
                        "engine_return_delta": value["engine_return_delta"],
                        "allocation_return_delta": value["allocation_return_delta"],
                        "exact_old_replay": value["old_engine_exact_replay"],
                    }
                    for name, value in item["results"].items()
                },
            }
            for item in attribution
        ],
    )
    # 本轮新工件只写本地独立证据目录，不自动替换生产模型或写生产数据库。
    os.environ["ETF_MODEL_DIR"] = str(output / "models-v11")
    configuration = experiments[0]["configuration"]
    bars = configuration["bars"]
    benchmark = [bar for bar in bars if bar["symbol"] == "510300"]
    model_map, trained = defaultdict(list), []
    for symbol in preregistration["symbols"]:
        for algorithm in preregistration["algorithms"]:
            for horizon in preregistration["horizons"]:
                model = train_model(
                    TrainRequest(
                        symbol=symbol,
                        algorithm=algorithm,
                        horizon=horizon,
                        seed=42,
                        bars=[bar for bar in bars if bar["symbol"] == symbol],
                        benchmark=benchmark,
                        trading_dates=configuration["trading_dates"],
                    )
                )
                trained.append(model)
                if horizon == 1:
                    model_map[algorithm].append(model)
                print(f"TRAIN {model['id']} role={model['prediction_model_role']}", flush=True)
    write_json(output / "models-v11.json", trained)
    summaries = []
    completed_windows = set()
    for experiment in experiments:
        config = deepcopy(experiment["configuration"])
        if (config["start"], config["end"]) in completed_windows:
            continue
        completed_windows.add((config["start"], config["end"]))
        # 全部策略统一使用旧/新模型都已有OOS信号的后续成交区间。
        first_signal = max(
            model["training_range"]["test"]["start"]
            for group in model_map.values()
            for model in group
        )
        first_execution = next(day for day in config["trading_dates"] if day > first_signal)
        config["start"] = max(config["start"], first_execution)
        result = {
            "start": config["start"],
            "end": config["end"],
            "nature": "REAL",
            "evaluation_type": "HISTORICAL_OOS",
            "results": {},
        }
        for strategy in strategies:
            result["results"][strategy] = run_backtest(
                BacktestRequest(**payload(config, strategy, model_map, "unified"))
            )
        for strategy in ("llm", "fusion"):
            result["results"][strategy] = {"status": "NOT_ELIGIBLE", "reason": "无可信历史点时新闻"}
        write_json(output / f"new-model-experiment-{config['start']}.json", result)
        summaries.append(
            {
                "start": result["start"],
                "end": result["end"],
                "strategies": {
                    name: value.get("metrics", value) for name, value in result["results"].items()
                },
            }
        )
    write_json(output / "new-model-summary.json", summaries)
    compare_same_windows(args.baseline, output)
    write_json(
        output / "manifest.json",
        {
            "completed_at": datetime.now(UTC).isoformat(),
            "old_replays": sum(len(item["results"]) for item in attribution),
            "new_models": len(trained),
            "paid_calls": 0,
            "production_writes": 0,
            "files": {
                str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in output.rglob("*")
                if path.is_file()
            },
        },
    )
    print("AUDIT COMPLETE: exact old replays, 12 new models, 3 research windows", flush=True)


if __name__ == "__main__":
    main()
