"""基金DS专用隔离合同验证；内存HTTP，无真实Key、无真实费用。"""

import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from etf_ai.fund import FundAnalysisRequest, analyze_fund, quote_fund, validate_fund
from etf_ai.providers import Settings
from etf_ai.schemas import NewsItem


def request(code="008701", horizon=20, nature="MOCK"):
    """构造明确标记的测试资料，三个资产不得互相借用证据。"""
    cutoff = datetime(2026, 10, 28, 8, tzinfo=UTC)
    return FundAnalysisRequest(
        fund_code=code,
        horizon=horizon,
        cutoff=cutoff,
        news=[
            NewsItem(
                id="fund-fixture",
                title="黄金储备资料",
                summary="黄金储备发生变化，资料未说明市场是否已反映。",
                source_url="https://www.mof.gov.cn/isolated-fund-test",
                source_name="ISOLATED_TEST",
                published_at=cutoff - timedelta(days=1),
                fetched_at=cutoff - timedelta(hours=1),
                nature=nature,
                publication_verified=True,
                content_sha256="a" * 64,
            )
        ],
    )


def settings():
    """显式测试设置不会读真实凭据。"""
    return Settings(enabled=True, api_key="test-only", rate_seconds=0)


def prepared(req):
    """模拟Java报价绑定与预留，重复请求身份不漂移。"""
    quoted = quote_fund(req, settings())
    return req.model_copy(
        update={
            "expected_input_sha256": quoted["input_sha256"],
            "expected_evidence_snapshot_id": quoted["evidence_snapshot_id"],
            "reserved_usd": quoted["reserved_usd"],
        }
    ), quoted


def output(quoted, available=True):
    """模拟DS结构，事实只能由给定证据ID映射。"""
    return {
        "fund_code": "008701",
        "horizon": 20,
        "status": "AVAILABLE" if available else "INSUFFICIENT_DATA",
        "stance": "NEGATIVE" if available else "UNKNOWN",
        "sentiment_score": "-0.2" if available else None,
        "summary": "资料可能已定价。",
        "risk_flags": ["HIGH_RISK"] if available else [],
        "mechanisms": [
            {
                "evidence_ids": [quoted["evidence_catalog"][0]["id"]],
                "kind": "CAUSAL_HYPOTHESIS",
                "mechanism": "可能改变需求预期",
                "counter_factor": "其他需求可能下降",
                "invalidation": "存量变化已定价",
                "uncertainty": "无法判定增量信息",
            }
        ]
        if available
        else [],
    }


def test_disabled_by_default_and_wrong_identity_no_http(monkeypatch):
    """独立基金开关默认关闭；输入身份不匹配连HTTP都不创建。"""
    monkeypatch.delenv("ETF_FUND_LLM_ENABLED", raising=False)
    req, quoted = prepared(request(nature="REAL"))
    assert not quoted["enabled"]
    monkeypatch.setattr(
        httpx, "Client", lambda *args, **kwargs: pytest.fail("不应外呼")
    )
    assert analyze_fund(req, settings())["attempts"] == 0
    assert (
        analyze_fund(request(nature="REAL"), settings())["error_code"]
        == "INPUT_SNAPSHOT_MISMATCH"
    )


def test_asset_horizon_time_snapshot_isolation():
    """黄金资料不能解释短债，20与60周期身份不同，截止后首次获取不准入。"""
    assert quote_fund(request(code="007169"), settings())["eligible_source_ids"] == []
    first = quote_fund(request(), settings())
    assert (
        first["request_fingerprint"]
        != quote_fund(request(horizon=60), settings())["request_fingerprint"]
    )
    req = request()
    req.news[0].fetched_at = req.cutoff + timedelta(seconds=1)
    assert quote_fund(req, settings())["eligible_source_ids"] == []


@pytest.mark.parametrize("available", [True, False])
def test_real_structure_via_mock_http(monkeypatch, available):
    """结构有效或合法资料不足均明确返回，风险不因展示降级而丢失。"""
    monkeypatch.setenv("ETF_FUND_LLM_ENABLED", "true")
    req, quoted = prepared(request())
    calls = []

    def handle(message):
        """内存响应，不建立真实网络连接。"""
        calls.append(message)
        payload = json.loads(message.content)
        assert "十进制数字字符串" in payload["messages"][0]["content"]
        return httpx.Response(
            200,
            json={
                "usage": {"prompt_tokens": 80, "completion_tokens": 20},
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": json.dumps(output(quoted, available))},
                    }
                ],
            },
        )

    result = analyze_fund(req, settings(), transport=httpx.MockTransport(handle))
    assert (
        len(calls) == 1
        and result["status"] == "SUCCEEDED"
        and result["nature"] == "MOCK"
    )
    analysis = result["analysis"]
    assert analysis["fund_code"] == "008701" and analysis["horizon"] == 20
    assert analysis["forecast_usage"] == "DISPLAY_ONLY"
    assert analysis["analysis_status"] == (
        "VALID" if available else "INSUFFICIENT_INPUT"
    )
    if available:
        assert analysis["facts"][0]["quote"] == quoted["evidence_catalog"][0]["text"]
        assert analysis["adjustment_blocked_by_risk"]
    else:
        assert analysis["sentiment_score"] is None and analysis["facts"] == []


@pytest.mark.parametrize(
    "mutation",
    [
        "unknown_evidence",
        "wrong_horizon",
        "bad_score",
        "nonnumeric_score",
        "nan_score",
        "probability",
    ],
)
def test_invalid_output_rejected(mutation):
    """不存在的引用、错误周期、非法分数和越权概率字段一律拒绝。"""
    quoted = quote_fund(request(), settings())
    result = output(quoted)
    if mutation == "unknown_evidence":
        result["mechanisms"][0]["evidence_ids"] = ["EV_unknown"]
    elif mutation == "wrong_horizon":
        result["horizon"] = 60
    elif mutation == "bad_score":
        result["sentiment_score"] = "2"
    elif mutation == "nonnumeric_score":
        result["sentiment_score"] = "中性偏多"
    elif mutation == "nan_score":
        result["sentiment_score"] = "NaN"
    else:
        result["probability_up"] = "0.7"
    with pytest.raises(ValueError):
        validate_fund(json.dumps(result), quoted["evidence_catalog"], "008701", 20)


def test_provider_failure_once_and_preserves_budget(monkeypatch):
    """失败一次即停；服务端故障保留未知费用占用，不生成中性替代。"""
    monkeypatch.setenv("ETF_FUND_LLM_ENABLED", "true")
    req, _ = prepared(request())
    calls = []

    def handle(message):
        """模拟HTTP503。"""
        calls.append(message)
        return httpx.Response(503)

    result = analyze_fund(req, settings(), transport=httpx.MockTransport(handle))
    assert (
        result["status"] == "FAILED" and result["billing_uncertain"] and len(calls) == 1
    )
    assert "analysis" not in result and Decimal(result["cost_usd"]) == 0
