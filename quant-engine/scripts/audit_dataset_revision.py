"""按V9真实跨源证据修正四行量额，参数不变地追加独立数据修订实验。"""

import argparse
import hashlib
import json
import os
import re
from collections import defaultdict
from copy import deepcopy
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from audit_v11 import payload, write_json

from etf_quant.backtesting import BacktestRequest, run_backtest
from etf_quant.features import FEATURE_VERSION, FEATURES
from etf_quant.models import TrainRequest, metrics, train_model


def corrected_configuration(configuration, migration, evidence):
    """逐项验证V9原值和腾讯原始量纲，只修改四行volume/amount及明确来源元数据。"""
    result = deepcopy(configuration)
    comparison = json.loads((evidence / "comparison.json").read_text())
    known = {row["trade_date"]: row for row in comparison["zero_volume_dates"]}
    revisions = []
    statements = re.findall(r"UPDATE market_bar SET.*?;", migration.read_text(), flags=re.DOTALL)
    if len(statements) != 4:
        raise ValueError("V9必须恰好包含四个精确行修正，禁止扩大数据范围")
    for statement in statements:
        day = re.search(r"trade_date='([^']+)'", statement).group(1)
        revision = json.loads(re.search(r"'({[^\n]+})'::jsonb", statement).group(1))
        row = next(
            bar for bar in result["bars"] if bar["symbol"] == "159915" and bar["trade_date"] == day
        )
        before = deepcopy(row)
        if (
            row["source"] != "AKSHARE_SINA"
            or Decimal(row["volume"]) != 0
            or Decimal(row["amount"]) != 0
        ):
            raise ValueError("冻结原数据不匹配V9的来源与原始零量额条件")
        source = next(
            item for item in comparison["requests"] if item["sha256"] == revision["raw_sha256"]
        )
        raw_path = evidence / source["raw_file"]
        if hashlib.sha256(raw_path.read_bytes()).hexdigest() != revision["raw_sha256"]:
            raise ValueError("跨源原始响应摘要不匹配")
        raw = next(
            item
            for item in json.loads(raw_path.read_text())["data"]["sz159915"]["day"]
            if item[0] == day
        )
        if Decimal(raw[5]) * 100 != Decimal(revision["volume"]) or Decimal(
            raw[8]
        ) * 10000 != Decimal(revision["amount"]):
            raise ValueError("腾讯原始成交手数/万元金额与V9标准化值不一致")
        if not all(
            Decimal(row[key]) == Decimal(known[day]["tencent"][key])
            for key in ("open", "high", "low", "close")
        ):
            raise ValueError("四行价格不能变化或存在跨源价格冲突")
        revision.update(old_source=row["source"], old_volume=0, old_amount=0)
        row["raw_values"]["volume_revision"] = revision
        row["volume"] = str(Decimal(revision["volume"]).quantize(Decimal(".0001")))
        row["amount"] = str(Decimal(revision["amount"]).quantize(Decimal(".0001")))
        row["source"] = "SINA_PRICE_TENCENT_VOLUME_VERIFIED"
        row["quality_flags"] = [
            flag for flag in row["quality_flags"] if flag != "ZERO_VOLUME_NO_FILL"
        ]
        row["quality_status"] = "WARNING" if row["quality_flags"] else "VALID"
        revisions.append({"trade_date": day, "before": before, "after": deepcopy(row)})
    return result, revisions


