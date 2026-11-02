"""逐 ETF 新闻协议、费用与采集诊断隔离测试；不访问付费模型。"""

import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from etf_ai.forward import (
    ForwardAnalysisRequest,
    analyze_forward,
    quote_forward,
)
from etf_ai.news import parse_rss, pbc_news
from etf_ai.providers import Settings
from etf_ai.schemas import NewsItem
from pydantic import ValidationError


def request(title="货币政策公开测试", nature="MOCK"):
    """明确标记测试材料及当时可用的双时间戳。"""
    cutoff = datetime(2026, 10, 24, 12, tzinfo=UTC)
    return ForwardAnalysisRequest(
        cutoff=cutoff,
        news=[
            NewsItem(
                id="fixture-news-1",
                title=title,
                summary="隔离测试摘要，不对应真实市场判断。",
                source_url="https://www.pbc.gov.cn/test",
                source_name="TEST",
                published_at=cutoff - timedelta(hours=3),
                fetched_at=cutoff - timedelta(hours=2),
                publication_verified=True,
                content_sha256="a" * 64,
                nature=nature,
            )
        ],
    )


def settings():
    """传输始终由 MockTransport 注入，无生产密钥和外部请求。"""
    return Settings(enabled=True, api_key="isolated-test-only", rate_seconds=0)


def batch(req, score=0.0, risk="medium"):
    """构造可追溯的三品种结构化测试结果，不进入真实统计。"""
    evidence_id = quote_forward(req, settings())["evidence_catalog"][0]["id"]
    return {
        "items": [
            {
                "symbol": symbol,
                "horizon": 1,
                "status": "AVAILABLE",
                "relevant_source_ids": ["fixture-news-1"],
                "sentiment_score": score,
                "risk_level": risk,
                "rationale": symbol + " 的隔离测试影响渠道 [fixture-news-1]。",
                "relevance_level": "medium",
                "direction": "neutral",
                "evidence_ids": [evidence_id],
                "impact_logic": "隔离测试各ETF独立影响逻辑。",
                "uncertainty": "无真实短期预测有效性证据。",
            }
            for symbol in req.symbols
        ]
    }


def response(payload):
    """模拟供应商原始响应、关联 ID 和计费，用于核验记录语义。"""
    return httpx.Response(
        200,
        headers={"x-request-id": "test-request-123"},
        json={
            "id": "test-response-456",
            "model": "response-test-model",
            "usage": {"prompt_tokens": 100, "completion_tokens": 60},
            "choices": [
                {"finish_reason": "stop", "message": {"content": json.dumps(payload)}}
            ],
        },
    )


def run(req, payload):
    """先报价预留，再使用隔离传输执行业务协议。"""
    config = settings()
    req.reserved_usd = quote_forward(req, config)["reserved_usd"]
    return analyze_forward(
        req, config, httpx.MockTransport(lambda _: response(payload))
    )


def test_symbol_specific_batch_keeps_zero_and_high_valid():
    """有效零分不等于缺新闻，高风险也不是供应商失败。"""
    req = request()
    result = run(req, batch(req, score=0.0, risk="high"))
    assert result["status"] == "SUCCEEDED" and result["nature"] == "MOCK"
    assert all(
        item["status"] == "AVAILABLE" and item["risk_level"] == "high"
        for item in result["result"]["items"]
    )
    assert result["provider_request_id"] == "test-request-123"
    assert result["provider_response_id"] == "test-response-456"
    assert result["response_model"] == "response-test-model"
    assert (
        result["model_version_basis"]
        == "APPLICATION_CONFIG_LABEL_NOT_IMMUTABLE_WEIGHTS"
    )
    assert Decimal(result["cost_usd"]) == Decimal("0.000102")
    assert result["input_tokens"] == 100


