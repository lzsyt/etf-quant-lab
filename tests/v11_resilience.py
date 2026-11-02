"""V1.1真实恢复验收：默认仅显示计划，显式--execute才影响已授权ETF Windows环境。"""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time
import uuid

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from publish import connect, remote  # noqa: E402

HOST = "example.invalid"
BASE = "https://etf.example.invalid"
OUTPUT = Path.home() / ".codex/workspaces/etf-quant-lab/output/v11-20261024/resilience"
SERVICES = ("ETFPostgreSQL", "ETFQuantEngine", "ETFQuantBackend", "ETFQuantWeb")
TABLES = ("prediction", "prediction_input", "prediction_actual", "paper_account",
          "paper_position", "paper_order", "paper_fill", "account_snapshot", "api_call_cost")
RUN_ID = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]


def powershell(script, label, timeout=60):
    """严格复用固定主机密钥和专属SSH密钥，凭据只存在受保护进程环境。"""
    with connect(HOST, "Administrator") as client:
        return remote(client, script, f"v11-resilience-{RUN_ID}-{label}.log", timeout=timeout).strip()


def query(statement, label):
    """验收SQL固定为只读事务，不读取管理员凭据表、不打印运行配置。"""
    script = r"""
. 'C:\ETFQuantLab\app\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
$sql=@'
BEGIN READ ONLY;
""" + statement + r"""
COMMIT;
'@
try {
 $sql | & 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -X -q -t -A -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -v ON_ERROR_STOP=1
 if($LASTEXITCODE -ne 0){throw 'Read-only resilience query failed'}
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
"""
    return json.loads(powershell(script, label))


def snapshot(label):
    """单条只读SQL的一致性快照核对所有冻结预测、账户、成交及费用，不包含任务心跳。"""
    parts = []
    for table in TABLES:
        key = "prediction_id" if table == "prediction_actual" else "id"
        parts.append(
            f"'{table}',(select jsonb_build_object('count',count(*),'digest',"
            f"md5(coalesce(string_agg(row_to_json(t)::text,'' order by {key}),''))) from {table} t)"
        )
    return query("SELECT jsonb_build_object(" + ",".join(parts) + ");", label)


def health(label):
    """四个自动服务、实际Java/Python健康及启动时间分别核实，不把服务Running当成就绪。"""
    return json.loads(powershell(r"""
$state=@{}
$state.release=(Get-Content 'C:\ETFQuantLab\app\current.json' -Raw | ConvertFrom-Json).id
$state.boot=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')
$state.services=@(foreach($name in @('ETFPostgreSQL','ETFQuantEngine','ETFQuantBackend','ETFQuantWeb')) {
 $s=Get-CimInstance Win32_Service -Filter "Name='$name'"
 @{name=$name;state=$s.State;start=$s.StartMode;pid=$s.ProcessId}
})
$state.java=Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 5
$state.python=Invoke-RestMethod 'http://127.0.0.1:18081/health' -TimeoutSec 5
$state.java_pid=(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18080 -State Listen |
 Select-Object -First 1).OwningProcess
$state.python_pid=(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18081 -State Listen |
 Select-Object -First 1).OwningProcess
$state.checked_at=(Get-Date).ToUniversalTime().ToString('o')
$state | ConvertTo-Json -Depth 8 -Compress
""", label))


def healthy(value):
    """只接受所有目标服务自动启动且两个应用均真实UP的结果。"""
    services = {item["name"]: item for item in value.get("services", [])}
    return (set(services) == set(SERVICES)
            and all(item["state"] == "Running" and item["start"] == "Auto" for item in services.values())
            and value.get("java", {}).get("status") == "UP"
            and value.get("python", {}).get("status") == "UP")


@contextmanager
def authenticated():
    """只从0600配置取实际管理员口令，登录失败即停止，不重设口令或绕过鉴权。"""
    path = Path(os.environ.get("ETF_E2E_CONFIG", ROOT / ".runtime/server-secrets.json"))
    if path.stat().st_mode & 0o077:
        raise RuntimeError("Administrator credential file must be restricted to its owner")
    config = json.loads(path.read_text())
    password = config.get("admin_password")
    if not password:
        raise RuntimeError("Administrator credential is unavailable")
    with requests.Session() as client:
        page = client.get(BASE + "/", timeout=20)
        if page.status_code != 200:
            raise RuntimeError("Public HTTPS homepage failed")
        csrf_response = client.get(BASE + "/api/auth/csrf", timeout=20)
        if csrf_response.status_code != 200:
            raise RuntimeError("CSRF handshake failed")
        csrf = csrf_response.json()
        for attempt in range(3):
            login = client.post(BASE + "/api/auth/login",
                                data={"username": "admin", "password": password},
                                headers={csrf["header"]: csrf["token"]}, timeout=20)
            if login.status_code != 429 or attempt == 2:
                break
            # 保留现有登录限速，验收只能等待窗口，不降低生产安全门禁。
            time.sleep(31)
        if login.status_code != 204:
            raise RuntimeError("Stored administrator credential did not authenticate; no reset attempted")
        csrf = client.get(BASE + "/api/auth/csrf", timeout=20).json()
        client.headers[csrf["header"]] = csrf["token"]
        yield client


