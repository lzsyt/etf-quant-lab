"""黄金增量前后只读核对线上版本和既有账本；凭据仅保留在进程内。"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy/scripts"))
from publish import connect, remote


def snapshot():
    """远端仅SELECT和健康查询，所有证据输出到本机，不写远端临时文件。"""
    script = r"""
. 'C:\ETFQuantLab\app\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
try {
$sql=@'
BEGIN READ ONLY;
SELECT jsonb_build_object(
 'migrations',(select jsonb_agg(jsonb_build_object('version',version,'success',success)) from flyway_schema_history),
 'tasks',(select jsonb_agg(to_jsonb(t)) from (select id,business_key,task_type,status,attempts,started_at,updated_at,error_code from task_execution order by updated_at desc limit 35)t),
 'models',(select jsonb_agg(to_jsonb(t)) from (select id,symbol,algorithm,available_at from forward_model)t),
 'accounts',(select jsonb_agg(to_jsonb(t)) from forward_account t),
 'decisions',(select jsonb_agg(to_jsonb(t)) from (select id,account_id,signal_date,entry_date,evaluation_date,generated_at from forward_decision order by generated_at desc limit 24)t),
 'news',(select jsonb_agg(to_jsonb(t)) from (select id,status,input_cutoff,created_at,error_code,quote->>'prompt_version' prompt_version,quote->>'schema_version' schema_version,result->'result' analysis_result from forward_analysis order by created_at desc limit 3)t),
 'costs',(select jsonb_agg(to_jsonb(t)) from (select id,business_key,status,reserved_cost,actual_cost,input_tokens,output_tokens,created_at from api_call_cost order by created_at desc limit 20)t),
 'latest_prices',(select jsonb_agg(to_jsonb(t)) from (select symbol,max(trade_date) latest,count(*) rows from market_bar group by symbol)t)
);
COMMIT;
'@
$data=& 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -t -A -v ON_ERROR_STOP=1 -c $sql
if($LASTEXITCODE -ne 0){throw 'Read-only audit failed'}
$hashes=@{}
foreach($table in @('model_version','prediction','prediction_actual','paper_account','paper_position','paper_order','paper_fill','account_snapshot','forward_experiment','forward_model','forward_plan','forward_decision','forward_actual','forward_settlement','forward_account','llm_analysis','forward_analysis','api_call_cost')) {
 $query="BEGIN READ ONLY; SELECT coalesce(jsonb_agg(jsonb_build_object('key',coalesce(to_jsonb(t)->>'id',to_jsonb(t)->>'decision_id',md5(to_jsonb(t)::text)),'hash',md5(to_jsonb(t)::text))),'[]'::jsonb) FROM $table t; COMMIT;"
 $rows=& 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -X -q -t -A -v ON_ERROR_STOP=1 -c $query
 if($LASTEXITCODE -ne 0){throw 'Read-only hash audit failed'}
 $hashes[$table]=($rows | ConvertFrom-Json)
}
$release=Get-Content 'C:\ETFQuantLab\app\current.json' -Raw | ConvertFrom-Json
$safe=@{}
foreach($name in @('ETF_SCHEDULING_ENABLED','ETF_FORWARD_ENABLED','ETF_FORWARD_LLM_ENABLED','ETF_LLM_ENABLED','ETF_LLM_DAILY_USD','DEEPSEEK_MODEL','ETF_GOLD_ENABLED','ETF_GOLD_LLM_ENABLED','OMP_NUM_THREADS','OPENBLAS_NUM_THREADS')) {
 $safe[$name]=[Environment]::GetEnvironmentVariable($name)
}
@{at=(Get-Date).ToUniversalTime().ToString('o');release=$release;data=($data | ConvertFrom-Json);row_hashes=$hashes;safe_config=$safe;java=(Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health');python=(Invoke-RestMethod 'http://127.0.0.1:18081/health');services=@(Get-Service ETFQuantBackend,ETFQuantEngine,ETFPostgreSQL | Select-Object Name,@{n='Status';e={[string]$_.Status}});processes=@(Get-Process java,python -ErrorAction SilentlyContinue | Select-Object ProcessName,CPU,WorkingSet64)} | ConvertTo-Json -Depth 40 -Compress
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
"""
    # Windows命令行有长度上限，拆成三个互不依赖的只读查询，不上传脚本。
    setup = script[: script.index("$sql=@'")]
    first = setup + script[script.index("$sql=@'") : script.index("$hashes=@{}")]
    first += "@{data=($data | ConvertFrom-Json)} | ConvertTo-Json -Depth 40 -Compress\n} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}"
    second = (
        setup
        + script[script.index("$hashes=@{}") : script.index("$release=Get-Content")]
    )
    second += "@{row_hashes=$hashes} | ConvertTo-Json -Depth 8 -Compress\n} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}"
    third = setup + script[script.index("$release=Get-Content") :]
    third = third.replace("data=($data | ConvertFrom-Json);row_hashes=$hashes;", "")
    result = {}
    with connect("example.invalid", "etfdeploy") as client:
        for index, part in enumerate((first, second, third)):
            result.update(
                json.loads(
                    remote(
                        client, part, f"gold-readonly-audit-{index}.log", timeout=120
                    )
                )
            )
    result["row_hashes"] = normalize_hashes(result["row_hashes"])
    return result


def normalize_hashes(tables):
    """Windows PowerShell 5会把数组包装成value/Count，显式解包后再比较。"""
    return {
        name: rows.get("value", []) if isinstance(rows, dict) else rows
        for name, rows in tables.items()
    }


def public_snapshot():
    """复用一次HTTPS会话读取业务接口；不提交采集、训练、日终或付费任务。"""
    config = json.loads((ROOT / ".runtime/server-secrets.json").read_text())
    base = "https://etf.example.invalid"
    with requests.Session() as client:
        csrf = client.get(base + "/api/auth/csrf", timeout=30).json()
        login = client.post(
            base + "/api/auth/login",
            data={"username": "admin", "password": config["admin_password"]},
            headers={csrf["header"]: csrf["token"]},
            timeout=30,
        )
        if login.status_code != 204:
            return {"login_status": login.status_code}
        result = {}
        for path in ["/api/system", "/api/tasks", "/api/forward", "/api/gold/overview"]:
            response = client.get(base + path, timeout=30)
            result[path] = {"status": response.status_code}
            if response.status_code == 200:
                result[path]["body"] = response.json()
        return result


def main():
    """保存基线或增量证据，比较原有行时允许正常业务新增，单独报告原行变化。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--before", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("证据文件已存在")
    result = snapshot()
    result["public"] = public_snapshot()
    if args.before:
        before = json.loads(args.before.read_text())
        result["comparison"] = {}
        for table, old in normalize_hashes(before["row_hashes"]).items():
            current = {row["key"]: row["hash"] for row in result["row_hashes"][table]}
            result["comparison"][table] = {
                "original": len(old),
                "now": len(current),
                "changed_or_missing": [
                    row["key"] for row in old if current.get(row["key"]) != row["hash"]
                ],
            }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(
        json.dumps(
            {
                "at": result["at"],
                "release": result["release"],
                "migrations": result["data"]["migrations"],
                "java": result["java"],
                "python": result["python"],
                "counts": {
                    key: len(value) for key, value in result["row_hashes"].items()
                },
                "comparison": result.get("comparison"),
                "evidence_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
