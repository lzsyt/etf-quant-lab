"""V1.1 线上只读基线与发布后核查；不触发任务、模型调用或模拟交易。"""

import argparse
import hashlib
import json
from pathlib import Path
import sys

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from publish import connect, remote


def read_database(client):
    """限定研究表白名单，使用只读事务；排除管理员密码及受保护运行配置。"""
    script = r"""
. 'C:\ETFQuantLab\app\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
$parts=@()
$destination='C:\ETFQuantLab\staging\v11-readonly-baseline.json'
try {
foreach($table in @('etf_instrument','trading_calendar','model_version','prediction','prediction_actual','backtest_experiment','paper_account','paper_position','paper_order','paper_fill','account_snapshot','task_execution','news_item','llm_analysis','api_call_cost','market_sync','risk_config_history')) {
  $selection=$table
  if($table -eq 'backtest_experiment') {$selection="(select * from backtest_experiment where nature='REAL' and status='SUCCEEDED' order by created_at desc limit 3)"}
  if($table -eq 'task_execution') {$selection="(select id,business_key,task_type,status,attempts,started_at,updated_at,error_code from task_execution)"}
  $parts+="'$table',(select coalesce(jsonb_agg(to_jsonb(t)),'[]'::jsonb) from $selection t)"
}
$parts+="'market_bar',(select jsonb_agg(to_jsonb(t)) from market_bar t where nature='REAL')"
$parts+="'migrations',(select jsonb_agg(jsonb_build_object('version',version,'success',success)) from flyway_schema_history)"
$sql='BEGIN READ ONLY; SELECT jsonb_build_object('+($parts -join ',')+'); COMMIT;'
& 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -t -A -v ON_ERROR_STOP=1 -o $destination -c $sql
if($LASTEXITCODE -ne 0){throw 'Read-only snapshot query failed'}
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
Write-Output 'READONLY_SNAPSHOT_READY'
"""
    remote(client, script, "v11-readonly-database.log", timeout=90)
    with client.open_sftp() as sftp:
        with sftp.open("C:/ETFQuantLab/staging/v11-readonly-baseline.json", "rb") as handle:
            return json.loads(handle.read().decode("utf-8-sig"))


def read_health(client):
    """使用专属服务名查询，兼容仅授权目标服务查询的普通部署账号。"""
    script = r"""
$state=@{at=(Get-Date).ToUniversalTime().ToString('o')}
$state.release=Get-Content 'C:\ETFQuantLab\app\current.json' -Raw | ConvertFrom-Json
$state.services=@(foreach($name in @('ETFPostgreSQL','ETFQuantEngine','ETFQuantBackend','ETFQuantWeb')) {
 $s=Get-Service $name; $c=Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$name"
 @{name=$name;state=[string]$s.Status;start=$c.Start;identity=$c.ObjectName}
})
$state.java=Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health'
$state.python=Invoke-RestMethod 'http://127.0.0.1:18081/health'
$state.disk=Get-PSDrive C | Select-Object Used,Free
$state | ConvertTo-Json -Depth 12 -Compress
"""
    return json.loads(remote(client, script, "v11-readonly-health.log", timeout=60))


def read_public():
    """真实HTTPS登录仅在内存持有Cookie，不回显口令或令牌。"""
    config = json.loads((ROOT / ".runtime/server-secrets.json").read_text())
    result = {}
    base = "https://etf.example.invalid"
    with requests.Session() as client:
        result["homepage_status"] = client.get(base + "/", timeout=60).status_code
        result["unauthenticated_status"] = client.get(base + "/api/paper/accounts", timeout=60).status_code
        csrf = client.get(base + "/api/auth/csrf", timeout=60).json()
        login = client.post(
            base + "/api/auth/login",
            data={"username": "admin", "password": config["admin_password"]},
            headers={csrf["header"]: csrf["token"]},
            timeout=60,
        )
        result["login_status"] = login.status_code
        if login.status_code == 204:
            for path in ["/api/market/instruments", "/api/predictions", "/api/backtests",
                         "/api/paper/accounts", "/api/system", "/api/tasks"]:
                response = client.get(base + path, timeout=60)
                result[path] = {"status": response.status_code}
                if response.status_code == 200:
                    body = response.json()
                    result[path]["count"] = len(body) if isinstance(body, list) else None
    return result


def main():
    """保存不含凭据的完整研究基线，输出仅包含核查摘要与不可变记录摘要。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("证据文件已存在，拒绝覆盖")
    # Nginx服务仅管理员可查，读取身份不用于变更安全策略。
    with connect("example.invalid", "Administrator") as client:
        result = {"health": read_health(client)}
    with connect("example.invalid", "etfdeploy") as client:
        result["data"] = read_database(client)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    result["public"] = read_public()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    hashes = {}
    for table in ["prediction", "backtest_experiment", "paper_account", "paper_order", "paper_fill"]:
        values = sorted(result["data"][table], key=lambda row: str(row["id"]))
        hashes[table] = hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()
    print(json.dumps({"health": result["health"], "public": result["public"],
                      "counts": {k: len(v) for k, v in result["data"].items()},
                      "hashes": hashes}, ensure_ascii=False))


if __name__ == "__main__":
    main()