def preflight():
    """仅在已核实休市日、队列空闲、V1.1已上线且近期备份存在时允许故障演练。"""
    with authenticated() as client:
        for path in ("/api/market/instruments", "/api/predictions", "/api/paper/accounts"):
            if client.get(BASE + path, timeout=20).status_code != 200:
                raise RuntimeError("Authenticated read-only preflight failed")
    conditions = query("""SELECT jsonb_build_object(
 'database',current_database(),
 'local_date',(now() at time zone 'Asia/Shanghai')::date,
 'is_open',(select is_open from trading_calendar where trade_date=(now() at time zone 'Asia/Shanghai')::date
            and source not like 'OBSERVED_%'),
 'migration',(select max(version::integer) from flyway_schema_history where success),
 'pending_tasks',(select count(*) from task_execution where status in ('RUNNING','QUEUED')
                  or (status='FAILED' and attempts<3)));
""", "preflight-database")
    if conditions["database"] != "etf_quant_lab" or conditions["migration"] < 9:
        raise RuntimeError("V1.1 schema is not installed on the expected ETF database")
    if conditions["is_open"] is not False:
        raise RuntimeError("Fault acceptance requires an explicitly verified closed day, not missing calendar coverage")
    if conditions["pending_tasks"]:
        raise RuntimeError("Research queue is not idle; finish or inspect its existing tasks first")
    checked = health("preflight-health")
    if not healthy(checked):
        raise RuntimeError("Services are not healthy before the test")
    backup = json.loads(powershell(r"""
$root='C:\ETFQuantLab'
$release=(Get-Content "$root\app\current.json" -Raw | ConvertFrom-Json).id
$meta=Get-Content "$root\releases\$release\release.json" -Raw | ConvertFrom-Json
if($meta.version -ne '1.1.0'){throw 'V1.1 release metadata required'}
$manifest=Get-ChildItem "$root\backup" -Filter backup-manifest.json -Recurse -File |
 Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if(-not $manifest){throw 'Verified backup is required before failure acceptance'}
$record=Get-Content $manifest.FullName -Raw | ConvertFrom-Json
if(((Get-Date).ToUniversalTime()-[DateTime]::Parse($record.created_at).ToUniversalTime()).TotalHours -gt 24){
 throw 'Recent backup is required'
}
$dump=Join-Path $manifest.DirectoryName 'database.dump'
$application=Join-Path $manifest.DirectoryName 'application.zip'
if(-not (Test-Path $application) -or -not $record.application_included){throw 'Application backup is missing'}
if((Get-FileHash $dump -Algorithm SHA256).Hash -ne $record.database_sha256){throw 'Database backup checksum failed'}
if((Get-FileHash $application -Algorithm SHA256).Hash -ne $record.application_sha256){throw 'Application backup checksum failed'}
@{directory=$manifest.DirectoryName;created_at=$record.created_at;database_verified=$true;
 application_included=$true;model_count=@($record.model_artifacts).Count;release=$release} |
 ConvertTo-Json -Compress
""", "preflight-backup", timeout=90))
    return {"conditions": conditions, "health": checked, "backup": backup}


def restore_services():
    """失败清理只启动同一项目原本健康的服务，绝不恢复旧数据、改密码或修改安全策略。"""
    powershell(r"""
foreach($name in @('ETFPostgreSQL','ETFQuantEngine','ETFQuantBackend','ETFQuantWeb')) {
 $service=Get-Service $name
 if($service.Status -eq 'StopPending'){$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))}
 if($service.Status -eq 'StartPending'){$service.WaitForStatus('Running',[TimeSpan]::FromSeconds(30))}
 $service.Refresh()
 if($service.Status -ne 'Running'){Start-Service $name}
}
. 'C:\ETFQuantLab\app\common.ps1'
Wait-Health -Seconds 120
Write-Output 'ETF_SERVICES_RECOVERED'
""", "finally-recover", timeout=160)


