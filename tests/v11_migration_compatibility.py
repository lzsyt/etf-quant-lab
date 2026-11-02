"""隔离数据库中实际验证V6→V9迁移与旧应用暂停调度回退，不覆盖任何已有库。"""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import zipfile

import httpx
import psycopg

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from local import environment


def main():
    """使用三个真实Java启动阶段核对历史行、迁移版本和旧worker不会执行任务。"""
    stamp = time.strftime("%Y%m%d_%H%M%S")
    output = Path("/home/developer/.codex/workspaces/etf-quant-lab/output/v11-20261024") / ("compatibility-" + stamp)
    output.mkdir(parents=True)
    database = "etf_quant_lab_v11_compat_" + stamp
    with psycopg.connect(host=str(ROOT / ".runtime/socket"), port=55432,
                         user="etf_owner", dbname="postgres", autocommit=True) as owner:
        owner.execute(psycopg.sql.SQL("CREATE DATABASE {} OWNER etf_app").format(psycopg.sql.Identifier(database)))
    protected = json.loads((ROOT / ".runtime/secrets.json").read_text())
    legacy = ROOT / ".runtime/releases/20261024-191738-76002e9.zip"
    assert hashlib.sha256(legacy.read_bytes()).hexdigest() == "74c31f4362799b4a6b64788d89c55beee07a5cc03d4981d36ebd27e9c587da51"
    old_jar = output / "legacy-backend.jar"
    with zipfile.ZipFile(legacy) as archive:
        old_jar.write_bytes(archive.read("backend.jar"))
    env = environment()
    env.update(ETF_DB_URL="jdbc:postgresql://127.0.0.1:55432/" + database,
               ETF_PORT="18180", ETF_SCHEDULING_ENABLED="false", ETF_WORKER_DELAY="315360000000",
               ETF_LOG_DIR=str(output), ETF_QUANT_URL="http://127.0.0.1:18999")
    evidence = {"database": database, "stages": []}
    baseline = None
    for name, jar, expected in [("legacy_v6", old_jar, "6"),
                                 ("current_v9", ROOT / "backend/target/etf-quant-lab-1.0.0.jar", "9"),
                                 ("legacy_on_v9_automatic_tasks_paused", old_jar, "9")]:
        with (output / (name + ".log")).open("wb") as log:
            process = subprocess.Popen(["/usr/lib/jvm/java-17-openjdk-amd64/bin/java", "-Xmx384m",
                                        "-jar", str(jar)], cwd=ROOT, env=env, stdout=log, stderr=log)
            try:
                ready = False
                for _ in range(90):
                    if process.poll() is not None:
                        raise AssertionError("Java startup failed: " + name)
                    try:
                        ready = httpx.get("http://127.0.0.1:18180/actuator/health", timeout=2).status_code == 200
                    except httpx.HTTPError:
                        pass
                    if ready:
                        break
                    time.sleep(1)
                assert ready, "Health timeout: " + name
                with psycopg.connect(host="127.0.0.1", port=55432, user="etf_app",
                                     password=protected["db_password"], dbname=database, autocommit=True) as db:
                    if baseline is None:
                        db.execute("insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                                   "input_cutoff,model_version,nature,direction,details) values(gen_random_uuid(),"
                                   "'V11_ISOLATED_COMPAT','510300','ma',1,'2026-10-23','2026-10-24T10:00Z','TEST','DEMO','FLAT','{}')")
                        db.execute("insert into task_execution(id,business_key,task_type,status,request)"
                                   " values(gen_random_uuid(),'V11_NO_EXECUTION','EOD','QUEUED','{}')")
                        source = json.loads((output.parent / "baseline.json").read_text())["data"]["market_bar"]
                        for bar in source:
                            if bar["symbol"] == "159915" and bar["volume"] == 0:
                                db.execute("insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                                           "price_basis,source,nature,fetched_at,quality_status,quality_flags,raw_values,revision)"
                                           " values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s)",
                                           [bar[k] for k in ["id","symbol","trade_date","open","high","low","close","volume","amount",
                                                             "price_basis","source","nature","fetched_at","quality_status"]]
                                           + [json.dumps(bar["quality_flags"]),json.dumps(bar["raw_values"]),bar["revision"]])
                    # 只核对原V6字段，新增nullable证据列不属于原记录的篡改。
                    frozen = db.execute("select id,business_key,symbol,strategy_id,horizon,signal_date,input_cutoff,"
                                        "created_at,model_version,nature,probability,direction,details from prediction order by id").fetchall()
                    accounts = db.execute("select id,strategy_id,initial_capital,cash,equity,state from paper_account order by id").fetchall()
                    current = hashlib.sha256(repr((frozen, accounts)).encode()).hexdigest()
                    baseline = baseline or current
                    assert current == baseline, "Historical state changed"
                    version = db.execute("select version from flyway_schema_history order by installed_rank desc limit 1").fetchone()[0]
                    assert version == expected
                    if expected == "9":
                        assert db.execute("select count(*) from market_bar where source='SINA_PRICE_TENCENT_VOLUME_VERIFIED'"
                                          " and volume>0 and amount>0 and revision=2 and raw_values->>'volume'='0'"
                                          " and (raw_values->'volume_revision'->>'old_volume')::numeric=0").fetchone()[0] == 4
                    time.sleep(95 if name == "legacy_on_v9_automatic_tasks_paused" else 5)
                    assert db.execute("select status from task_execution where business_key='V11_NO_EXECUTION'").fetchone()[0] == "QUEUED"
                    evidence["stages"].append({"stage": name, "health": "UP", "migration": version,
                                                "original_records_unchanged": True, "queued_task_untouched": True,
                                                "observed_seconds": 95 if name == "legacy_on_v9_automatic_tasks_paused" else 5,
                                                "scheduling_configured": False, "worker_delay_ms": 315360000000})
            finally:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    evidence["status"] = "PASSED"
    (output / "verification.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    main()
