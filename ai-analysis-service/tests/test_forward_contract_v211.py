"""V2.1.1闭环：真实失败只离线重放，全部模型调用使用隔离Mock。"""

import copy
import json
from datetime import timedelta
from pathlib import Path

import httpx
import pytest
from etf_ai import forward
from test_forward_news import batch, request, response, run, settings


@pytest.mark.parametrize("attempt,conflicts,bad_quotes", [(0, 2, 2), (1, 1, 0)])
def test_saved_real_failures_still_rejected_with_all_reasons(
    attempt, conflicts, bad_quotes
):
    """保存的失败原文不改分数、不补引用；新代码仍拒绝，并同时记录曾被遮住的事实错误。"""
    saved = json.loads(
        (Path(__file__).parent / "fixtures/forward_v21_real_failure.json").read_text()
    )
    req = forward.ForwardAnalysisRequest(**saved["request"])
    selected, audit = forward.select_forward_news(req)
    raw = saved["attempts"][attempt]["raw_output"]
    with pytest.raises(forward.InvalidOutput) as caught:
        forward.validate_legacy_batch(raw, selected, audit)
    codes = [item["code"] for item in caught.value.errors]
    assert codes.count("DIRECTION_SCORE_CONFLICT") == conflicts
    assert codes.count("QUOTE_NOT_CONTIGUOUS_INPUT_TEXT") == bad_quotes
    assert saved["attempts"][attempt]["raw_output"] == raw


@pytest.mark.parametrize(
    "direction,score",
    [
        ("positive", 0.01),
        ("positive", 1.0),
        ("negative", -0.01),
        ("negative", -1.0),
        ("neutral", 0.0),
        ("mixed", -0.2),
        ("mixed", 0.3),
    ],
)
def test_direction_boundaries_keep_valid_high_risk(direction, score):
    """看多/看空用positive/negative；high不影响结构成功，留给原交易否决规则。"""
    req = request()
    payload = batch(req, score, "high")
    for item in payload["items"]:
        item["direction"] = direction
    result = run(req, payload)
    assert result["status"] == "SUCCEEDED"
    assert all(item["risk_level"] == "high" for item in result["result"]["items"])


@pytest.mark.parametrize(
    "direction,score",
    [
        ("neutral", 0.01),
        ("neutral", -0.01),
        ("positive", 0.0),
        ("negative", 0.0),
        ("positive", -0.1),
        ("negative", 0.1),
        ("bullish", 0.1),
        ("bearish", -0.1),
        ("unknown", 0.0),
    ],
)
def test_invalid_direction_never_coerced(direction, score):
    """非法值保留原文，不通过转换别名或修改分数伪装通过。"""
    req = request()
    payload = batch(req, score)
    payload["items"][0]["direction"] = direction
    result = run(req, payload)
    assert result["status"] == "FAILED"
    original = json.loads(result["raw_output"])["items"][0]
    assert original["direction"] == direction and original["sentiment_score"] == score
    assert result["attempt_evidence"][0]["validation_errors"]


def test_all_etfs_can_legitimately_report_insufficient_without_scores():
    """相关事实无法支持h1时，结构成功不等于有效情绪，不强迫有效打分。"""
    req = request()
    payload = batch(req)
    for item in payload["items"]:
        item.update(
            status="INSUFFICIENT_DATA",
            sentiment_score=None,
            risk_level=None,
            direction="unknown",
        )
    result = run(req, payload)
    assert result["status"] == "SUCCEEDED"
    assert all(item["sentiment_score"] is None for item in result["result"]["items"])


