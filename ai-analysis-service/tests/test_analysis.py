"""结构、发布时间、费用与API失败的确定性测试，不使用真实付费密钥。"""

import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from etf_ai.news import parse_rss, safe_url
from etf_ai.providers import Settings, analyze, quote
from etf_ai.schemas import AnalysisRequest, NewsItem
from pydantic import ValidationError


def request():
    now = datetime.now(UTC)
    return AnalysisRequest(
        cutoff=now,
        news=[
            NewsItem(
                id="news-1",
                title="货币政策公开测试",
                summary="测试材料，不对应真实市场事件",
                source_url="https://www.pbc.gov.cn/test",
                source_name="TEST",
                published_at=now - timedelta(hours=2),
                fetched_at=now - timedelta(hours=1),
                publication_verified=True,
                content_sha256="a" * 64,
            )
        ],
    )


def settings():
    return Settings(enabled=True, api_key="test-only", rate_seconds=0)


def response_data(source="news-1"):
    return {
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "content": json.dumps(
                        {
                            "market_sentiment": "neutral",
                            "sentiment_score": 0.0,
                            "risk_level": "medium",
                            "key_factors": [],
                            "risk_factors": [],
                            "source_ids": [source],
                            "analysis_summary": "测试结果",
                        }
                    )
                },
            }
        ],
        "usage": {"prompt_tokens": 100, "completion_tokens": 60},
        "model": "test-model",
    }


def test_future_news_and_late_retrieval_are_rejected():
    data = request().model_dump()
    data["news"][0]["fetched_at"] = data["cutoff"] + timedelta(seconds=1)
    with pytest.raises(ValidationError):
        AnalysisRequest(**data)
    data = request().model_dump()
    data["news"][0]["published_at"] = data["cutoff"] + timedelta(seconds=1)
    with pytest.raises(ValidationError):
        AnalysisRequest(**data)


def test_disabled_missing_and_mock_are_explicit():
    req = request()
    assert analyze(req, Settings())["status"] == "MISSING"
    req.news[0].nature = "MOCK"
    result = analyze(req, Settings(mock=True))
    assert result["nature"] == "MOCK" and result["cost_usd"] == "0"


def test_budget_is_required_before_any_call():
    with pytest.raises(ValueError, match="费用"):
        analyze(
            request(),
            settings(),
            httpx.MockTransport(lambda r: pytest.fail("must not call")),
        )


def test_real_protocol_schema_and_usage():
    req, config = request(), settings()
    req.reserved_usd = quote(req, config)["reserved_usd"]

    def handle(r):
        body = json.loads(r.content)
        assert "tools" not in body and body["thinking"]["type"] == "disabled"
        return httpx.Response(200, json=response_data())

    result = analyze(req, config, httpx.MockTransport(handle))
    assert result["status"] == "SUCCEEDED" and result["input_tokens"] == 100
    assert Decimal(result["cost_usd"]) == Decimal("0.000102")
    assert "probability" not in result["result"]


def test_unknown_sources_invalid_json_and_rate_limit_fail_closed():
    extra_field = response_data()
    content = json.loads(extra_field["choices"][0]["message"]["content"])
    content["additionalProperties"] = False
    extra_field["choices"][0]["message"]["content"] = json.dumps(content)
    for response in [
        httpx.Response(200, json=extra_field),
        httpx.Response(200, json=response_data("invented")),
        httpx.Response(429, json={}),
        httpx.Response(200, json={"choices": []}),
    ]:
        req, config = request(), settings()
        req.reserved_usd = quote(req, config)["reserved_usd"]
        result = analyze(
            req, config, httpx.MockTransport(lambda r, response=response: response)
        )
        assert result["status"] == "FAILED" and "result" not in result


def test_timeout_reserves_unknown_billing():
    req, config = request(), settings()
    req.reserved_usd = quote(req, config)["reserved_usd"]

    def fail(r):
        raise httpx.ReadTimeout("test")

    result = analyze(req, config, httpx.MockTransport(fail))
    assert result["billing_uncertain"] and result["status"] == "FAILED"


def test_news_ssrf_and_xml_entity_are_rejected():
    with pytest.raises(ValueError):
        safe_url("http://127.0.0.1/private")
    with pytest.raises(
        __import__(
            "defusedxml.common", fromlist=["DefusedXmlException"]
        ).DefusedXmlException
    ):
        parse_rss(
            b'<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss>&xxe;</rss>',
            "TEST",
            datetime.now(UTC),
        )


def test_gpt_stays_disabled_in_mainland_configuration():
    config = settings()
    config.provider = "openai"
    with pytest.raises(ValueError, match="GPT"):
        analyze(request(), config)


def test_official_but_irrelevant_news_never_calls_api():
    """央行来源也不能使干部任免新闻自动成为ETF研究特征。"""
    req = request()
    req.news[0].title = "机关干部任免公告"
    req.news[0].summary = "干部任职信息，日常负责货币政策事务。"
    quoted = quote(req, settings())
    assert quoted["eligible_source_ids"] == []
    result = analyze(req, settings(), httpx.MockTransport(lambda r: pytest.fail("不得收费")))
    assert result["status"] == "MISSING" and result["error_code"] == "NO_RELEVANT_NEWS"
    assert result["relevance"]["rejected"][0]["reason"] == "ADMINISTRATIVE_NEWS"


def test_relevance_scope_and_filtered_quote_match_actual_inputs():
    """直接指数新闻只关联目标ETF，未知品种不能套用宽基宏观假设。"""
    from etf_ai.relevance import select_relevant

    req = request()
    req.news[0].title = "创业板指数调整公告"
    selected, audit = select_relevant(req)
    assert len(selected.news) == 1
    assert audit["accepted"][0]["symbols"] == ["159915"]
    req.symbols = ["510500"]
    assert quote(req, settings())["eligible_source_ids"] == []
    req.news[0].title = "公开市场操作公告"
    assert quote(req, settings())["eligible_source_ids"] == ["news-1"]
    req.symbols = ["UNKNOWN"]
    assert quote(req, settings())["eligible_source_ids"] == []


def test_mixed_news_excludes_irrelevant_content_before_model_call():
    """保留被过滤原因，发送给模型和引用的来源均只能来自合格集合。"""
    req, config = request(), settings()
    irrelevant = req.news[0].model_copy(update={"id": "noise", "title": "员工食堂采购公告",
                                               "summary": "采购蔬菜和厨房设备"})
    req.news.append(irrelevant)
    req.reserved_usd = quote(req, config)["reserved_usd"]

    def handle(r):
        payload = json.loads(json.loads(r.content)["messages"][1]["content"])
        assert [n["id"] for n in payload["news"]] == ["news-1"]
        return httpx.Response(200, json=response_data())

    result = analyze(req, config, httpx.MockTransport(handle))
    assert result["status"] == "SUCCEEDED"
    assert result["source_ids"] == ["news-1"]
    assert len(result["relevance"]["rejected"]) == 1
