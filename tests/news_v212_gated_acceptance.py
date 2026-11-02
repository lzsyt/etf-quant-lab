"""V2.1.2日额度已耗尽时的零收费正式流程验收；默认不执行、绝不申请版本付费重试。"""

import argparse
import json
import time
import uuid
from pathlib import Path

from v11_resilience import BASE, authenticated, health, healthy, query


def gates():
    """仅读取当前北京日收费批次，必须已触发普通日门禁才允许提交只报价任务。"""
    return query("""SELECT jsonb_build_object(
        'date',(now() at time zone 'Asia/Shanghai')::date,
        'migration',(select max(version::integer) from flyway_schema_history where success),
        'pending',(select count(*) from task_execution where status in ('QUEUED','RUNNING')),
        'paid_batches',(select count(*) from forward_analysis a join api_call_cost c on a.id=c.id
            where (a.input_cutoff at time zone 'Asia/Shanghai')::date=(now() at time zone 'Asia/Shanghai')::date),
        'validation_batches',(select count(*) from forward_analysis a join api_call_cost c on a.id=c.id
            where (a.input_cutoff at time zone 'Asia/Shanghai')::date=(now() at time zone 'Asia/Shanghai')::date
            and a.quote->>'validation_mode'='EXPLICIT_VERSION_VALIDATION'));
        """, "v212-zero-fee-gates")


def cost_digest():
    """费用全表整行摘要用于证明门禁验收没有创建、更新或删改历史费用。"""
    return query("""SELECT jsonb_build_object('count',count(*),
        'digest',md5(coalesce(string_agg(row_to_json(c)::text,'' order by id),'')))
        FROM api_call_cost c;""", "v212-zero-fee-cost")


def main():
    """只提交一个普通NEWS_ANALYZE任务，再重复相同业务键验证任务幂等；不训练、不生成决策。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--release")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.execute:
        print("默认不执行。仅已有部署授权且日额度已耗尽时，指定 --execute --release --output 验收零收费门禁。")
        return
    if not args.release or not args.output or not args.output.is_dir():
        raise ValueError("必须指定准确版本和现有证据目录")
    marker = args.output / "gated-submission.json"
    if marker.exists():
        raise RuntimeError("已提交或尝试提交；只查询原任务，不创建另一任务")
    checked = health("v212-before-gated-task")
    if not healthy(checked) or checked["release"] != args.release or checked["python"]["version"] != "2.1.2":
        raise RuntimeError("运行版本或健康不符合本轮预期")
    state = gates()
    if state["migration"] != 11 or state["pending"] or state["paid_batches"] < 2 or state["validation_batches"] < 1:
        raise RuntimeError("日额度尚未耗尽或队列非空；禁止此脚本可能发起付费")
    before = cost_digest()
    business_key = "news-v212-zero-fee-evidence-" + state["date"]
    body = {"type": "NEWS_ANALYZE", "payload": {}, "business_key": business_key}
    marker.write_text(json.dumps({"state": "SUBMITTING", "business_key": business_key, "gates": state}))
    with authenticated() as client:
        submitted = client.post(BASE + "/api/tasks", json=body, timeout=30)
        submitted.raise_for_status()
        task_id = submitted.json()["id"]
        marker.write_text(json.dumps({"task_id": task_id, "business_key": business_key, "gates": state}))
        for _ in range(30):
            response = client.get(BASE + "/api/tasks/" + task_id, timeout=15)
            response.raise_for_status()
            task = response.json()
            if task["status"] in ("SUCCEEDED", "FAILED"):
                break
            time.sleep(1)
        else:
            raise TimeoutError("只可后续查询原task，不重新提交")
        again = client.post(BASE + "/api/tasks", json=body, timeout=30)
        again.raise_for_status()
        assert again.json()["id"] == task_id
    args.output.joinpath("gated-task.json").write_text(json.dumps(task, ensure_ascii=False, indent=2))
    analysis_id = str(uuid.UUID(task["result"]["analysis"]["id"]))
    analysis = query(f"SELECT row_to_json(a) FROM forward_analysis a WHERE id='{analysis_id}';", "v212-gated-analysis")
    args.output.joinpath("gated-analysis.json").write_text(json.dumps(analysis, ensure_ascii=False, indent=2))
    assert analysis["status"] == "MISSING"
    assert analysis["error_code"] == "DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED"
    quote = analysis["quote"]
    assert quote["schema_version"] == "etf-symbol-evidence-v2.1.2"
    assert quote["prompt_version"] == "etf-symbol-h1-v2.1.3"
    assert quote["evidence_version"] == "news-evidence-v1" and quote["evidence_catalog"]
    assert before == cost_digest()
    summary = {"task_id": task_id, "analysis_id": analysis_id,
               "task_status": task["status"], "analysis_status": analysis["status"],
               "validation_status": "WAITING_REAL_VALIDATION", "error_code": analysis["error_code"],
               "news_count": len(quote["eligible_source_ids"]), "evidence_count": len(quote["evidence_catalog"]),
               "conservative_bytes": quote["input_token_upper_bound"], "cost_ledger_unchanged": True,
               "paid_api_calls": 0, "input_tokens": 0, "output_tokens": 0, "estimated_usd": "0",
               "same_task_reused": True, "new_decisions_generated": False}
    args.output.joinpath("gated-acceptance.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
