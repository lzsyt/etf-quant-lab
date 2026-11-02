"""显式初始化黄金真实研究链路；凭据仅在进程内，支持本机或已指定正式域名。"""

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit

import httpx

ROOT = Path(__file__).resolve().parents[1]


def digest(value):
    """公开研究JSON按稳定顺序求摘要，不包含凭据或会话。"""
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def allowed_base(value):
    """凭据不能被重定向或发送到任意外部站点，只允许本项目既有入口。"""
    parsed = urlsplit(value)
    allowed = value.rstrip("/") in (
        "http://127.0.0.1:18080", "http://localhost:18080", "https://etf.example.invalid"
    )
    if not allowed or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("初始化目标不在明确允许范围")
    return value.rstrip("/")


def main():
    """分批导入、注册工件、持久任务预测及幂等核验；不主动收费或创建交易。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--credentials", type=Path)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=150)
    args = parser.parse_args()
    if not args.run:
        print("未访问网络；明确 --run 后才执行已授权初始化与预测核验")
        return
    base = allowed_base(args.base_url)
    if args.output.exists():
        raise ValueError("证据输出已存在，拒绝覆盖")
    credential_file = args.credentials or ROOT / ".runtime" / (
        "server-secrets.json" if base.startswith("https:") else "secrets.json"
    )
    if credential_file.stat().st_mode & 0o077:
        raise ValueError("凭据配置权限必须仅当前用户可读写")
    protected = json.loads(credential_file.read_text())
    source = json.loads(args.data.read_text())
    training = json.loads(args.models.read_text())
    assert training.get("models"), "没有真实模型工件，拒绝伪造预测"
    evidence = {
        "target": base, "data_sha256": hashlib.sha256(args.data.read_bytes()).hexdigest(),
        "model_manifest_sha256": hashlib.sha256(args.models.read_bytes()).hexdigest(),
        "imports": [], "models": [model["model_id"] for model in training["models"]],
        "real_data_rows": len(source["observations"]), "paid_analysis_requested": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with httpx.Client(base_url=base, timeout=90, follow_redirects=False) as client:
        csrf = client.get("/api/auth/csrf").json()
        response = client.post("/api/auth/login", data={"username": "admin", "password": protected["admin_password"]},
                               headers={csrf["header"]: csrf["token"]})
        if response.status_code != 204:
            raise RuntimeError("登录未成功，状态码=" + str(response.status_code))
        del protected
        csrf = client.get("/api/auth/csrf").json()

        def post(path, payload):
            """已认证写请求附轮换后的CSRF，失败只报告受控路由和HTTP状态。"""
            response = client.post(path, json=payload, headers={csrf["header"]: csrf["token"]})
            if response.status_code != 200:
                raise RuntimeError(path + " 返回状态码 " + str(response.status_code))
            return response.json()

        def get(path):
            """只读接口不记录Cookie、响应头或任何认证信息。"""
            response = client.get(path)
            if response.status_code != 200:
                raise RuntimeError(path + " 返回状态码 " + str(response.status_code))
            return response.json()

        for offset in range(0, len(source["observations"]), 2000):
            result = post("/api/gold/data/import", {"observations": source["observations"][offset:offset+2000]})
            evidence["imports"].append(result)
        evidence["source_import"] = post("/api/gold/data/import", {
            "observations": [], "sources": source["sources"], "calendar": source.get("calendar", {})
        })
        evidence["model_import"] = post("/api/gold/models/import", {"models": training["models"]})
        key = "gold-initialize-v1:" + evidence["data_sha256"][:12] + ":" + evidence["model_manifest_sha256"][:12]
        request = {"type": "GOLD_PREDICT", "business_key": key}
        submitted = post("/api/gold/tasks", request)
        repeated = post("/api/gold/tasks", request)
        assert submitted["id"] == repeated["id"], "持久任务未按业务键去重"

        def wait(task_id):
            """有限等待黄金夜间任务；不越过时间门禁，也不无限制造重试。"""
            deadline = time.monotonic() + args.timeout
            while time.monotonic() < deadline:
                task = get("/api/tasks/" + task_id)
                if task["status"] == "SUCCEEDED":
                    return task
                if task["status"] == "FAILED":
                    raise RuntimeError("黄金任务失败：" + str(task.get("error_code")))
                time.sleep(1)
            raise RuntimeError("黄金任务等待超时；检查夜间领取窗口和原ETF优先任务")

        task = wait(submitted["id"])
        first = get("/api/gold/overview")
        selected = [row for row in first["predictions"] if row["model_id"] in evidence["models"]]
        assert {row["horizon"] for row in selected} == {63, 126}, "两期限预测未完整冻结"
        assert all(row["actual"] is None for row in selected), "首次前瞻不应提前有到期结果"
        alternate = post("/api/gold/tasks", {"type": "GOLD_PREDICT", "business_key": key + ":repeat-new-task"})
        second_task = wait(alternate["id"])
        second = get("/api/gold/overview")
        assert digest(first["predictions"]) == digest(second["predictions"]), "不同任务重复生成覆写或增生预测"
        for _ in range(3):
            get("/api/gold/overview")
        third = get("/api/gold/overview")
        assert digest(second["predictions"]) == digest(third["predictions"])
        assert digest(second["health"]["tasks"]) == digest(third["health"]["tasks"]), "浏览创建了任务"
        cost = post("/api/gold/cost", {"quantity": "100", "unit_purchase_price": "1020",
                                      "benchmark_price": "1000", "repurchase_deduction_per_gram": "10"})
        assert str(cost["break_even_benchmark_price"]) == "1030", "指定保本测试失败"
        assert cost["profit_probability"] is None
        frozen = []
        for row in selected:
            snapshot = get("/api/gold/predictions/" + row["id"])
            assert snapshot["input_snapshot"]["reference_observation"]
            frozen.append({field: snapshot.get(field) for field in (
                "id", "model_id", "horizon", "reference_date", "reference_price", "generated_at",
                "data_cutoff", "target_date", "target_date_status", "probability_up", "median_return",
                "interval_lower", "interval_upper", "quality", "code_version", "training_cutoff",
                "fit_at", "feature_version", "label_version", "calibration_version", "input_snapshot",
            )})
        evidence.update({"status": "PASSED", "first_task": {"id": task["id"], "status": task["status"]},
                         "second_task": {"id": second_task["id"], "status": second_task["status"]},
                         "same_key_same_task": True, "same_reference_same_prediction": True,
                         "read_only_no_tasks": True, "cost_test_break_even": cost["break_even_benchmark_price"],
                         "coverage": third["coverage"], "predictions": frozen})
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps({"status": evidence["status"], "real_data_rows": evidence["real_data_rows"],
                      "prediction_ids": [row["id"] for row in evidence["predictions"]],
                      "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (httpx.HTTPError, RuntimeError, ValueError, KeyError, AssertionError, OSError) as error:
        # 只处理已知业务/网络/输入失败，不打印HTTP请求头、受保护配置或异常链。
        print(json.dumps({"status": "FAILED", "error_type": type(error).__name__, "reason": str(error)}), file=sys.stderr)
        raise SystemExit(1)