def test_one_request_for_three_etfs_and_actual_summary_is_saved():
    """同次请求覆盖三 ETF，并保存真实输入文本而非声称读过正文。"""
    req, config, calls = request(), settings(), []
    req.reserved_usd = quote_forward(req, config)["reserved_usd"]

    def handle(http_request):
        body = json.loads(http_request.content)
        actual = json.loads(body["messages"][1]["content"])
        calls.append(actual)
        assert actual["horizon"] == 1 and len(actual["symbols"]) == 3
        assert actual["input_content_type"] == "SUMMARY"
        assert any(entry["text"] == req.news[0].summary for entry in actual["evidence"])
        assert "summary" not in actual["news"][0]
        assert "fetched_at" in actual["news"][0]
        assert "tools" not in body
        return response(batch(req))

    result = analyze_forward(req, config, httpx.MockTransport(handle))
    assert result["status"] == "SUCCEEDED" and len(calls) == 1
    assert json.loads(json.loads(result["input_text"])[1]["content"]) == calls[0]
    assert (
        result["relevance"]["candidate_count"]
        == result["relevance"]["accepted_count"]
        == 1
    )


def test_direct_etf_news_cannot_be_copied_to_other_symbols():
    """创业板专属来源被复制给其他 ETF 时拒绝整个非法输出。"""
    req = request("创业板指数调整公告")
    rejected = run(req, batch(req))
    assert rejected["status"] == "FAILED" and rejected["error_code"] == "INVALID_OUTPUT"
    valid = batch(req)
    for item in valid["items"]:
        if item["symbol"] != "159915":
            item.update(
                status="NO_RELEVANT_NEWS",
                relevant_source_ids=[],
                sentiment_score=None,
                risk_level=None,
                relevance_level="none",
                direction="unknown",
                evidence_ids=[],
            )
    result = run(req, valid)
    assert result["status"] == "SUCCEEDED"
    assert sum(item["status"] == "AVAILABLE" for item in result["result"]["items"]) == 1


@pytest.mark.parametrize(
    "change", ["missing_as_zero", "h5", "duplicate", "unknown_source", "nan"]
)
def test_invalid_outputs_are_explicit_failures(change):
    """缺失伪中性、错期限、重复品种、伪造来源及非数概率都不能放行。"""
    req = request()
    payload = batch(req)
    if change == "missing_as_zero":
        payload["items"][0]["status"] = "INSUFFICIENT_DATA"
    elif change == "h5":
        payload["items"][0]["horizon"] = 5
    elif change == "duplicate":
        payload["items"][1]["symbol"] = payload["items"][0]["symbol"]
    elif change == "unknown_source":
        payload["items"][0]["relevant_source_ids"] = ["invented"]
    else:
        payload["items"][0]["sentiment_score"] = float("nan")
    result = run(req, payload)
    assert result["status"] == "FAILED" and result["error_code"] == "INVALID_OUTPUT"
    assert "result" not in result
    assert result["attempts"] == 2 and len(result["attempt_evidence"]) == 2


def test_missing_and_stale_news_never_call_and_never_receive_score():
    """无相关新闻及过期新闻各有原因，无真实或测试网络调用。"""
    req = request("机关采购公告")
    denied = httpx.MockTransport(lambda _: pytest.fail("无合格新闻不能调用"))
    result = analyze_forward(req, settings(), denied)
    assert result["status"] == "MISSING" and result["error_code"] == "NO_RELEVANT_NEWS"
    assert "result" not in result and result["attempts"] == 0
    req = request()
    req.news[0].published_at = req.cutoff - timedelta(days=8)
    result = analyze_forward(req, settings(), denied)
    assert result["error_code"] == "STALE_NEWS"


def test_api_failure_and_invalid_json_preserve_failure_and_fee_evidence():
    """HTTP 200 不是成功；供应商失败仍保存关联与预算不确定性。"""
    req, config = request(), settings()
    req.reserved_usd = quote_forward(req, config)["reserved_usd"]
    result = analyze_forward(
        req,
        config,
        httpx.MockTransport(
            lambda _: httpx.Response(503, headers={"x-request-id": "test-failure"})
        ),
    )
    assert result["status"] == "FAILED" and result["error_code"] == "HTTP_503"
    assert (
        result["billing_uncertain"] and result["provider_request_id"] == "test-failure"
    )
    result = analyze_forward(
        req, config, httpx.MockTransport(lambda _: response({"wrong": 1}))
    )
    assert result["status"] == "FAILED" and result["error_code"] == "INVALID_OUTPUT"
    assert result["response_model"] == "response-test-model"
    assert result["input_tokens"] == 200


