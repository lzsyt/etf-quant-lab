"""隔离黄金解释合同测试；所有HTTP均内存传输，不读取真实Key或消耗预算。"""

import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from etf_ai.gold import GoldAnalysisRequest, analyze_gold, quote_gold, validate_gold
from etf_ai.providers import Settings
from etf_ai.schemas import NewsItem


def request(title="央行公布黄金储备变化", nature="MOCK"):
    """明确标记测试来源，不向真实行情或新闻库写入样例。"""
    now = datetime(2026, 10, 26, 13, tzinfo=UTC)
    return GoldAnalysisRequest(
        cutoff=now,
        news=[
            NewsItem(
                id="gold-fixture",
                title=title,
                summary="公告提供黄金储备资料，变化未必构成尚未定价的信息。",
                source_url="https://www.mof.gov.cn/isolated-gold-test",
                source_name="ISOLATED_TEST",
                published_at=now - timedelta(days=1),
                fetched_at=now - timedelta(hours=1),
                nature=nature,
                publication_verified=True,
                content_sha256="a" * 64,
                metadata={
                    "content": "黄金储备资料为当期存量。不能据此认定未来价格一定上涨。",
                    "content_type": "EXCERPT",
                },
            )
        ],
    )


def settings():
    """显式测试配置只使用无效占位Key，永不读取环境。"""
    return Settings(enabled=True, api_key="test-only", rate_seconds=0)


def prepared(req):
    """模拟Java先冻结报价并预留费用后再发起分析。"""
    quoted = quote_gold(req, settings())
    return req.model_copy(
        update={
            "expected_input_sha256": quoted["input_sha256"],
            "expected_evidence_snapshot_id": quoted["evidence_snapshot_id"],
            "reserved_usd": quoted["reserved_usd"],
        }
    ), quoted


def test_coin_topic_is_not_a_bullish_gold_signal():
    """纪念币同时出现央行和黄金也不能获得有效黄金机制资格。"""
    result = quote_gold(request("央行发行黄金纪念币"), settings())
    assert result["eligible_source_ids"] == []
    assert result["rejected"][0]["reason"] == "COIN_OR_RETAIL_TOPIC"


def test_publication_and_first_fetch_have_independent_cutoffs():
    """今天读取的历史新闻不得前填到历史预测输入。"""
    req = request()
    req.news[0].fetched_at = req.cutoff + timedelta(seconds=1)
    assert quote_gold(req, settings())["eligible_source_ids"] == []


def test_gold_identity_and_original_text_mapping_are_frozen():
    """黄金域与长周期进入身份；服务端原文切片逐字等于输入，重试身份不漂移。"""
    req = request()
    quoted = quote_gold(req, settings())
    assert quoted == quote_gold(req, settings())
    assert quoted["domain"] == "GOLD_LONG_TERM"
    entry = quoted["evidence_catalog"][0]
    assert (
        req.news[0].metadata[entry["field"]][entry["start"] : entry["end"]]
        == entry["text"]
    )
    assert quoted["pricing"]["attempts"] == 1
    assert Decimal(quoted["reserved_usd"]) > 0


def test_wrong_snapshot_refuses_before_any_http(monkeypatch):
    """未携带精确报价的run不能建立网络连接。"""
    monkeypatch.setattr(httpx, "Client", lambda *a, **k: pytest.fail("不能外呼"))
    result = analyze_gold(request(nature="REAL"), settings())
    assert result["error_code"] == "INPUT_SNAPSHOT_MISMATCH"
    assert result["attempts"] == 0


def test_unknown_evidence_and_numeric_probabilities_are_rejected():
    """无法跨快照引用，也不允许数值预测从解释层流入主模型。"""
    quoted = quote_gold(request(), settings())
    result = {
        "status": "AVAILABLE",
        "summary": "条件关联，未证明因果。",
        "mechanisms": [
            {
                "evidence_ids": ["EV_unknown"],
                "kind": "CAUSAL_HYPOTHESIS",
                "mechanism": "可能影响需求",
                "counter_factor": "供给可能增加",
                "invalidation": "需求变化消退",
                "uncertainty": "可能已被定价",
            }
        ],
    }
    with pytest.raises(ValueError):
        validate_gold(json.dumps(result), quoted["evidence_catalog"])
    result["mechanisms"][0]["evidence_ids"] = [quoted["evidence_catalog"][0]["id"]]
    result["probability"] = 0.8
    with pytest.raises(ValueError):
        validate_gold(json.dumps(result), quoted["evidence_catalog"])


def test_real_provider_result_can_be_information_insufficient():
    """Schema成功与投资有效性分开，合法信息不足无虚构中性分数。"""
    req, quoted = prepared(request())
    calls = []

    def handle(http_request):
        """内存提供方返回一份合法信息不足响应，不存在真实外部调用。"""
        calls.append(http_request)
        return httpx.Response(
            200,
            json={
                "usage": {"prompt_tokens": 80, "completion_tokens": 20},
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": json.dumps(
                                {
                                    "status": "INSUFFICIENT_DATA",
                                    "summary": "存量信息不足以确定增量机制。",
                                    "mechanisms": [],
                                }
                            )
                        },
                    }
                ],
            },
        )

    result = analyze_gold(req, settings(), transport=httpx.MockTransport(handle))
    assert result["status"] == "SUCCEEDED"
    assert result["result"]["status"] == "INSUFFICIENT_DATA"
    assert result["nature"] == "MOCK" and len(calls) == 1
    assert result["request_fingerprint"] == quoted["request_fingerprint"]
    assert "probability" not in result["result"]


def test_provider_failure_does_not_blindly_retry_or_fabricate():
    """一次HTTP失败即结束，不产生替代事实；不确定收费继续占用预算。"""
    req, _ = prepared(request())
    calls = []

    def handle(http_request):
        """模拟提供方临时失败，仅内存计数。"""
        calls.append(http_request)
        return httpx.Response(503)

    result = analyze_gold(req, settings(), transport=httpx.MockTransport(handle))
    assert result["status"] == "FAILED" and len(calls) == 1
    assert result["billing_uncertain"] is True
    assert "result" not in result