def wait_recovered(before, mode, timeout):
    """等待真实自动恢复；整机模式必须观察启动时间改变，超时不能记为通过。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            current = health(mode + "-waiting-health")
            if healthy(current) and (mode != "windows" or current["boot"] != before["boot"]):
                return current
        except Exception:
            # 启动窗口仅保留失败类别；不回显SSH/HTTP认证上下文。
            pass
        time.sleep(5)
    raise TimeoutError("ETF services did not recover within the acceptance deadline")


def check_public_after():
    """重启后重新登录，确认服务端会话恢复路径和公开页面读取均可用。"""
    status = {}
    with authenticated() as client:
        for path in ("/api/market/instruments", "/api/predictions", "/api/backtests", "/api/paper/accounts"):
            response = client.get(BASE + path, timeout=20)
            status[path] = response.status_code
            if response.status_code != 200:
                raise RuntimeError("Authenticated business read failed after recovery")
    return {"login": "PASSED", "endpoints": status}


def weekend_task(day):
    """同一EOD键提交两次只领取一次休市任务，并验证不会触发收费或伪造预测。"""
    key = "v11-resilience-closed-day:" + day
    request = {"type": "EOD", "payload": {}, "business_key": key}
    with authenticated() as client:
        first = client.post(BASE + "/api/tasks", json=request, timeout=20)
        second = client.post(BASE + "/api/tasks", json=request, timeout=20)
        if first.status_code != 200 or second.status_code != 200:
            raise RuntimeError("Closed-day task submission failed")
        identifier = first.json()["id"]
        if second.json()["id"] != identifier:
            raise RuntimeError("Repeated EOD business key created a duplicate task")
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            response = client.get(BASE + "/api/tasks/" + identifier, timeout=20)
            if response.status_code != 200:
                raise RuntimeError("Closed-day task polling failed")
            task = response.json()
            if task["status"] == "SUCCEEDED":
                if task.get("result", {}).get("status") != "SKIPPED_NON_TRADING_DAY":
                    raise RuntimeError("EOD did not take the required no-fee closed-day path")
                return {"id": identifier, "business_key": key, "status": task["status"],
                        "result_status": task["result"]["status"], "attempts": task["attempts"]}
            if task["status"] == "FAILED":
                raise RuntimeError("Closed-day EOD failed instead of safely skipping")
            time.sleep(2)
    raise TimeoutError("Closed-day EOD task did not complete")


def inject_fault(mode):
    """仅针对固定ETF服务注入已授权故障；PostgreSQL自行finally恢复，不依赖本机SSH连接存活。"""
    if mode == "python":
        return json.loads(powershell(r"""
$owners=@(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18081 -State Listen |
 Select-Object -ExpandProperty OwningProcess -Unique)
if($owners.Count -ne 1){throw 'Expected exactly one project listener; refusing ambiguous process kill'}
$target=Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
if($target.Name -ne 'python.exe' -or $target.ExecutablePath -notlike 'C:\ETFQuantLab\runtime\*' -or
 $target.CommandLine -notmatch 'etf_quant\.api:app'){throw 'Listener does not belong to the ETF Python application'}
Stop-Process -Id $target.ProcessId -Force
@{killed_python_pid=$target.ProcessId;
 expectation='Existing WinSW automatic restart; no changes to recovery policy'} | ConvertTo-Json -Compress
""", "python-fault"))
    if mode == "postgres":
        return json.loads(powershell(r"""
$root='C:\ETFQuantLab'
$javaBefore=(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18080 -State Listen |
 Select-Object -First 1).OwningProcess
try {
 & "$root\runtime\pgsql\bin\pg_ctl.exe" -D "$root\data\postgres" stop -m fast -w -t 30 | Out-Null
 if($LASTEXITCODE -ne 0){throw 'PostgreSQL controlled stop failed'}
 $tcp=New-Object Net.Sockets.TcpClient
 $unavailable=$false
 try {$tcp.Connect('127.0.0.1',55432)} catch {$unavailable=$true} finally {$tcp.Dispose()}
 $during=Get-CimInstance Win32_Service -Filter "Name='ETFQuantBackend'"
 $javaDuring=(Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18080 -State Listen |
  Select-Object -First 1).OwningProcess
 Start-Sleep -Seconds 5
 if(-not $unavailable){throw 'Database outage was not observed'}
 @{database_unavailable_observed=$unavailable;java_pid_before=$javaBefore;
 java_pid_during=$javaDuring;backend_state_during=$during.State;
 retained_backend_process=($during.State -eq 'Running' -and $javaDuring -eq $javaBefore)} |
 ConvertTo-Json -Compress
} finally {
 $service=Get-Service ETFPostgreSQL
 if($service.Status -eq 'StopPending'){$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))}
 $service.Refresh()
 if($service.Status -ne 'Running'){Start-Service ETFPostgreSQL}
}
""", "postgres-fault", timeout=90))
    if mode == "windows":
        powershell(r"""