def test_timeout_is_failed_and_does_not_fabricate_analysis():
    """超时保持费用不确定，不能伪造 S=0 成功分析。"""
    req, config = request(), settings()
    req.reserved_usd = quote_forward(req, config)["reserved_usd"]

    def timeout(_):
        raise httpx.ReadTimeout("isolated timeout")

    result = analyze_forward(req, config, httpx.MockTransport(timeout))
    assert result["status"] == "FAILED" and result["error_code"] == "ReadTimeout"
    assert result["billing_uncertain"] and "result" not in result


def test_mock_cannot_be_real_and_real_cannot_enter_mock_transport():
    """通过 MockTransport 执行的成功结果也强制 MOCK，防止统计污染。"""
    with pytest.raises(ValueError, match="不能混用"):
        analyze_forward(request(nature="REAL"), Settings(mock=True))
    with pytest.raises(ValueError, match="不能混用"):
        analyze_forward(
            request(nature="REAL"),
            settings(),
            httpx.MockTransport(lambda _: response({})),
        )
    result = analyze_forward(request(), Settings(mock=True))
    assert result["nature"] == "MOCK" and result["attempts"] == 0


def test_budget_and_provider_boundary_prevent_external_call():
    """先预留完整预算，且不接受新提供商或扩大调用次数。"""
    denied = httpx.MockTransport(lambda _: pytest.fail("不得调用"))
    with pytest.raises(ValueError, match="费用"):
        analyze_forward(request(), settings(), denied)
    config = settings()
    config.base_url = "https://example.com"
    with pytest.raises(ValueError, match="官方"):
        analyze_forward(request(), config, denied)
    config.attempts = 3
    with pytest.raises(ValueError, match="重试次数"):
        quote_forward(request(), config)


def test_future_or_late_news_are_invalid_and_h5_is_not_supported():
    """发布时间与首次抓取时间都必须在截止内，不复用 h1 到 h5。"""
    data = request().model_dump()
    data["news"][0]["fetched_at"] = data["cutoff"] + timedelta(seconds=1)
    with pytest.raises(ValidationError):
        ForwardAnalysisRequest(**data)
    data = request().model_dump()
    data["horizon"] = 5
    with pytest.raises(ValidationError):
        ForwardAnalysisRequest(**data)


def test_stale_empty_sina_feed_is_distinct_from_collect_failure():
    """长期不更新的空 RSS 有可核查原因，不能只显示没有相关新闻。"""
    audit = {}
    result = parse_rss(
        b"<rss><channel><pubDate>Sun, 23 Sep 2018 10:55:05 GMT</pubDate></channel></rss>",
        "SINA_FINANCE",
        datetime(2026, 10, 24, tzinfo=UTC),
        audit,
    )
    assert result == [] and audit["candidate_count"] == 0 and audit["source_stale"]
    assert audit["source_updated_at"].startswith("2018-09-23")
    with pytest.raises(ValueError, match="NOT_RSS_FEED"):
        parse_rss(b"<html><body>not a feed</body></html>", "TEST", datetime.now(UTC))


def test_pbc_seventh_candidate_is_collected_without_lowering_relevance():
    """同一列表页第七篇政策摘要也应被采集，不在主题判断前截成六篇。"""
    day = "2026-10-23"
    listing = (
        "<table>"
        + "".join(
            f'<tr><td><font class="newslist_style"><a href="/{index}">文章{index}</a></font>{day}</td></tr>'
            for index in range(7)
        )
        + "</table>"
    )
    article = '<meta name="ArticleTitle" content="货币政策摘要"><meta name="PubDate" content="2026-10-23"><meta name="Description" content="隔离摘要">'

    def handle(http_request):
        return httpx.Response(
            200, text=listing if "index.html" in str(http_request.url) else article
        )

    audit = {}
    with httpx.Client(transport=httpx.MockTransport(handle)) as client:
        items = pbc_news(client, datetime(2026, 10, 24, tzinfo=UTC), audit)
    assert len(items) == 7 and audit["candidate_count"] == 7
    assert audit["content_type"] == "SUMMARY"
    assert all(
        item.published_at.hour == 23 and item.published_at.minute == 59
        for item in items
    )
