"""在新建隔离库真实启动V11→黄金版→V11，验证追加迁移和不恢复数据库的应用回退。"""

import hashlib
import json
import subprocess
import sys
import time
import zipfile
from pathlib import Path

import httpx
import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from local import environment


def main():
    """隔离库保留可复核，不接触生产库、其他服务或真实费用接口。"""
    stamp = time.strftime("%Y%m%d_%H%M%S")
    output = Path(
        "/home/developer/.codex/workspaces/etf-quant-lab/output/gold-20261026"
    ) / ("compatibility-" + stamp)
    output.mkdir(parents=True)
    database = "etf_gold_compat_" + stamp
    with psycopg.connect(
        host=str(ROOT / ".runtime/socket"),
        port=55432,
        user="etf_owner",
        dbname="postgres",
        autocommit=True,
    ) as owner:
        owner.execute(
            sql.SQL("CREATE DATABASE {} OWNER etf_app").format(sql.Identifier(database))
        )
    protected = json.loads((ROOT / ".runtime/secrets.json").read_text())
    legacy = ROOT / ".runtime/releases/20261026-200931-94bcc9e.zip"
    old_jar = output / "legacy-backend.jar"
    with zipfile.ZipFile(legacy) as archive:
        old_jar.write_bytes(archive.read("backend.jar"))
    env = environment()
    env.update(
        ETF_DB_URL="jdbc:postgresql://127.0.0.1:55432/" + database,
        ETF_PORT="18180",
        ETF_SCHEDULING_ENABLED="false",
        ETF_GOLD_ENABLED="false",
        ETF_WORKER_DELAY="315360000000",
        ETF_LOG_DIR=str(output),
        ETF_QUANT_URL="http://127.0.0.1:18999",
    )
    evidence = {
        "database": database,
        "legacy_sha256": hashlib.sha256(legacy.read_bytes()).hexdigest(),
        "stages": [],
    }
    baseline = None
    for name, jar, expected in [
        ("legacy_v11", old_jar, "11"),
        ("gold_v13", ROOT / "backend/target/etf-quant-lab-1.0.0.jar", "13"),
        ("legacy_on_v13", old_jar, "13"),
    ]:
        with (output / (name + ".log")).open("wb") as log:
            process = subprocess.Popen(
                [
                    "/usr/lib/jvm/java-17-openjdk-amd64/bin/java",
                    "-Xmx384m",
                    "-jar",
                    str(jar),
                ],
                cwd=ROOT,
                env=env,
                stdout=log,
                stderr=log,
            )
            try:
                ready = False
                for _ in range(75):
                    if process.poll() is not None:
                        raise AssertionError("Java启动失败：" + name)
                    try:
                        ready = (
                            httpx.get(
                                "http://127.0.0.1:18180/actuator/health", timeout=2
                            ).status_code
                            == 200
                        )
                    except httpx.HTTPError:
                        pass
                    if ready:
                        break
                    time.sleep(1)
                assert ready, name
                with psycopg.connect(
                    host="127.0.0.1",
                    port=55432,
                    user="etf_app",
                    password=protected["db_password"],
                    dbname=database,
                    autocommit=True,
                ) as db:
                    if baseline is None:
                        db.execute(
                            "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                            "input_cutoff,model_version,nature,direction,details) values(gen_random_uuid(),"
                            "'GOLD_ISOLATED_COMPAT','510300','ma',1,'2026-10-23','2026-10-24T10:00Z','TEST','DEMO','FLAT','{}')"
                        )
                        db.execute(
                            "insert into task_execution(id,business_key,task_type,status,request)"
                            " values(gen_random_uuid(),'GOLD_COMPAT_ETF_QUEUE','EOD','QUEUED','{}')"
                        )
                    if name == "gold_v13":
                        db.execute(
                            "insert into gold_source(series_id,status,checked_at,details) values"
                            " ('ISOLATED_COMPAT_ONLY','EMPTY',now(),'{\"nature\":\"MOCK\"}')"
                        )
                        db.execute(
                            "insert into task_execution(id,business_key,task_type,status,request) values"
                            " (gen_random_uuid(),'GOLD_COMPAT_PAUSED','GOLD_PREDICT','QUEUED','{}')"
                        )
                        # 正式应用回退仅暂停黄金队列，旧worker不消费未知类型；不覆盖业务数据。
                        db.execute(
                            "update task_execution set status='PAUSED' where left(task_type,5)='GOLD_'"
                        )
                    rows = db.execute(
                        "select to_jsonb(p)::text from prediction p order by id"
                    ).fetchall()
                    accounts = db.execute(
                        "select to_jsonb(a)::text from paper_account a order by id"
                    ).fetchall()
                    current = hashlib.sha256(
                        repr((rows, accounts)).encode()
                    ).hexdigest()
                    baseline = baseline or current
                    assert current == baseline, "旧预测/账户发生变化"
                    version = db.execute(
                        "select version from flyway_schema_history order by installed_rank desc limit 1"
                    ).fetchone()[0]
                    assert version == expected
                    assert (
                        db.execute(
                            "select status from task_execution where business_key='GOLD_COMPAT_ETF_QUEUE'"
                        ).fetchone()[0]
                        == "QUEUED"
                    )
                    if expected == "13":
                        assert (
                            db.execute(
                                "select status from task_execution where business_key='GOLD_COMPAT_PAUSED'"
                            ).fetchone()[0]
                            == "PAUSED"
                        )
                        assert (
                            db.execute(
                                "select count(*) from gold_source where series_id='ISOLATED_COMPAT_ONLY'"
                            ).fetchone()[0]
                            == 1
                        )
                    evidence["stages"].append(
                        {
                            "stage": name,
                            "health": "UP",
                            "migration": version,
                            "legacy_records_unchanged": True,
                            "etf_queue_untouched": True,
                            "gold_records_retained": expected == "13",
                            "database_restored": False,
                        }
                    )
            finally:
                process.terminate()
                try:
                    process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    evidence["status"] = "PASSED"
    (output / "verification.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2)
    )
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    main()
