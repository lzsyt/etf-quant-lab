"""V2.1.1提示词预算：只消除重复输入，验证归档不变和字节拆分精确。"""

import json

import pytest
from etf_ai.forward import compact_news_payload, input_budget_breakdown, quote_forward
from test_forward_news import request, settings


def test_duplicate_summary_omission_keeps_stored_snapshot_and_evidence():
    """规范化前缀重复的摘要可省略，但标题、实际正文与归档摘要都不改变。"""
    req = request()
    item = req.news[0]
    item.summary = "GDP 公布\n统计结果。"
    item.metadata = {
        "content": "GDP公布统计结果。后续事实未改动。",
        "content_type": "FULL_TEXT",
    }
    original = item.model_dump(mode="json")
    payload = compact_news_payload(item)
    assert payload["summary"] == ""
    assert payload["title"] == item.title
    assert payload["content"] == item.metadata["content"]
    assert payload["content_type"] == "FULL_TEXT"
    assert item.model_dump(mode="json") == original


@pytest.mark.parametrize(
    "content", ["", "内容不同，摘要含有新增事实。", "前面事实。独立摘要。"]
)
def test_nonduplicated_or_summary_only_input_is_kept(content):
    """无正文、不同事实或只在正文中段出现的摘要不能被误删。"""
    item = request().news[0]
    item.summary = "独立摘要。"
    item.metadata = {
        "content": content,
        "content_type": "SUMMARY" if not content else "FULL_TEXT",
    }
    payload = compact_news_payload(item)
    assert payload["summary"] == item.summary
    assert payload["content"] == content


def test_quote_explains_omission_and_keeps_model_content_bound():
    """只对实际1600字正文副本省略重复摘要，并保存可见说明和精确预算。"""
    req = request("货币政策官方数据")
    item = req.news[0]
    item.summary = "货币政策数据公布。" * 20
    item.metadata = {
        "content": item.summary + "后续真实材料。" * 400,
        "content_type": "FULL_TEXT",
    }
    original = req.model_dump(mode="json")
    quote = quote_forward(req, settings())
    messages = json.loads(quote["input_text"])
    payload = json.loads(messages[1]["content"])
    assert any(
        d["reason"] == "DUPLICATE_OF_CONTENT_PREFIX"
        for d in quote["relevance"]["evidence_diagnostics"]
    )
    assert "summary" not in payload["news"][0]
    assert (
        sum(len(e["text"]) for e in payload["evidence"] if e["field"] == "content")
        <= 1600
    )
    assert payload["news"][0]["content_type"] == "EXCERPT"
    assert req.model_dump(mode="json") == original
    budget = quote["input_budget_breakdown"]
    assert budget["total_bytes"] == quote["input_token_upper_bound"] <= 24000
    assert sum(budget["components_bytes"].values()) == budget["total_bytes"]
    assert budget["components_bytes"]["evidence_text"] > 0
    assert budget["components_bytes"]["news_content"] == 0
    assert messages[1]["content"] == json.dumps(
        payload, ensure_ascii=False, separators=(",", ":")
    )


@pytest.mark.parametrize("compact", [False, True])
def test_budget_breakdown_is_exact_for_unicode_and_escaped_content(compact):
    """中文、多字节符号与JSON转义均按UTF8计数，兼容冻结旧格式和新版紧凑格式。"""
    payload = {
        "cutoff": "2026-10-25T03:35:28Z",
        "symbols": ["510300", "510500"],
        "summary_policy": "EMPTY_WHEN_DUPLICATED",
        "eligible_source_ids_by_symbol": {"510300": ["来源甲"], "510500": ["来源甲"]},
        "news": [
            {
                "id": "来源甲",
                "event_id": "事件甲",
                "title": "标题“统计”",
                "summary": "",
                "content": '中文\n原文含"引号"、反斜线\\及符号📊',
                "content_type": "EXCERPT",
                "source_url": "https://www.stats.gov.cn/公开.html",
            }
        ],
    }
    options = {"ensure_ascii": False}
    if compact:
        options["separators"] = (",", ":")
    system = '中文说明。结构规则：{"type":"object","title":"输出"}'
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": json.dumps(payload, **options)},
    ]
    result = input_budget_breakdown(messages)
    components = result["components_bytes"]
    assert components["system_instructions"] == len("中文说明。结构规则：".encode())
    assert components["output_schema"] == len(
        '{"type":"object","title":"输出"}'.encode()
    )
    assert result["unit"] == "UTF8_BYTES"
    assert result["basis"] == "CONSERVATIVE_INPUT_BOUND_NOT_PROVIDER_TOKENS"
    assert result["message_content_bytes"] == sum(
        len(message["content"].encode()) for message in messages
    )
    assert (
        sum(components.values())
        == result["total_bytes"]
        == result["message_content_bytes"] + 1024
    )
    assert all(value >= 0 for value in components.values())
    assert components["transport_reserve"] == 1024


def test_budget_breakdown_without_schema_does_not_invent_schema_bytes():
    """没有结构分隔标记的历史系统文本全部归指令，空新闻仍可精确拆分。"""
    messages = [
        {"role": "system", "content": "只有指令"},
        {"role": "user", "content": '{"news":[]}'},
    ]
    result = input_budget_breakdown(messages)
    assert result["components_bytes"]["output_schema"] == 0
    assert result["summary_omitted_count"] == 0
    assert sum(result["components_bytes"].values()) == result["total_bytes"]
