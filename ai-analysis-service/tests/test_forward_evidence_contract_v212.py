"""证据ID协议隔离测试；真实失败仅离线重放，手工Mock绝不变更历史分析。"""

import copy
import hashlib
import json
from datetime import timedelta
from pathlib import Path

import httpx
import pytest
from etf_ai import forward
from test_forward_news import request, response, settings


def payload(req, status="AVAILABLE", risk="medium"):
    """从本次报价目录手工选择合法证据，明确仅作为隔离协议夹具。"""
    quote = forward.quote_forward(req, settings())
    rows = []
    for symbol in req.symbols:
        allowed = quote["relevance"]["by_symbol"][symbol]
        chosen_status = status if allowed else "NO_RELEVANT_NEWS"
        has_sources = chosen_status != "NO_RELEVANT_NEWS"
        source_ids = allowed[:1] if has_sources else []
        evidence_ids = (
            [
                next(
                    row["id"]
                    for row in quote["evidence_catalog"]
                    if row["source_id"] == source_ids[0]
                )
            ]
            if source_ids
            else []
        )
        rows.append(
            {
                "symbol": symbol,
                "horizon": 1,
                "status": chosen_status,
                "relevant_source_ids": source_ids,
                "sentiment_score": 0.0 if chosen_status == "AVAILABLE" else None,
                "risk_level": risk if chosen_status == "AVAILABLE" else None,
                "rationale": "人工构造的隔离协议样本，不是模型判断。",
                "relevance_level": "medium" if has_sources else "none",
                "direction": "neutral" if chosen_status == "AVAILABLE" else "unknown",
                "evidence_ids": evidence_ids,
                "impact_logic": "仅验证来源和状态合约，不产生交易结论。",
                "uncertainty": "不证明真实h1信号有效。",
            }
        )
    return {"items": rows}


def validate(req, result):
    """使用最终预算选择及冻结快照验证，不向模型发送任何内容。"""
    selected, audit = forward.select_forward_news(req)
    return forward.validate_batch(
        json.dumps(result, ensure_ascii=False), selected, audit
    )


def codes(error):
    """提取标准化错误类型，避免测试依赖不相关的文案格式。"""
    return {item["code"] for item in error.value.errors}


def test_real_run_without_frozen_quote_identity_is_rejected_before_provider(
    monkeypatch,
):
    """正式run即使两项expected都被省略也不得绕过报价冻结；从不建立外部连接。"""
    req = request(nature="REAL")
    monkeypatch.setattr(
        httpx, "Client", lambda *a, **k: pytest.fail("不得创建供应商连接")
    )
    result = forward.analyze_forward(req, settings())
    assert result["error_code"] == "INPUT_SNAPSHOT_MISMATCH"
    assert result["attempts"] == 0 and result["cost_usd"] == "0"


@pytest.mark.parametrize(
    "kind", ["unknown", "duplicate", "wrong_snapshot", "wrong_source"]
)
def test_invalid_evidence_ids_are_rejected(kind):
    """伪造、重复、跨请求或未关联来源的证据均不能通过，不改写引用ID。"""
    req = request()
    result = payload(req)
    row = result["items"][0]
    if kind == "unknown":
        row["evidence_ids"] = ["EV_" + "f" * 64]
    elif kind == "duplicate":
        row["evidence_ids"] *= 2
    elif kind == "wrong_snapshot":
        other = copy.deepcopy(req)
        other.news[0].summary += "另一请求中的新事实。"
        row["evidence_ids"] = payload(other)["items"][0]["evidence_ids"]
    else:
        row["relevant_source_ids"] = ["not-the-evidence-source"]
    before = copy.deepcopy(result)
    with pytest.raises(forward.InvalidOutput) as caught:
        validate(req, result)
    expected = (
        "INVALID_STRUCTURE"
        if kind == "duplicate"
        else "EVIDENCE_SOURCE_NOT_ALLOWED"
        if kind == "wrong_source"
        else "UNKNOWN_EVIDENCE_ID"
    )
    assert expected in codes(caught)
    assert result == before


