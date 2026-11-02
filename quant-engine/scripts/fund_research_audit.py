"""只读公开净值的本地研究复现；输出到指定隔离目录，绝不写生产数据库或收费。"""

import argparse
import json
import os
from pathlib import Path


from etf_quant.fund_data import FUND_CODES, FundFetchRequest, FundObservation, fetch_fund_nav
from etf_quant.fund_models import FundPredictRequest, FundTrainRequest, predict_funds, train_funds


def main():
    """依次抓取、训练和冻结，输入输出完整保存以便独立核验。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--nav-input", help="从已保存真实观测离线复现，省略时才抓取公开来源")
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    os.environ["ETF_MODEL_DIR"] = str(output / "models")
    results = []
    for code in FUND_CODES:
        data = (
            json.loads((Path(args.nav_input) / f"nav-{code}.json").read_text())
            if args.nav_input
            else fetch_fund_nav(FundFetchRequest(fund_code=code))
        )
        (output / f"nav-{code}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2))
        rows = [FundObservation.model_validate(row) for row in data["observations"]]
        trained = train_funds(
            FundTrainRequest(fund_code=code, observations=rows, code_version="fund-nav-v1")
        )
        models = [model for model in trained["models"] if model.get("status") == "TRAINED"]
        predicted = predict_funds(
            FundPredictRequest(fund_code=code, observations=rows, models=models)
        )
        result = {
            "fund_code": code,
            "data_count": data["count"],
            "data_start": str(rows[0].nav_date),
            "data_end": data["latest_nav_date"],
            "models": trained["models"],
            "forecasts": predicted["forecasts"],
        }
        (output / f"research-{code}.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2)
        )
        results.append(result)
        print(
            code,
            data["count"],
            data["latest_nav_date"],
            [(model["horizon"], model["validation_status"]) for model in models],
            flush=True,
        )
    summary = {
        "funds": [
            {key: result[key] for key in ("fund_code", "data_count", "data_start", "data_end")}
            for result in results
        ],
        "models": [
            {
                key: model[key]
                for key in ("id", "fund_code", "horizon", "validation_status", "metrics", "split")
            }
            for result in results
            for model in result["models"]
        ],
        "production_database_writes": 0,
        "paid_calls": 0,
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print("summary", output / "summary.json", flush=True)


if __name__ == "__main__":
    main()
