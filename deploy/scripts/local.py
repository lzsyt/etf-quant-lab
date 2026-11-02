#!/usr/bin/env python3
"""独立本地环境管理；配置限当前用户读取，不修改系统默认工具或已有数据库。"""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".runtime"
PG = ROOT / ".tools/postgres/usr/lib/postgresql/16/bin"


def environment():
    """将受保护文件中的凭据只注入当前启动的子进程。"""
    config = json.loads((RUNTIME / "secrets.json").read_text())
    return dict(os.environ, ETF_DB_URL="jdbc:postgresql://127.0.0.1:55432/etf_quant_lab",
                ETF_DB_USER="etf_app", ETF_DB_PASSWORD=config["db_password"],
                ETF_ADMIN_PASSWORD_HASH=config["admin_hash"],
                ETF_INTERNAL_TOKEN=config["internal_token"],
                ETF_MODEL_DIR=str(RUNTIME / "models"),
                ETF_LOG_DIR=str(RUNTIME / "logs"), OMP_NUM_THREADS="1",
                OPENBLAS_NUM_THREADS="1", MKL_NUM_THREADS="1")


def initialize():
    """仅初始化本工程专用集群；存在配置或角色时不覆盖、不清空数据。"""
    import crypt
    RUNTIME.mkdir(mode=0o700, exist_ok=True)
    os.chmod(RUNTIME, 0o700)
    for folder in ("logs", "socket", "models"):
        (RUNTIME / folder).mkdir(mode=0o700, exist_ok=True)
    secret_file = RUNTIME / "secrets.json"
    if not secret_file.exists():
        password = secrets.token_urlsafe(24)
        config = {"db_password":secrets.token_urlsafe(32), "admin_password":password,
                  "admin_hash":crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH)),
                  "internal_token":secrets.token_urlsafe(40)}
        descriptor = os.open(secret_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump(config, handle)
    start_database()
    config = json.loads(secret_file.read_text())
    query = ["/usr/bin/psql", "-h", str(RUNTIME / "socket"), "-p", "55432",
             "-U", "etf_owner", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"]
    found = subprocess.run(query, input="SELECT 1 FROM pg_roles WHERE rolname='etf_app';",
                           text=True, capture_output=True, check=True).stdout.strip()
    if not found:
        # password 是本函数生成的 URL-safe 字符串；永不写入命令行参数或日志。
        sql = "CREATE ROLE etf_app LOGIN PASSWORD '" + config["db_password"] + "';"
        subprocess.run(query, input=sql, text=True, stdout=subprocess.DEVNULL, check=True)
    found = subprocess.run(query, input="SELECT 1 FROM pg_database WHERE datname='etf_quant_lab';",
                           text=True, capture_output=True, check=True).stdout.strip()
    if not found:
        subprocess.run(query, input="CREATE DATABASE etf_quant_lab OWNER etf_app;", text=True,
                       stdout=subprocess.DEVNULL, check=True)
    found = subprocess.run(query, input="SELECT 1 FROM pg_database WHERE datname='etf_quant_lab_test';",
                           text=True, capture_output=True, check=True).stdout.strip()
    if not found:
        subprocess.run(query, input="CREATE DATABASE etf_quant_lab_test OWNER etf_app;", text=True,
                       stdout=subprocess.DEVNULL, check=True)
    print("Independent PostgreSQL and protected local configuration ready")


def start_database():
    """数据库仅监听本机，使用本工程 PID 与日志，不触碰系统数据库。"""
    if not (RUNTIME / "postgres/PG_VERSION").exists():
        subprocess.run([str(PG / "initdb"), "-D", str(RUNTIME / "postgres"), "-U", "etf_owner",
                        "--encoding=UTF8", "--locale=C.UTF-8", "--auth-local=trust",
                        "--auth-host=scram-sha-256"], check=True)
    status = subprocess.run([str(PG/"pg_ctl"), "-D", str(RUNTIME/"postgres"), "status"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if status.returncode == 0:
        return
    options = (f"-p 55432 -h 127.0.0.1 -k {RUNTIME / 'socket'} "
               "-c timezone=UTC -c shared_buffers=64MB -c max_connections=25")
    subprocess.run([str(PG/"pg_ctl"), "-D", str(RUNTIME/"postgres"), "-l",
                    str(RUNTIME/"logs/postgresql.log"), "-o", options, "start"], check=True)


def start(name, command):
    """只启动本工程服务，以回环端口隔离，输出写入受保护运行目录。"""
    pid_file = RUNTIME / (name + ".pid")
    if pid_file.exists():
        try:
            os.kill(int(pid_file.read_text()), 0)
            print(name, "already running")
            return
        except ProcessLookupError:
            pass
    with (RUNTIME / "logs" / (name + ".console.log")).open("ab") as log:
        proc = subprocess.Popen(command, cwd=ROOT, env=environment(), stdout=log, stderr=log,
                                start_new_session=True)
    pid_file.write_text(str(proc.pid))
    print(name, "started")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["init", "start", "test-java", "stop"])
    args = parser.parse_args()
    if args.action == "init":
        initialize()
    elif args.action == "start":
        start_database()
        start("quant", [str(ROOT/".venv/bin/python"), "-m", "uvicorn", "etf_quant.api:app",
                        "--host", "127.0.0.1", "--port", "18081", "--workers", "1"])
        start("backend", ["/usr/lib/jvm/java-17-openjdk-amd64/bin/java", "-Xms128m", "-Xmx768m",
                          "-jar", str(ROOT/"backend/target/etf-quant-lab-1.0.0.jar")])
    elif args.action == "test-java":
        test_env = environment()
        test_env["ETF_DB_URL"] = "jdbc:postgresql://127.0.0.1:55432/etf_quant_lab_test"
        # 多个隔离Spring上下文不能耗尽专属PG的25连接；不修改业务启动配置。
        test_env["SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"] = "3"
        test_env["SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"] = "1"
        raise SystemExit(subprocess.run(["bash", str(ROOT/"deploy/scripts/mvn.sh"),
                                         "spotless:apply", "test", "-Dspring.test.context.cache.maxSize=2"], env=test_env).returncode)
    else:
        for name in ("backend", "quant"):
            path = RUNTIME/(name+".pid")
            if path.exists():
                pid = int(path.read_text())
                command = Path(f"/proc/{pid}/cmdline")
                if command.exists() and str(ROOT).encode() in command.read_bytes():
                    os.kill(pid, signal.SIGTERM)
                    for _ in range(100):
                        if not command.exists() or not command.read_bytes():
                            break
                        time.sleep(0.1)
                path.unlink()
