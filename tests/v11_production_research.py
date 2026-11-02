"""V1.1 生产研究登记：默认离线计划，--execute 才经正式接口串行训练和回测。"""

import argparse
import hashlib
import json
import re
import stat
import time
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = Path("/home/developer/.codex/workspaces/etf-quant-lab/output/v11-20261024")
BASE = "https://etf.example.invalid"
SYMBOLS = ["510300", "510500", "159915"]
START, END = "2026-07-01", "2026-10-23"
TRAINING_VERSION = "purged-evaluation-plus-recent-production-v1.1"


def digest(path):
    """摘要只针对非敏感研究证据，不读取或摘要凭据。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_plan(args):
    """核对本地预注册与十二工件计划，构造服务器实际支持的任务载荷。"""
    corrected = args.corrected
    expected = json.loads((corrected / "production-model-plan.json").read_text())
    metadata = json.loads((corrected / "models.json").read_text())
    registration = json.loads((corrected / "preregistration.json").read_text())
    baseline = json.loads(args.baseline.read_text())["data"]
    if digest(args.baseline) != registration["baseline_sha256"]:
        raise ValueError("基线摘要与纠错实验预注册不一致")
    expected_keys = {
        (s, a, h) for s in SYMBOLS for a in ("lightgbm", "xgboost") for h in (1, 5)
    }
    key = lambda row: (row["symbol"], row["algorithm"], row["horizon"])
    if len(expected) != 12 or {key(row) for row in expected} != expected_keys:
        raise ValueError("生产模型计划必须恰好包含3只ETF、2个算法、1/5日共12组")
    references = {key(row): row for row in metadata}
    if set(references) != expected_keys or len(metadata) != 12:
        raise ValueError("完整纠错模型证据与生产计划的范围不同")
    for row in expected:
        if any(
            references[key(row)].get(field) != value for field, value in row.items()
        ):
            raise ValueError("生产计划字段与完整纠错工件证据不一致")
        if references[key(row)]["training_version"] != TRAINING_VERSION:
            raise ValueError("纠错工件不是V1.1生产与评估分离的固定训练版本")
    if registration["seed"] != 42 or registration["buy_probability_threshold"] != 0.55:
        raise ValueError("预注册种子或阈值不匹配本次固定实验")
    config = baseline["backtest_experiment"][0]["configuration"]
    tag = args.run_key + "-" + digest(corrected / "production-model-plan.json")[:12]
    tasks = []
    for row in expected:
        tasks.append(
            {
                "type": "TRAIN",
                "business_key": f"{tag}-{row['symbol']}-{row['algorithm']}-h{row['horizon']}",
                "payload": {
                    "symbol": row["symbol"],
                    "algorithm": row["algorithm"],
                    "horizon": row["horizon"],
                    "start": "2021-01-01",
                    "end": END,
                    "nature": "REAL",
                    "price_basis": "RAW",
                },
            }
        )
    for mode, strategies in (
        ("history", ["ma", "llm", "fusion", "buy_hold", "cash"]),
        ("oos", ["ma", "lightgbm", "xgboost", "llm", "fusion", "buy_hold", "cash"]),
    ):
        tasks.append(
            {
                "type": "BACKTEST",
                "business_key": f"{tag}-{mode}-h1-{START}-{END}",
                "payload": {
                    "symbols": SYMBOLS,
                    "strategies": strategies,
                    "start": START,
                    "end": END,
                    "horizon": 1,
                    "nature": "REAL",
                    "initial_capital": config["initial_capital"],
                    "risk": config["risk"],
                },
            }
        )
    return {
        "endpoint": BASE,
        "run_key": tag,
        "expected_models": expected,
        "tasks": tasks,
        "evidence_hashes": {
            name: digest(corrected / name)
            for name in (
                "production-model-plan.json",
                "models.json",
                "preregistration.json",
            )
        },
        "semantics": [
            "仅POST /api/tasks的TRAIN和BACKTEST；不调用EOD、预测、新闻、行情抓取或付费API",
            "12次训练串行完成后才回测；随机种子由现有Java固定42，不接受参数搜索",
            "LightGBM/XGBoost收益只来自冻结严格OOS预测，规则历史单独保存",
            "LLM及融合缺少历史新闻证据，正式接口只记录NOT_ELIGIBLE",
            "接口不支持上传冻结config或指定历史model_id，既有三个窗口结果原样保留",
            "历史和OOS的七月共同窗口、资金、成本与风控相同；买入持有受相同约束",
            "相同业务键复用原任务；输出文件必须新建，失败后不自动改参数或换键重试",
        ],
    }, references


def request_json(client, method, path, **kwargs):
    """仅允许固定研究接口，错误只报告状态码，不输出响应或认证材料。"""
    if method == "POST" and path != "/api/tasks":
        raise ValueError("研究阶段禁止调用非任务写接口")
    response = client.request(method, BASE + path, timeout=(10, 90), **kwargs)
    if response.status_code != 200:
        raise RuntimeError(f"研究接口 {method} {path} 返回HTTP {response.status_code}")
    return response.json()


def login(client):
    """沿用受保护配置登录，口令和CSRF仅在进程内存中使用。"""
    secret = ROOT / ".runtime/server-secrets.json"
    if stat.S_IMODE(secret.stat().st_mode) & 0o077:
        raise ValueError("server-secrets.json权限不够严格，请由管理员核对受保护配置")
    config = json.loads(secret.read_text())
    csrf = request_json(client, "GET", "/api/auth/csrf")
    for attempt in range(3):
        response = client.post(
            BASE + "/api/auth/login",
            data={"username": "admin", "password": config["admin_password"]},
            headers={csrf["header"]: csrf["token"]},
            timeout=(10, 60),
        )
        if response.status_code != 429 or attempt == 2:
            break
        # 遵守既有Nginx每分钟2次限制，不改安全策略；错误口令不自动重试。
        time.sleep(31)
    config.clear()
    if response.status_code != 204:
        raise RuntimeError(f"管理登录失败：HTTP {response.status_code}")
    # Spring Security登录后会轮换CSRF，后续POST必须获取新令牌。
    csrf = request_json(client, "GET", "/api/auth/csrf")
    client.headers.update({csrf["header"]: csrf["token"]})


def save(output, evidence):
    """原子保存本次执行的进度；只更新本脚本已独占创建的证据文件。"""
    temporary = output.with_suffix(output.suffix + ".pending")
    temporary.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    temporary.replace(output)


def run_task(client, task, output, evidence, args):
    """一个任务终结后才允许排下一个，失败保留服务端真实结果及业务标识。"""
    if task["type"] not in ("TRAIN", "BACKTEST"):
        raise ValueError("禁止执行非训练/回测任务")
    accepted = request_json(client, "POST", "/api/tasks", json=task)
    record = {"submitted": task, "accepted": accepted, "observed_states": []}
    evidence["executions"].append(record)
    save(output, evidence)
    deadline = time.monotonic() + args.task_timeout
    last_state = None
    while time.monotonic() < deadline:
        result = request_json(client, "GET", "/api/tasks/" + accepted["id"])
        if (
            result.get("task_type") != task["type"]
            or result.get("request") != task["payload"]
        ):
            raise RuntimeError("业务键已有不同载荷，拒绝误用其他任务结果")
        state = (result.get("status"), result.get("attempts"))
        record["task"] = result
        if state != last_state:
            record["observed_states"].append(
                {
                    "at": datetime.now(UTC).isoformat(),
                    "status": state[0],
                    "attempts": state[1],
                }
            )
            print(
                f"{task['type']} {task['business_key']} {state[0]} attempts={state[1]}",
                flush=True,
            )
            last_state = state
        save(output, evidence)
        if state[0] == "SUCCEEDED":
            return result["result"]
        if state[0] == "FAILED" and int(state[1] or 0) >= 3:
            raise RuntimeError("任务已失败三次，停止后续研究；完整错误码见证据")
        # 服务端失败任务五分钟后自动重试，持续观察原任务，不额外创建任务。
        time.sleep(args.poll_seconds)
    raise TimeoutError("任务尚未终结，停止新增任务；服务端原任务仍可能运行或自动重试")


def compare_model(actual, expected):
    """数据和固定实验参数必须一致；跨平台ID/默认参数/依赖差异单独披露。"""
    fields = [
        "id",
        "data_sha256",
        "data_cutoff",
        "feature_version",
        "model_parameter_version",
        "fit_data_cutoff",
        "calibration_data_cutoff",
        "training_range",
        "dependencies",
        "hyperparameters",
        "prediction_model_role",
        "historical_predictions_model_role",
    ]
    differences = {
        field: {"expected": expected.get(field), "actual": actual.get(field)}
        for field in fields
        if actual.get(field) != expected.get(field)
    }
    blocking = [
        field
        for field in (
            "symbol",
            "algorithm",
            "horizon",
            "data_sha256",
            "data_cutoff",
            "feature_version",
            "fit_data_cutoff",
            "calibration_data_cutoff",
            "training_range",
            "nature",
            "price_basis",
            "training_version",
            "prediction_model_role",
            "historical_predictions_model_role",
        )
        if actual.get(field) != expected.get(field)
    ]
    parameters = actual.get("hyperparameters", {})
    expected_parameters = expected["hyperparameters"]
    for section, names in {
        "estimator": [
            "n_estimators",
            "max_depth",
            "learning_rate",
            "n_jobs",
            "random_state",
            *(
                ["num_leaves", "min_child_samples", "deterministic", "force_col_wise"]
                if expected["algorithm"] == "lightgbm"
                else ["tree_method", "device", "reg_lambda", "eval_metric"]
            ),
        ],
        "calibrator": ["random_state", "max_iter"],
    }.items():
        for name in names:
            if parameters.get(section, {}).get(name) != expected_parameters[
                section
            ].get(name):
                blocking.append(f"hyperparameters.{section}.{name}")
    for name in (
        "training_version",
        "production_window",
        "production_calibration_candidates",
        "evaluation_split",
    ):
        if parameters.get(name) != expected_parameters.get(name):
            blocking.append("hyperparameters." + name)
    result = {
        "expected_id": expected["id"],
        "actual_id": actual.get("id"),
        "data_matches": actual.get("data_sha256") == expected["data_sha256"],
        "differences": differences,
        "blocking_differences": blocking,
        "interpretation": "ID及参数摘要包含依赖版本，差异不能宣称完全复现；固定数据或实验规则不符则停止",
    }
    return result


def check_backtest(result, task):
    """核验实际共同区间、全部组别与历史新闻排除状态，禁止自动接受改区间。"""
    expected_type = (
        "HISTORICAL_OOS"
        if "lightgbm" in task["payload"]["strategies"]
        else "HISTORICAL_RULE"
    )
    if (
        result.get("start"),
        result.get("end"),
        result.get("evaluation_type"),
        result.get("nature"),
    ) != (
        START,
        END,
        expected_type,
        "REAL",
    ):
        raise RuntimeError(
            "正式回测的实际研究区间或证据类别与计划不同，不能冒称同窗口比较"
        )
    groups = result.get("results", {})
    if set(groups) != set(task["payload"]["strategies"]):
        raise RuntimeError("正式回测缺少计划中的实验组")
    for name in ("llm", "fusion"):
        if groups[name].get("status") != "NOT_ELIGIBLE":
            raise RuntimeError("没有历史新闻证据时LLM/融合不能产生历史收益")
    common_dates = None
    for name, value in groups.items():
        if name in ("llm", "fusion"):
            continue
        if not value.get("curve") or "metrics" not in value:
            raise RuntimeError("可运行实验组缺少真实权益曲线或指标")
        dates = [point["trade_date"] for point in value["curve"]]
        if common_dates is not None and dates != common_dates:
            raise RuntimeError("实验组实际权益序列日期不同，禁止拼接为共同窗口")
        common_dates = dates
        if value.get("engine_version") != "daily-decimal-v1.1.0":
            raise RuntimeError("正式任务未使用本轮已审计的V1.1回测引擎")
        if Decimal(str(value["metrics"]["initial_capital"])) != Decimal(
            task["payload"]["initial_capital"]
        ):
            raise RuntimeError("实际回测本金与冻结计划不同")
        actual_risk = value.get("benchmark", {}).get("risk", {})
        for field, planned in task["payload"]["risk"].items():
            if field not in actual_risk or Decimal(str(actual_risk[field])) != Decimal(
                str(planned)
            ):
                raise RuntimeError("实际回测成本或风险限制与冻结计划不同")


def execute(args, plan, references):
    """先留存计划，再登录、检查空闲队列并串行提交，不改任何旧记录。"""
    if args.output is None:
        raise ValueError("--execute必须同时指定新的--output证据JSON文件")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(
            {"status": "INITIALIZING", "plan": plan},
            handle,
            ensure_ascii=False,
            indent=2,
        )
    evidence = {
        "started_at": datetime.now(UTC).isoformat(),
        "status": "RUNNING",
        "plan": plan,
        "executions": [],
        "model_comparisons": [],
    }
    try:
        with requests.Session() as client:
            login(client)
            prior_tasks = request_json(client, "GET", "/api/tasks")
            evidence["preflight"] = {
                "tasks": prior_tasks,
                "models": request_json(client, "GET", "/api/models"),
                "backtests": request_json(client, "GET", "/api/backtests"),
            }
            own_keys = {
                task["type"] + ":" + task["business_key"] for task in plan["tasks"]
            }
            if any(
                row["business_key"] not in own_keys
                and (
                    row["status"] in ("QUEUED", "RUNNING")
                    or (row["status"] == "FAILED" and row.get("attempts", 0) < 3)
                )
                for row in prior_tasks
            ):
                raise RuntimeError(
                    "已有其他未终结或待自动重试任务，请先核对，不并发追加研究"
                )
            for task in plan["tasks"]:
                actual = run_task(client, task, args.output, evidence, args)
                if task["type"] == "TRAIN":
                    payload = task["payload"]
                    comparison = compare_model(
                        actual,
                        references[
                            (
                                payload["symbol"],
                                payload["algorithm"],
                                payload["horizon"],
                            )
                        ],
                    )
                    evidence["model_comparisons"].append(comparison)
                    save(args.output, evidence)
                    if comparison["blocking_differences"]:
                        raise RuntimeError(
                            "生产模型数据或固定训练规则不匹配预注册，已保存差异并停止后续任务"
                        )
                else:
                    check_backtest(actual, task)
            evidence["registered_models"] = request_json(client, "GET", "/api/models")
            registered_ids = {row["id"] for row in evidence["registered_models"]}
            old_ids = {row["id"] for row in evidence["preflight"]["models"]}
            trained_ids = {row["actual_id"] for row in evidence["model_comparisons"]}
            if not (old_ids | trained_ids).issubset(registered_ids):
                raise RuntimeError("生产模型登记核验失败，旧记录或本轮训练记录缺失")
            evidence["backtests_after"] = request_json(client, "GET", "/api/backtests")
            evidence["status"] = "SUCCEEDED"
    except Exception as error:  # noqa: BLE001 -- 最外层脱敏并保存失败证据，仍重新抛出。
        evidence["status"] = "FAILED"
        # 仅输出本脚本固定错误或异常类型，requests异常不得携带请求认证材料进入证据。
        evidence["error"] = (
            str(error)
            if type(error) in (RuntimeError, ValueError, TimeoutError)
            else type(error).__name__
        )
        raise RuntimeError(evidence["error"]) from None
    finally:
        evidence["finished_at"] = datetime.now(UTC).isoformat()
        save(args.output, evidence)
    print(
        json.dumps(
            {
                "status": evidence["status"],
                "tasks": len(evidence["executions"]),
                "model_comparison_differences": sum(
                    bool(row["differences"]) for row in evidence["model_comparisons"]
                ),
                "output": str(args.output),
            },
            ensure_ascii=False,
        )
    )


def main():
    """默认不读密码、不联网、不写文件；执行必须显式给出--execute。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", type=Path, default=EVIDENCE / "baseline.json")
    parser.add_argument(
        "--corrected", type=Path, default=EVIDENCE / "dataset-revision-corrected"
    )
    parser.add_argument("--run-key", default="v11-20261024-v9-corrected")
    parser.add_argument("--poll-seconds", type=float, default=3)
    parser.add_argument("--task-timeout", type=float, default=1800)
    args = parser.parse_args()
    if not re.fullmatch(r"v11-[A-Za-z0-9_-]{1,80}", args.run_key):
        parser.error("业务键必须以v11-开头且仅包含字母、数字、下划线、短横线")
    if not 1 <= args.poll_seconds <= 30 or not 60 <= args.task_timeout <= 7200:
        parser.error("轮询间隔须1～30秒，单任务超时须60～7200秒")
    plan, references = load_plan(args)
    if args.execute:
        execute(args, plan, references)
    else:
        print(
            json.dumps(
                {"mode": "PLAN_ONLY_NO_NETWORK", **plan}, ensure_ascii=False, indent=2
            )
        )


if __name__ == "__main__":
    main()
