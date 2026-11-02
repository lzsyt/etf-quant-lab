"""V2.1在已恢复的独立数据库演练V10→V11→V10应用兼容，默认不执行。"""

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import zipfile

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from local import environment

OUT = Path.home() / ".codex/workspaces/etf-quant-lab/output/news-v21-20261025"
DB = "etf_news_v21_restore_20261025"
BASE = "http://127.0.0.1:18190"
TABLES = [
    "prediction",
    "prediction_input",
    "prediction_actual",
    "paper_account",
    "paper_position",
    "paper_order",
    "paper_fill",
    "account_snapshot",
    "model_version",
    "llm_analysis",
    "news_item",
    "api_call_cost",
    "forward_experiment",
    "forward_account",
    "forward_model",
    "forward_analysis",
    "forward_plan",
    "forward_decision",
    "forward_actual",
    "forward_settlement",
]


def sql(statement):
    """固定只读查询目标为本轮新恢复库，不接受生产连接或任意库名。"""
    return subprocess.check_output(
        [
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-h",
            str(ROOT / ".runtime/socket"),
            "-p",
            "55432",
            "-U",
            "etf_owner",
            "-d",
            DB,
            "-At",
            "-c",
            statement,
        ],
        text=True,
    ).strip()


def snapshot():
    """对完整历史行计算确定性摘要，而非仅比较记录数。"""
    parts = []
    for table in TABLES:
        key = (
            "decision_id"
            if table == "forward_actual"
            else "prediction_id"
            if table == "prediction_actual"
            else "id"
        )
        parts.append(
            f"'{table}',(select jsonb_build_object('count',count(*),'digest',md5(coalesce(string_agg(row_to_json(t)::text,'' order by {key}),''))) from {table} t)"
        )
    return json.loads(sql("select jsonb_build_object(" + ",".join(parts) + ");"))


def start(jar, label):
    """禁用调度、工作队列及全部收费，仅启动独立端口检验真实迁移与接口。"""
    env = environment()
    env.update(
        ETF_DB_URL="jdbc:postgresql://127.0.0.1:55432/" + DB,
        ETF_PORT="18190",
        ETF_QUANT_URL="http://127.0.0.1:18191",
        ETF_SCHEDULING_ENABLED="false",
        ETF_FORWARD_ENABLED="true",
        ETF_FORWARD_LLM_ENABLED="false",
        ETF_LLM_ENABLED="false",
        ETF_LLM_DAILY_USD="0",
        ETF_WORKER_DELAY="315360000000",
        SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="2",
    )
    with (ROOT / ".runtime/logs" / (label + ".log")).open("w") as log:
        process = subprocess.Popen(
            [
                "/usr/lib/jvm/java-17-openjdk-amd64/bin/java",
                "-Xmx384m",
                "-jar",
                str(jar),
            ],
            env=env,
            stdout=log,
            stderr=log,
        )
    for _ in range(100):
        if process.poll() is not None:
            raise RuntimeError("Isolated application exited: " + label)
        try:
            response = requests.get(BASE + "/actuator/health", timeout=2)
            if response.status_code == 200 and response.json()["status"] == "UP":
                return process
        except requests.RequestException:
            pass
        time.sleep(0.3)
    stop(process)
    raise RuntimeError("Isolated readiness timeout: " + label)


def stop(process):
    """仅停止本脚本启动的隔离应用，不操作既有服务。"""
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def inspect_api():
    """真实登录只读检查管理接口，口令只在内存中使用。"""
    config = json.loads((ROOT / ".runtime/server-secrets.json").read_text())
    with requests.Session() as client:
        csrf = client.get(BASE + "/api/auth/csrf", timeout=10).json()
        login = client.post(
            BASE + "/api/auth/login",
            data={"username": "admin", "password": config["admin_password"]},
            headers={csrf["header"]: csrf["token"]},
            timeout=10,
        )
        assert login.status_code == 204, (
            "Protected restored account did not authenticate"
        )
        forward = client.get(BASE + "/api/forward", timeout=20)
        forward.raise_for_status()
        assert len(forward.json()["accounts"]) == 4
        intelligence = client.get(BASE + "/api/intelligence", timeout=20)
        intelligence.raise_for_status()
        assert intelligence.json()["news_total"] == 13
        return {
            "accounts": 4,
            "news_total": 13,
            "analyses": len(intelligence.json()["analyses"]),
        }


def main():
    """仅显式执行本轮隔离库；旧应用回退不删除新增表，不覆盖任何历史数据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({"status": "NOT_EXECUTED", "database": DB}))
        return
    with socket.socket() as port:
        port.bind(("127.0.0.1", 18190))
    protected = ROOT / ".runtime/news-v21"
    old = protected / "previous.jar"
    with zipfile.ZipFile(protected / "previous.zip") as package:
        old.write_bytes(package.read("backend.jar"))
    before = snapshot()
    report = {"database": DB, "before": before, "paid_calls": 0}
    process = None
    try:
        process = start(old, "news-v21-old-v10")
        report["old_application_start"] = True
        stop(process)
        process = start(
            ROOT / "backend/target/etf-quant-lab-1.0.0.jar", "news-v21-new-v11"
        )
        assert (
            sql("select max(version::integer) from flyway_schema_history where success")
            == "11"
        )
        report["new_api"] = inspect_api()
        assert snapshot() == before
        stop(process)
        process = start(old, "news-v21-rollback-v10")
        assert snapshot() == before
        report.update(
            status="PASSED", old_app_on_v11=True, historical_rows_unchanged=True
        )
    finally:
        if process and process.poll() is None:
            stop(process)
        (OUT / "migration-rehearsal.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2)
        )
    print(
        json.dumps(
            {k: v for k, v in report.items() if k != "before"}, ensure_ascii=False
        )
    )


if __name__ == "__main__":
    main()
