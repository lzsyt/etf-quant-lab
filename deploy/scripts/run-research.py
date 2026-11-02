"""通过真实后端API训练和回测，报告只写实际返回的结果。"""
from datetime import date
import json
from pathlib import Path
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"tests"))
from smoke import authenticated_client


def run_job(client,kind,payload):
    """轮询持久化任务，明确失败，不以空报告替代。"""
    response=client.post("/api/tasks",json={"type":kind,"payload":payload,
        "business_key":"acceptance-"+uuid.uuid4().hex})
    response.raise_for_status()
    job=response.json()["id"]
    for _ in range(600):
        status=client.get("/api/tasks/"+job).json()
        if status["status"]=="SUCCEEDED":
            return status["result"]
        if status["status"]=="FAILED":
            raise RuntimeError("Job failed: "+str(status.get("error_code")))
        time.sleep(.5)
    raise TimeoutError("Research job timeout")


def main():
    output=ROOT/".runtime/evidence"
    output.mkdir(exist_ok=True)
    with authenticated_client() as client:
        symbols=["510300","510500","159915"]
        models=json.loads((output/"models-real.json").read_text()) if "--backtests-only" in sys.argv else []
        for symbol in ([] if "--backtests-only" in sys.argv else symbols):
            for algorithm in ("lightgbm","xgboost"):
                for horizon in (1,5):
                    result=run_job(client,"TRAIN",{"symbol":symbol,"algorithm":algorithm,
                        "horizon":horizon,"start":"2021-01-01","end":"2026-10-23","nature":"REAL"})
                    models.append(result)
                    print(json.dumps({"trained":result["id"],"metrics":result["metrics"]["test"]},ensure_ascii=False),flush=True)
        (output/"models-real.json").write_text(json.dumps(models,ensure_ascii=False,indent=2))
        experiments=[]
        for start in ("2024-01-01","2026-04-01","2026-07-01"):
            result=run_job(client,"BACKTEST",{"symbols":symbols,"strategies":["ma","rsi","macd",
                "momentum","rotation","lightgbm","xgboost","llm","fusion","buy_hold","cash"],
                "start":start,"end":"2026-10-23","nature":"REAL","initial_capital":"100000.00"})
            experiments.append(result)
            print(json.dumps({"experiment":result["id"],"start":result["start"],"end":result["end"],
                "results":{k:v.get("metrics",v) for k,v in result["results"].items()}},ensure_ascii=False),flush=True)
        (output/"experiments-real.json").write_text(json.dumps(experiments,ensure_ascii=False,indent=2))
    lines=["# 真实行情训练与回测报告","","本报告由实际API执行结果生成，虚拟本金每组100,000元。",
        "采用2021-01-04至2026-10-23的新浪未复权日线；只衡量价格收益，未核实的分红不计入。",
        "信号在收盘后形成，后续交易日开盘价加价差与滑点进行假设成交，日线成交不代表真实盘中成交。",
        "默认单ETF20%、总仓位40%、单笔5%、单日新增10%，佣金万三且最低5元，滑点5bp、价差2bp。",
        "买入持有基准在最初5个交易日按相同约束逐步建仓，此后不再平衡；轮动选择最强品种，趋势动量对满足条件品种等权分配。",
        "","## 模型样本外结果","","|ETF|模型|窗口|测试样本|准确率|Brier|测试开始|测试结束|",
        "|---|---|---:|---:|---:|---:|---|---|"]
    for model in models:
        metric=model["metrics"]["test"];period=model["training_range"]["test"]
        lines.append(f'|{model["symbol"]}|{model["algorithm"]}|{model["horizon"]}|{metric["samples"]}|'
            f'{metric["accuracy"]:.2%}|{metric["brier_score"]:.4f}|{period["start"]}|{period["end"]}|')
    for experiment in experiments:
        lines += ["",f'## 实验 {experiment["start"]} — {experiment["end"]}',"",
                  f'实验ID：`{experiment["id"]}`。所有组共用机器学习可用的样本外成交窗口。',"",
                  "|策略|扣费收益|最大回撤|Sharpe|成交笔数|手续费|较风险约束买入持有超额|",
                  "|---|---:|---:|---:|---:|---:|---:|"]
        baseline=experiment["results"]["buy_hold"]["metrics"]["total_return"]
        for name,result in experiment["results"].items():
            if "metrics" not in result:
                lines.append(f'|{name}|不可评估：缺少点时新闻|—|—|—|—|—|')
                continue
            m=result["metrics"];sharpe="未定义" if m["sharpe"] is None else f'{m["sharpe"]:.3f}'
            lines.append(f'|{name}|{m["total_return"]:.2%}|{m["max_drawdown"]:.2%}|{sharpe}|'
                         f'{m["trade_count"]}|{m["fees"]}|{m["total_return"]-baseline:.2%}|')
    lines += ["","## 解释边界","",
              "结果不证明未来可盈利。研究池为当前存续的三只ETF，存在选择偏差；窗口部分重叠，不是独立重复检验。",
              "模型未随机打乱样本；训练、校准、验证、测试按时间顺序划分，标签跨界被清除，并另做三折Walk-Forward。",
              "LLM与融合策略没有可信历史点时新闻，因此不输出伪造历史收益，也没有证据宣称优于机器学习。",
              "上线后的前瞻预测和模拟收益需等待未来真实交易日，不包含在此报告的历史统计中。"]
    (ROOT/"docs/真实行情回测报告.md").write_text("\n".join(lines)+"\n")
    print("Real research report generated",flush=True)


if __name__=="__main__":
    main()