def test_evidence_cannot_be_reused_across_etf_eligibility():
    """创业板事件即使ID存在，也不能作为沪深300的相关证据。"""
    req = request("创业板指数调整公告")
    result = payload(req)
    assert validate(req, result)
    only = next(row for row in result["items"] if row["symbol"] == "159915")
    target = next(row for row in result["items"] if row["symbol"] == "510300")
    target.update({**only, "symbol": "510300"})
    with pytest.raises(forward.InvalidOutput) as caught:
        validate(req, result)
    assert "EVIDENCE_SOURCE_NOT_ALLOWED" in codes(caught)


@pytest.mark.parametrize("kind", ["text", "hash", "future_publication", "future_fetch"])
def test_modified_or_future_frozen_input_is_rejected(kind):
    """拿旧目录验证被修改或未来的新闻副本必须失败，包括model_copy绕过Pydantic。"""
    req = request()
    result = payload(req)
    selected, audit = forward.select_forward_news(req)
    modified = copy.deepcopy(selected)
    if kind == "text":
        modified.news[0].summary += "变更的原文。"
    elif kind == "hash":
        modified.news[0].content_sha256 = "b" * 64
    elif kind == "future_publication":
        modified.news[0].published_at = req.cutoff + timedelta(seconds=1)
    else:
        modified.news[0].fetched_at = req.cutoff + timedelta(seconds=1)
    with pytest.raises(forward.InvalidOutput) as caught:
        forward.validate_batch(json.dumps(result), modified, audit)
    assert (
        "INVALID_FROZEN_INPUT"
        if kind.startswith("future")
        else "EVIDENCE_SNAPSHOT_MISMATCH"
    ) in codes(caught)


@pytest.mark.parametrize("status", ["NO_RELEVANT_NEWS", "INSUFFICIENT_DATA"])
def test_missing_states_have_no_trading_score(status):
    """无相关新闻与信息不足都可结构通过，但分数风险为空且方向unknown，不能据此融合。"""
    req = request()
    result = payload(req, status=status)
    checked = validate(req, result)
    assert all(
        item.sentiment_score is None
        and item.risk_level is None
        and item.direction == "unknown"
        for item in checked.items
    )
    if status == "NO_RELEVANT_NEWS":
        assert all(
            not item.evidence_ids and not item.relevant_source_ids
            for item in checked.items
        )


@pytest.mark.parametrize("field", ["evidence_ids", "relevant_source_ids"])
def test_no_relevant_news_cannot_carry_citations(field):
    """语义无关不能同时携带来源或证据，避免误解为空分数的可融合新闻。"""
    req = request()
    valid = payload(req)
    result = payload(req, status="NO_RELEVANT_NEWS")
    result["items"][0][field] = valid["items"][0][field]
    with pytest.raises(forward.InvalidOutput):
        validate(req, result)


def test_available_zero_and_high_remain_valid_in_isolated_transport():
    """合法中性零分与high风险仍成功；不会为了提高成功率把高风险当失败。"""
    req, config = request(), settings()
    result = payload(req, risk="high")
    quote = forward.quote_forward(req, config)
    req.reserved_usd = quote["reserved_usd"]
    response_data = forward.analyze_forward(
        req, config, httpx.MockTransport(lambda _: response(result))
    )
    assert response_data["status"] == "SUCCEEDED" and response_data["nature"] == "MOCK"
    assert all(
        row["sentiment_score"] == 0 and row["risk_level"] == "high"
        for row in response_data["result"]["items"]
    )


@pytest.mark.parametrize(
    "field", ["expected_input_sha256", "expected_evidence_snapshot_id", "missing_peer"]
)
def test_mismatched_expected_identity_never_calls_provider(field):
    """报价身份不一致或只传一个期望身份时，在任何供应商请求前结束且零费用。"""
    req, config, calls = request(), settings(), []
    quote = forward.quote_forward(req, config)
    req.reserved_usd = quote["reserved_usd"]
    req.expected_input_sha256 = quote["input_sha256"]
    req.expected_evidence_snapshot_id = quote["evidence_snapshot_id"]
    if field == "missing_peer":
        req.expected_evidence_snapshot_id = None
    else:
        setattr(req, field, "0" * 64)

    def transport(http_request):
        calls.append(http_request)
        raise AssertionError("身份不符禁止发出模型调用")

    result = forward.analyze_forward(req, config, httpx.MockTransport(transport))
    assert (
        result["status"] == "FAILED"
        and result["error_code"] == "INPUT_SNAPSHOT_MISMATCH"
    )
    assert result["attempts"] == result["input_tokens"] == result["output_tokens"] == 0
    assert result["cost_usd"] == "0" and not calls