& shutdown.exe /r /t 5 /d p:4:1 /c 'ETF Quant Lab authorized V1.1 recovery acceptance'
if($LASTEXITCODE -ne 0){throw 'Windows restart request failed'}
Write-Output 'AUTHORIZED_RESTART_SCHEDULED'
""", "windows-fault")
        return {"restart_requested": True, "policy_changed": False}
    raise ValueError("Unknown failure mode")


def perform(mode, checks, output):
    """每个阶段独立保存前后摘要；失败也落盘，并先恢复服务再向调用者报告失败。"""
    started = time.monotonic()
    record = {"mode": mode, "status": "RUNNING", "started_at": datetime.now(timezone.utc).isoformat()}
    mutated = False
    failure = None
    try:
        record["before"] = snapshot(mode + "-before")
        record["health_before"] = health(mode + "-health-before")
        if mode == "weekend":
            record["task"] = weekend_task(checks["conditions"]["local_date"])
        else:
            mutated = True
            record["fault"] = inject_fault(mode)
            record["health_after"] = wait_recovered(record["health_before"], mode, 480 if mode == "windows" else 180)
            if mode == "python" and record["health_after"]["python_pid"] == record["fault"]["killed_python_pid"]:
                raise RuntimeError("Python process replacement was not observed")
            if mode == "postgres" and (not record["fault"]["retained_backend_process"]
                                       or record["health_after"]["java_pid"] != record["fault"]["java_pid_before"]):
                raise RuntimeError("Database recovered, but backend process was not retained; cannot claim connection-pool recovery")
        record["public"] = check_public_after()
        record["after"] = snapshot(mode + "-after")
        if record["before"] != record["after"]:
            record["changed_tables"] = [name for name in TABLES if record["before"][name] != record["after"][name]]
            raise RuntimeError("Frozen predictions, account ledger or cost state changed during acceptance")
        record["state_unchanged"] = True
        record["status"] = "PASSED"
    except BaseException as error:
        failure = error
        record["status"] = "FAILED"
        record["error_type"] = type(error).__name__
        if type(error) in (RuntimeError, TimeoutError, ValueError):
            record["reason"] = str(error)[:240]
    finally:
        if mutated:
            try:
                restore_services()
                record["finally_recovery"] = "HEALTHY"
            except BaseException as recovery_error:
                record["finally_recovery"] = "MANUAL_INTERVENTION_REQUIRED"
                record["recovery_error_type"] = type(recovery_error).__name__
                record["status"] = "FAILED"
                failure = failure or recovery_error
        record["elapsed_seconds"] = round(time.monotonic() - started, 2)
        record["completed_at"] = datetime.now(timezone.utc).isoformat()
        path = output / (RUN_ID + "-" + mode + ".json")
        with path.open("x") as handle:
            json.dump(record, handle, ensure_ascii=False, indent=2)
        print(json.dumps({"mode": mode, "status": record["status"], "evidence": str(path)}, ensure_ascii=False), flush=True)
    if failure is not None:
        raise RuntimeError("Acceptance failed; inspect sanitized evidence and ensure all ETF services are restored") from None
    return record


def main():
    """默认无网络、无服务动作；实际执行必须显式给出--execute并通过全部前置核查。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("weekend", "python", "postgres", "windows", "all"), required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    modes = ("weekend", "python", "postgres", "windows") if args.mode == "all" else (args.mode,)
    if not args.execute:
        print(json.dumps({"status": "NOT_EXECUTED", "modes": modes,
                          "target": "ETF Quant Lab Windows only", "requires": "--execute after backup and V1.1 deployment"}, ensure_ascii=False))
        return
    args.output.mkdir(parents=True, exist_ok=True)
    preflight_path = args.output / (RUN_ID + "-preflight.json")
    try:
        checks = preflight()
    except Exception as error:
        evidence = {"status": "FAILED_BEFORE_ANY_FAULT", "error_type": type(error).__name__}
        if type(error) in (RuntimeError, TimeoutError, ValueError):
            evidence["reason"] = str(error)[:240]
        with preflight_path.open("x") as handle:
            json.dump(evidence, handle, ensure_ascii=False, indent=2)
        raise
    with preflight_path.open("x") as handle:
        json.dump(checks, handle, ensure_ascii=False, indent=2)
    for mode in modes:
        print(json.dumps({"mode": mode, "status": "STARTING"}), flush=True)
        perform(mode, checks, args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # 只输出错误类型，HTTP请求、配置内容、口令、Cookie和CSRF令牌永不进入控制台。
        print(json.dumps({"status": "FAILED", "error_type": type(error).__name__}), flush=True)
        raise SystemExit(1)
