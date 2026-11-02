"""只读实测真实行情接口；保留结果供集成验收，不把失败替换为演示数据。"""
from datetime import date
import json
from pathlib import Path
import sys

from etf_quant.data import fetch_bars, fetch_calendar
from etf_quant.schemas import FetchRequest


def main():
    folder = Path(__file__).resolve().parents[2] / ".runtime" / "data"
    folder.mkdir(exist_ok=True)
    outcomes = []
    for provider in ("EASTMONEY", "SINA"):
        try:
            request = FetchRequest(symbol="510300", start=date(2021,1,1), end=date.today(),
                                   provider=provider)
            result = fetch_bars(request)
            (folder / f"510300-{provider}.json").write_text(json.dumps(result, ensure_ascii=False))
            outcome = {"provider":provider, "status":"SUCCESS", "rows":len(result["bars"]),
                       "first":result["bars"][0]["trade_date"],
                       "last":result["bars"][-1]["trade_date"],
                       "amount_present":result["bars"][-1]["amount"] is not None}
        except Exception as error:
            outcome = {"provider":provider, "status":"FAILED", "error":str(error)}
        outcomes.append(outcome)
        print(json.dumps(outcome,ensure_ascii=False), flush=True)
    try:
        calendar = fetch_calendar()
        (folder / "calendar.json").write_text(json.dumps(calendar))
        print('Calendar rows:', len(calendar["dates"]), 'coverage:', calendar["coverage_end"])
    except Exception as error:
        print('Calendar unavailable:',type(error).__name__)
    (folder/"probe-results.json").write_text(json.dumps(outcomes, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