def test_exact_expected_identity_matches_actual_wire_and_catalog():
    """模型实际收到唯一证据表，不重复正文，目录ID和精确请求哈希一致。"""
    req, config, calls = request(), settings(), []
    req.news[0].metadata = {
        "content": "货币政策公布了可核实事实。",
        "content_type": "FULL_TEXT",
    }
    expected = forward.quote_forward(req, config)
    req.reserved_usd = expected["reserved_usd"]
    req.expected_input_sha256 = expected["input_sha256"]
    req.expected_evidence_snapshot_id = expected["evidence_snapshot_id"]
    output = payload(req, status="INSUFFICIENT_DATA")

    def transport(http_request):
        actual = json.loads(http_request.content)["messages"]
        calls.append(actual)
        return response(output)

    result = forward.analyze_forward(req, config, httpx.MockTransport(transport))
    assert result["status"] == "SUCCEEDED" and len(calls) == 1
    assert calls[0] == json.loads(expected["input_text"])
    wire = json.loads(calls[0][1]["content"])
    assert {item["id"] for item in wire["evidence"]} == {
        item["id"] for item in expected["evidence_catalog"]
    }
    assert all(
        not ({"title", "summary", "content"} & item.keys()) for item in wire["news"]
    )
    assert (
        expected["input_budget_breakdown"]["total_bytes"]
        == expected["input_token_upper_bound"]
        <= 24000
    )


@pytest.mark.parametrize("attempt", [0, 1])
def test_two_real_v211_failures_remain_failures_without_rewriting(attempt):
    """真实旧输出原文逐字保留；新协议不自动转译facts为证据或修正旧响应。"""
    saved = json.loads(
        (Path(__file__).parent / "fixtures/forward_v211_real_failure.json").read_text()
    )
    req = forward.ForwardAnalysisRequest(**saved["request"])
    raw = saved["attempts"][attempt]["raw_output"]
    assert (
        hashlib.sha256(raw.encode()).hexdigest()
        == saved["attempts"][attempt]["raw_output_sha256"]
    )
    selected, audit = forward.select_forward_news(req)
    with pytest.raises(forward.InvalidOutput):
        forward.validate_batch(raw, selected, audit)
    # 历史摘录协议继续独立拒绝这两条真实失败；不因新机制掩盖旧校验错误。
    with pytest.raises(forward.InvalidOutput):
        forward.validate_legacy_batch(raw, selected, audit)
    assert saved["attempts"][attempt]["raw_output"] == raw


def test_saved_sources_can_support_explicit_manual_mock_insufficient_example():
    """另建手工Mock夹具证明新结构可通过，不更新历史失败或暗示模型已真实成功。"""
    saved = json.loads(
        (Path(__file__).parent / "fixtures/forward_v211_real_failure.json").read_text()
    )
    before = copy.deepcopy(saved)
    req = forward.ForwardAnalysisRequest(**saved["request"])
    for item in req.news:
        item.nature = "MOCK"
    config = settings()
    req.reserved_usd = forward.quote_forward(req, config)["reserved_usd"]
    manual = payload(req, status="INSUFFICIENT_DATA")
    result = forward.analyze_forward(
        req, config, httpx.MockTransport(lambda _: response(manual))
    )
    assert result["status"] == "SUCCEEDED" and result["nature"] == "MOCK"
    assert all(
        item["status"] == "INSUFFICIENT_DATA" and item["sentiment_score"] is None
        for item in result["result"]["items"]
    )
    assert saved == before
