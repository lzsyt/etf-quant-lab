"""开发机离线黄金训练入口：读取冻结输入，保留抓取时间，输出可注册模型清单。"""

import argparse
import json
import time
from pathlib import Path

from etf_quant.gold_models import GoldTrainRequest, train_gold


def main():
    """仅本地训练，不联网采集、不调用LLM、不写业务库或覆盖已有报告。"""
    parser = argparse.ArgumentParser(description="使用冻结黄金数据训练并保存独立工件")
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--code-version", required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("输出报告已存在，拒绝覆盖")
    source = json.loads(args.data.read_text())
    started = time.monotonic()
    result = train_gold(
        GoldTrainRequest(observations=source["observations"], code_version=args.code_version)
    )
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    result["input_file"] = str(args.data.resolve())
    result["nature"] = "LOCAL_MODEL_BUILD_NOT_LIVE_PREDICTION"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(
        json.dumps(
            {
                "status": result["status"],
                "models": [row["model_id"] for row in result["models"]],
                "elapsed_seconds": result["elapsed_seconds"],
            }
        )
    )


if __name__ == "__main__":
    main()