def main():
    """独立目录保留失败和成功证据；不更新数据库，不覆盖任何旧模型或实验。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--previous", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    migration = root / "database/migrations/V9__verified_volume_revisions.sql"
    baseline = json.loads(args.baseline.read_text())["data"]
    config, revisions = corrected_configuration(
        baseline["backtest_experiment"][0]["configuration"], migration, args.evidence
    )
    assert (
        sum(
            left != right
            for left, right in zip(
                baseline["backtest_experiment"][0]["configuration"]["bars"],
                config["bars"],
                strict=True,
            )
        )
        == 4
    )
    assert all(bar["nature"] == "REAL" and bar["price_basis"] == "RAW" for bar in config["bars"])
    previous_models = json.loads((args.previous / "models-v11.json").read_text())
    previous_by_key = {(m["symbol"], m["algorithm"], m["horizon"]): m for m in previous_models}
    previous_summary = json.loads((args.previous / "new-model-summary.json").read_text())
    preregistration = {
        "registered_at": datetime.now(UTC).isoformat(),
        "reason": "真实独立源确认原四行零成交量/额错误；仅纠错重跑，不按已知收益选择特征或参数",
        "baseline_sha256": hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
        "migration_sha256": hashlib.sha256(migration.read_bytes()).hexdigest(),
        "comparison_sha256": hashlib.sha256(
            (args.evidence / "comparison.json").read_bytes()
        ).hexdigest(),
        "previous_preregistration_sha256": hashlib.sha256(
            (args.previous / "preregistration.json").read_bytes()
        ).hexdigest(),
        "seed": 42,
        "feature_version": FEATURE_VERSION,
        "features": FEATURES,
        "buy_probability_threshold": 0.55,
        "hyperparameters": {
            algorithm: next(
                m["hyperparameters"] for m in previous_models if m["algorithm"] == algorithm
            )
            for algorithm in ("lightgbm", "xgboost")
        },
        "windows": [(item["start"], item["end"]) for item in previous_summary],
        "unchanged": [
            "OHLC价格",
            "交易日历",
            "初始本金",
            "成本和风险",
            "特征版本",
            "模型超参数",
            "随机种子",
            "阈值",
            "严格训练/校准/验证/测试分段规则",
            "既有预测/实验/模型",
        ],
        "source_sha256": {
            name: hashlib.sha256(
                (root / "quant-engine/src/etf_quant" / name).read_bytes()
            ).hexdigest()
            for name in ("models.py", "features.py", "backtesting.py", "data.py", "schemas.py")
        },
    }
    write_json(output / "preregistration.json", preregistration)
    write_json(output / "dataset-revisions.json", revisions)
    write_json(output / "corrected-bars.json", config["bars"])
    os.environ["ETF_MODEL_DIR"] = str(output / "models")
    trained, model_map = [], defaultdict(list)
    benchmark = [bar for bar in config["bars"] if bar["symbol"] == "510300"]
    for symbol in ("510300", "510500", "159915"):
        for algorithm in ("lightgbm", "xgboost"):
            for horizon in (1, 5):
                result = train_model(
                    TrainRequest(
                        symbol=symbol,
                        algorithm=algorithm,
                        horizon=horizon,
                        seed=42,
                        bars=[bar for bar in config["bars"] if bar["symbol"] == symbol],
                        benchmark=benchmark,
                        trading_dates=config["trading_dates"],
                    )
                )
                old = previous_by_key[(symbol, algorithm, horizon)]
                assert result["hyperparameters"] == old["hyperparameters"]
                assert result["feature_version"] == old["feature_version"]
                if symbol != "159915":
                    assert (
                        result["id"] == old["id"]
                        and result["oos_predictions"] == old["oos_predictions"]
                    )
                else:
                    assert result["data_sha256"] != old["data_sha256"] and result["id"] != old["id"]
                trained.append(result)
                if horizon == 1:
                    model_map[algorithm].append(result)
                print(f"TRAIN_CORRECTED {result['id']}", flush=True)
    write_json(output / "models.json", trained)
    write_json(
        output / "production-model-plan.json",
        [
            {
                key: model[key]
                for key in (
                    "id",
                    "symbol",
                    "algorithm",
                    "horizon",
                    "data_sha256",
                    "data_cutoff",
                    "feature_version",
                    "model_parameter_version",
                    "fit_data_cutoff",
                    "calibration_data_cutoff",
                )
            }
            for model in trained
        ],
    )
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
    summaries = []
    for old in previous_summary:
        current = deepcopy(config)
        current.update(start=old["start"], end=old["end"])
        # 依照原V2实验共同窗口比较，不借样本恢复把更早不同日期混入对照。
        first_signal = max(
            model["training_range"]["test"]["start"]
            for group in model_map.values()
            for model in group
        )
        first_execution = next(day for day in current["trading_dates"] if day > first_signal)
        assert current["start"] >= first_execution
        result = {"start": current["start"], "end": current["end"], "results": {}}
        for strategy in strategies:
            result["results"][strategy] = run_backtest(
                BacktestRequest(**payload(current, strategy, model_map, "unified"))
            )
        for strategy in ("llm", "fusion"):
            result["results"][strategy] = {"status": "NOT_ELIGIBLE", "reason": "无可信历史点时新闻"}
        write_json(output / f"experiment-{current['start']}.json", result)
        summaries.append(
            {
                "start": current["start"],
                "end": current["end"],
                "strategies": {
                    name: {
                        "previous_dataset": old["strategies"][name],
                        "corrected_dataset": value.get("metrics", value),
                    }
                    for name, value in result["results"].items()
                },
            }
        )
    write_json(output / "comparison-summary.json", summaries)
    pairs = []
    for new in trained:
        old = previous_by_key[(new["symbol"], new["algorithm"], new["horizon"])]
        old_rows = {row["signal_date"]: row for row in old["oos_predictions"]}
        common = [
            (old_rows[row["signal_date"]], row)
            for row in new["oos_predictions"]
            if row["signal_date"] in old_rows
        ]
        assert all(
            left["target_date"] == right["target_date"] and left["actual_up"] == right["actual_up"]
            for left, right in common
        )
        labels = [right["actual_up"] for _, right in common]
        pairs.append(
            {
                "symbol": new["symbol"],
                "algorithm": new["algorithm"],
                "horizon": new["horizon"],
                "first_signal_date": common[0][1]["signal_date"],
                "last_signal_date": common[-1][1]["signal_date"],
                "previous_dataset": metrics(labels, [left["probability"] for left, _ in common]),
                "corrected_dataset": metrics(labels, [right["probability"] for _, right in common]),
            }
        )
    write_json(output / "paired-probability-metrics.json", pairs)
    write_json(
        output / "manifest.json",
        {
            "completed_at": datetime.now(UTC).isoformat(),
            "corrected_rows": 4,
            "models": 12,
            "same_window_experiments": len(summaries),
            "paid_calls": 0,
            "production_writes": 0,
            "files": {
                str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in output.rglob("*")
                if path.is_file()
            },
        },
    )
    print("CORRECTED DATASET AUDIT COMPLETE", flush=True)


if __name__ == "__main__":
    main()
