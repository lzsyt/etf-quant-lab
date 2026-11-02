"""显式授权后仅提交一个版本验证任务；默认不联网，不生成交易决策。"""

import argparse
import json
from pathlib import Path
import time

from v11_resilience import BASE, authenticated, health, powershell, query


def preflight(release, output):
    """在目标服务器只报价，不调用模型；凭据只从既有受保护运行环境加载。"""
    current = health("news-v211-before-validation")
    if current["release"] != release:
        raise RuntimeError("运行版本与本轮预期不符")
    quote = json.loads(powershell(r"""
. 'C:\ETFQuantLab\app\common.ps1'
Read-Environment 'C:\ETFQuantLab'
$env:PGPASSWORD=$env:ETF_DB_PASSWORD
$sql=@'
BEGIN READ ONLY;
SELECT jsonb_build_object('cutoff',now(),'symbols',jsonb_build_array('510300','510500','159915'),
'horizon',1,'news',(select coalesce(jsonb_agg(x.article),'[]'::jsonb) from (
select to_jsonb(n)||jsonb_build_object('metadata',coalesce(m.details,'{}'::jsonb)) article
from news_item n left join news_item_metadata m on m.news_id=n.id
where n.nature='REAL' and n.publication_verified and n.published_at<=now()
and n.fetched_at<=now() and n.published_at>=now()-interval '7 days'
order by n.published_at desc,n.id limit 150) x));
COMMIT;
'@
try {
 $payload=($sql | & 'C:\ETFQuantLab\runtime\pgsql\bin\psql.exe' -X -q -t -A -h 127.0.0.1 -p 55432 -U etf_app -d etf_quant_lab -v ON_ERROR_STOP=1) -join ''
 if($LASTEXITCODE -ne 0){throw 'Read-only quote input failed'}
 $quote=Invoke-RestMethod 'http://127.0.0.1:18081/analysis/forward/quote' -Method Post -ContentType 'application/json' -Headers @{'X-Internal-Token'=$env:ETF_INTERNAL_TOKEN} -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 30
 @{quote=$quote;daily_usd=$env:ETF_LLM_DAILY_USD;forward_llm_enabled=$env:ETF_FORWARD_LLM_ENABLED;scheduling_enabled=$env:ETF_SCHEDULING_ENABLED} | ConvertTo-Json -Depth 100 -Compress
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
""", "news-v211-preflight-quote", timeout=60))
    state = query("""SELECT jsonb_build_object(
      'date',(now() at time zone 'Asia/Shanghai')::date,
      'migration',(select max(version::integer) from flyway_schema_history where success),
      'pending_tasks',(select count(*) from task_execution where status in ('RUNNING','QUEUED')),
      'cost_rows',(select count(*) from api_call_cost),
      'validation_batches',(select count(*) from forward_analysis a join api_call_cost c on a.id=c.id
         where a.input_cutoff>=date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai'
         and a.quote->>'validation_mode'='EXPLICIT_VERSION_VALIDATION'));
    """, "news-v211-validation-preflight")
    output.joinpath("real-preflight.json").write_text(json.dumps(
        {"health": current, "quote": quote, "state": state}, ensure_ascii=False, indent=2))
    value = quote["quote"]
    assert state["migration"] == 11 and state["pending_tasks"] == 0
    assert state["validation_batches"] == 0, "已有显式验证，不追加新批次"
    assert quote["daily_usd"] == "1.00" and quote["forward_llm_enabled"] == "true"
    assert value["enabled"] and value["configured"] and value["eligible_source_ids"]
    assert value["prompt_version"] == "etf-symbol-h1-v2.1.2"
    assert value["schema_version"] == "etf-symbol-events-v2.1.1"
    assert value["pricing"]["attempts"] == 2 and value["pricing"]["max_output_tokens"] == 1400
    assert value["input_token_upper_bound"] <= 24000
    return state


def main():
    """一次提交后只轮询原任务；终态失败也不更换key、不循环调用模型。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--release")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.execute:
        print("默认不执行。专项授权后指定 --execute --release <运行版本> --output <证据目录>。")
        return
    if not args.release or not args.output or not args.output.is_dir():
        raise ValueError("必须提供准确版本和本轮证据目录")
    marker = args.output / "real-submission.json"
    if marker.exists():
        raise RuntimeError("已经提交或尝试提交本轮验证；只读检查原任务，不自动再次执行")
    before = preflight(args.release, args.output)
    business_key = "news-v211-explicit-validation-" + before["date"]
    marker.write_text(json.dumps({"business_key": business_key, "state": "SUBMITTING"}))
    with authenticated() as client:
        submitted = client.post(BASE + "/api/tasks", json={
            "type": "NEWS_ANALYZE", "payload": {"validate_version": True},
            "business_key": business_key,
        }, timeout=30)
        submitted.raise_for_status()
        task_id = submitted.json()["id"]
        marker.write_text(json.dumps({"business_key": business_key, "task_id": task_id}))
        for _ in range(120):
            response = client.get(BASE + "/api/tasks/" + task_id, timeout=30)
            response.raise_for_status()
            task = response.json()
            if task["status"] in ("SUCCEEDED", "FAILED"):
                break
            time.sleep(1)
        else:
            raise TimeoutError("任务尚未终结，仅允许继续查询原task_id")
    args.output.joinpath("real-validation-task.json").write_text(
        json.dumps(task, ensure_ascii=False, indent=2))
    analysis_id = task.get("result", {}).get("analysis", {}).get("id")
    if not analysis_id:
        raise RuntimeError("任务未返回分析，保留证据且不自动重试")
    # ID由已鉴权接口返回，但仍用UUID解析后才嵌入只读查询。
    import uuid
    analysis_id = str(uuid.UUID(analysis_id))
    analysis = query(f"SELECT row_to_json(a) FROM forward_analysis a WHERE id='{analysis_id}';",
                     "news-v211-real-analysis")
    args.output.joinpath("real-analysis.json").write_text(json.dumps(analysis, ensure_ascii=False, indent=2))
    cost = query(f"SELECT coalesce((SELECT to_jsonb(c) FROM api_call_cost c WHERE id='{analysis_id}'),'null'::jsonb);",
                 "news-v211-real-cost")
    args.output.joinpath("real-cost.json").write_text(json.dumps(cost, ensure_ascii=False, indent=2))
    print(json.dumps({"analysis_id": analysis_id, "status": analysis["status"],
                      "error_code": analysis.get("error_code"), "cost": cost,
                      "items": (analysis.get("result") or {}).get("result", {}).get("items", []),
                      "decisions_generated": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