@pytest.mark.parametrize(
    "quote,valid",
    [
        ("货币政策公开测试", True),
        ("货币\u00a0政策　公开\n测试", True),
        ("测试隔离测试摘要", False),
        ("货币政策…公开测试", False),
        ("货币政策公开測試", False),
        ("缺失的事实", False),
    ],
)
def test_quote_whitespace_unicode_and_field_boundaries(quote, valid):
    """仅兼容既有空白大小写差异，不折叠不同Unicode字符，不跨字段拼接或添加省略号。"""
    req = request()
    payload = batch(req)
    for item in payload["items"]:
        item.pop("evidence_ids")
        item["facts"] = [{"source_id": req.news[0].id, "quote": req.news[0].title}]
    payload["items"][0]["facts"][0]["quote"] = quote
    selected, audit = forward.select_forward_news(req)
    if valid:
        forward.validate_legacy_batch(json.dumps(payload), selected, audit)
    else:
        with pytest.raises(forward.InvalidOutput):
            forward.validate_legacy_batch(json.dumps(payload), selected, audit)


def test_untransmitted_truncated_tail_cannot_be_quoted():
    """存档存在但未发送的尾部不能成为事实；引用只能来自实际EXCERPT。"""
    req = request()
    req.news[0].metadata = {
        "content": "财政政策" * 450 + "未传输的独有事实",
        "content_type": "FULL_TEXT",
    }
    payload = batch(req)
    payload["items"][0]["facts"] = [
        {"source_id": req.news[0].id, "quote": "未传输的独有事实"}
    ]
    result = run(req, payload)
    assert result["status"] == "FAILED"
    sent = json.loads(json.loads(result["input_text"])[1]["content"])
    content = [e for e in sent["evidence"] if e["field"] == "content"]
    assert all(
        e["content_type"] == "EXCERPT" and len(e["text"]) <= 1600 for e in content
    )
    assert all("未传输的独有事实" not in e["text"] for e in content)


def test_stable_identity_keeps_exact_sent_time_separate(monkeypatch):
    """同日任务时间变化不触发新请求；内容、模型及协议实质变化各自生成新身份。"""
    req, config = request(), settings()
    first = forward.quote_forward(req, config)
    later = req.model_copy(update={"cutoff": req.cutoff + timedelta(minutes=1)})
    second = forward.quote_forward(later, config)
    assert first["request_fingerprint"] == second["request_fingerprint"]
    assert first["input_sha256"] != second["input_sha256"]
    changed = copy.deepcopy(req)
    changed.news[0].summary += "新增事实"
    assert (
        forward.quote_forward(changed, config)["request_fingerprint"]
        != first["request_fingerprint"]
    )
    changed = copy.deepcopy(req)
    changed.news[0].content_sha256 = "b" * 64
    assert (
        forward.quote_forward(changed, config)["request_fingerprint"]
        != first["request_fingerprint"]
    )
    for field in ("model", "model_version"):
        changed_config = copy.copy(config)
        setattr(changed_config, field, "different-version")
        assert (
            forward.quote_forward(req, changed_config)["request_fingerprint"]
            != first["request_fingerprint"]
        )
    for field in ("PROMPT_VERSION", "SCHEMA_VERSION"):
        with monkeypatch.context() as patch:
            patch.setattr(forward, field, "changed-version")
            assert (
                forward.quote_forward(req, config)["request_fingerprint"]
                != first["request_fingerprint"]
            )


def test_compact_schema_retains_every_validation_constraint():
    """传输压缩只去展示title，不能丢required、枚举、边界或additionalProperties。"""

    def remove_titles(value):
        if isinstance(value, dict):
            return {k: remove_titles(v) for k, v in value.items() if k != "title"}
        if isinstance(value, list):
            return [remove_titles(v) for v in value]
        return value

    assert forward.compact_output_schema() == remove_titles(
        forward.EvidenceEventBatch.model_json_schema()
    )


def test_mock_call_preserves_identity_and_attempt_usage():
    """报价与发送身份一致，逐次Token单独保存，仍不将Mock统计当真实成功。"""
    req, config = request(), settings()
    quoted = forward.quote_forward(req, config)
    req.reserved_usd = quoted["reserved_usd"]
    result = forward.analyze_forward(
        req, config, httpx.MockTransport(lambda _: response(batch(req)))
    )
    assert result["request_fingerprint"] == quoted["request_fingerprint"]
    assert result["input_sha256"] == quoted["input_sha256"]
    assert result["attempt_evidence"][0]["input_tokens"] == 100
    assert result["nature"] == "MOCK"
