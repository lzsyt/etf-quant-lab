"""GPT协议与网络边界回归：仅内存HTTP，不读取用户密钥或发起收费调用。"""

import json
from dataclasses import replace
from datetime import timedelta
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest
from etf_ai.gpt import (
    GptReviewRequest,
    GptSettings,
    _validated_review,
    analyze_gpt,
    quote_gpt,
)
from pydantic import ValidationError
from test_forecast_prediction import prediction, request


def settings():
    """测试专用密钥与合法代理形状，transport保证没有真实网络。"""
    return GptSettings(
        enabled=True, api_key="gpt-test-secret", proxy_url="http://127.0.0.1:17891"
    )


def prepared(req, config=None):
    """模拟Java已持久预留同一报价，不跳过输入身份校验。"""
    quote = quote_gpt(req, config or settings())
    return req.model_copy(
        update={
            "reserved_usd": quote["reserved_usd"],
            "expected_input_sha256": quote["input_sha256"],
            "expected_evidence_snapshot_id": quote["evidence_snapshot_id"],
        }
    ), quote


def envelope(payload, **changes):
    """真实Responses形状包含先行reasoning项，不能假设第一项就是文本。"""
    return {
        "status": "completed",
        "model": "gpt-6.1-sol",
        "usage": {
            "input_tokens": 1000,
            "output_tokens": 500,
            "output_tokens_details": {"reasoning_tokens": 300},
        },
        "output": [
            {"type": "reasoning"},
            {
                "type": "message",
                "content": [
                    {
                        "type": "output_text",
                        "text": json.dumps(payload, ensure_ascii=False),
                    }
                ],
            },
        ],
        **changes,
    }


def review_request():
    """两条同截止同窗口的基础记录，原始输入没有到期收益。"""
    base = request()
    values = []
    for scheme, value in (("FUND_LIGHTGBM", "0.02"), ("GPT", "-0.01")):
        values.append(
            {
                "id": str(uuid4()),
                "scheme": scheme,
                "data_cutoff": base.cutoff,
                "generated_at": base.cutoff + timedelta(seconds=1),
                "output": {"point_return": value, "probability_up": None},
                "summary": "合成测试说明",
                "model_validation_status": "FAILED",
            }
        )
    return GptReviewRequest(**base.model_dump(), predictions=values)


def review_output(req):
    """只输出文字研究，不提供综合数值。"""
    return {
        "asset": req.asset,
        "target": req.target,
        "horizon": req.horizon,
        "status": "AVAILABLE",
        "agreement": "DIVERGENT",
        "summary": "方向存在分歧。",
        "differences": ["两条预测的符号不同。"],
        "possible_causes": ["输入解释可能不同。"],
        "missing_evidence": ["尚无到期事实。"],
        "counter_factors": [],
        "invalidation": "新的合格证据可能改变解释。",
        "uncertainty": "尚未证明模型有效。",
        "prediction_ids": [str(p.id) for p in req.predictions],
        "evidence_ids": [],
    }


def test_independent_responses_output_and_reasoning_billing():
    req, quote = prepared(request())
    calls = []

    def handler(http):
        """核对固定官方端点及独立输入没有基础预测字段。"""
        body = json.loads(http.content)
        calls.append(body)
        assert str(http.url) == "https://api.openai.com/v1/responses"
        assert body["store"] is False
        assert body["text"]["format"]["strict"] is True
        assert "predictions" not in json.loads(body["input"][1]["content"])
        return httpx.Response(200, json=envelope(prediction(req, quote)))

    result = analyze_gpt(req, settings(), httpx.MockTransport(handler))
    assert result["status"] == "SUCCEEDED"
    assert result["nature"] == "MOCK"
    assert result["result"]["probability_validation_status"] == "NOT_PROVIDED"
    assert result["billing_uncertain"] is False
    assert Decimal(result["cost_usd"]) == Decimal("0.007")
    assert len(calls) == 1


@pytest.mark.parametrize(
    "proxy",
    [
        "",
        "http://external.example:17891",
        "https://127.0.0.1:17891",
        "http://user:password@127.0.0.1:17891",
        "http://127.0.0.1:8888",
        "http://127.0.0.1:17891/path",
        "http://127.0.0.1:invalid",
    ],
)
def test_no_proxy_never_calls_or_falls_back(proxy):
    config = replace(settings(), proxy_url=proxy)
    req, quote = prepared(request(), config)
    assert quote["configured"] is False
    result = analyze_gpt(
        req, config, httpx.MockTransport(lambda _: pytest.fail("unexpected HTTP"))
    )
    assert result["error_code"] == "GPT_PROXY_REQUIRED"
    assert result["attempts"] == 0


def test_real_client_explicit_proxy_and_no_environment_fallback(monkeypatch):
    req, _ = prepared(request(nature="REAL"))
    seen = []

    def broken_client(**kwargs):
        """代理初始化失败也必须返回受控错误，不能创建第二个直连客户端。"""
        seen.append(kwargs)
        raise httpx.ConnectError("private proxy failure")

    monkeypatch.setattr("etf_ai.gpt.httpx.Client", broken_client)
    result = analyze_gpt(req, settings())
    assert result["status"] == "FAILED"
    assert result["billing_uncertain"] is True
    assert len(seen) == 1
    assert seen[0]["proxy"] == "http://127.0.0.1:17891"
    assert seen[0]["trust_env"] is False
    assert seen[0]["follow_redirects"] is False
    assert "private proxy" not in json.dumps(result)


def test_quote_changes_with_model_and_output_limit():
    req, _ = prepared(request())
    config = replace(settings(), max_tokens=4001)
    result = analyze_gpt(
        req, config, httpx.MockTransport(lambda _: pytest.fail("unexpected HTTP"))
    )
    assert result["error_code"] == "INPUT_SNAPSHOT_MISMATCH"


@pytest.mark.parametrize("status", [401, 429, 500])
def test_provider_error_never_retries_or_leaks_response(status):
    req, _ = prepared(request())
    calls = []

    def fail(http):
        """敏感供应商原文不能进入结果或自动重试。"""
        calls.append(http)
        return httpx.Response(status, text="gpt-test-secret")

    result = analyze_gpt(req, settings(), httpx.MockTransport(fail))
    assert result["status"] == "FAILED"
    assert result["billing_uncertain"] is (status >= 500)
    assert len(calls) == 1
    assert "gpt-test-secret" not in json.dumps(result)


def test_refusal_and_incomplete_preserve_known_costs():
    req, _ = prepared(request())
    for body, reason in [
        (envelope({}, status="incomplete"), "GPT_INCOMPLETE_OUTPUT"),
        (
            envelope(
                {}, output=[{"type": "message", "content": [{"type": "refusal"}]}]
            ),
            "GPT_REFUSED",
        ),
    ]:
        result = analyze_gpt(
            req,
            settings(),
            httpx.MockTransport(
                lambda _, response_body=body: httpx.Response(200, json=response_body)
            ),
        )
        assert result["error_code"] == reason
        assert Decimal(result["cost_usd"]) == Decimal("0.007")
        assert result["billing_uncertain"] is False


def test_review_is_disabled_before_settings_quote_or_network(monkeypatch):
    """即使GPT预测已启用，旧分歧报价和执行也不读配置、不预留、不建HTTP。"""
    req = review_request()
    monkeypatch.setenv("ETF_GPT_ENABLED", "true")
    monkeypatch.setattr(
        GptSettings,
        "for_request",
        lambda *_: pytest.fail("停用分歧不应读取模型配置"),
    )
    monkeypatch.setattr(
        httpx, "Client", lambda **_: pytest.fail("停用分歧不应创建HTTP客户端")
    )
    for result in (quote_gpt(req), analyze_gpt(req), analyze_gpt(req, settings())):
        assert result["domain"] == "FORECAST_DISAGREEMENT_REVIEW"
        assert result["status"] == "BLOCKED"
        assert result["error_code"] == "GPT_DISAGREEMENT_DISABLED"
        assert result["enabled"] is False
        assert result["attempts"] == 0
        assert Decimal(result["reserved_usd"]) == Decimal(result["cost_usd"]) == 0
        assert result["billing_uncertain"] is False
        assert "result" not in result


@pytest.mark.parametrize("tamper", ["id", "numeric", "evidence"])
def test_review_rejects_invented_references_and_numeric_prediction(tamper):
    req = review_request()
    output = review_output(req)
    if tamper == "id":
        output["prediction_ids"][0] = str(uuid4())
    elif tamper == "numeric":
        output["probability_up"] = "0.9"
    else:
        output["evidence_ids"] = ["invented"]
    # 保留历史结果校验规则，但不再通过已停用的付费入口运行旧分歧协议。
    with pytest.raises(ValueError):
        _validated_review(json.dumps(output), req, [])


def test_review_rejects_other_cutoff_and_duplicate_records():
    req = review_request().model_dump()
    req["predictions"][0]["data_cutoff"] -= timedelta(days=1)
    with pytest.raises(ValidationError, match="INCOMPARABLE"):
        GptReviewRequest.model_validate(req)
    req = review_request().model_dump()
    req["predictions"][1] = req["predictions"][0]
    with pytest.raises(ValidationError, match="DUPLICATE"):
        GptReviewRequest.model_validate(req)


def test_independent_request_rejects_other_model_predictions():
    with pytest.raises(ValidationError):
        type(request()).model_validate({**request().model_dump(), "predictions": []})


def test_gpt_internal_endpoints_require_token_and_disabled_runs_never_call_network(
    monkeypatch,
):
    """四个内部入口均需令牌；关闭GPT时真实执行入口也只能返回阻断。"""
    from etf_quant import api
    from fastapi.testclient import TestClient

    token = "isolated-gpt-internal-token-longer-than-32"
    monkeypatch.setenv("ETF_INTERNAL_TOKEN", token)
    monkeypatch.setenv("ETF_GPT_ENABLED", "false")
    with TestClient(api.app) as client:
        for route, payload in (
            ("/analysis/gpt", request()),
            ("/analysis/gpt-review", review_request()),
        ):
            body = payload.model_dump(mode="json")
            for action in ("quote", "run"):
                assert client.post(route + "/" + action, json=body).status_code == 401
            response = client.post(
                route + "/run", json=body, headers={"x-internal-token": token}
            )
            assert response.status_code == 200
            assert response.json()["status"] == "BLOCKED"
            assert response.json()["attempts"] == 0


def test_review_preserves_full_precision_quant_outputs():
    """原生模型17位小数是有效研究输入，不应套用GPT生成结果的12位限制或截断。"""
    data = review_request().model_dump(mode="json")
    exact = "0.04413635665490971"
    data["predictions"][0]["output"]["point_return"] = exact
    req = GptReviewRequest.model_validate(data)
    assert req.predictions[0].output.point_return == exact
    assert quote_gpt(req, settings())["error_code"] == "GPT_DISAGREEMENT_DISABLED"
    for invalid in ("NaN", "1e9999", "0." + "1" * 33):
        data["predictions"][0]["output"]["point_return"] = invalid
        with pytest.raises(ValidationError):
            GptReviewRequest.model_validate(data)


def test_review_endpoints_remain_blocked_when_gpt_is_enabled(monkeypatch):
    """内部令牌不能绕过停用，报价和执行均零调用，保留正常独立预测入口。"""
    from etf_quant import api
    from fastapi.testclient import TestClient

    token = "isolated-gpt-review-disabled-token-32"
    monkeypatch.setenv("ETF_INTERNAL_TOKEN", token)
    monkeypatch.setenv("ETF_GPT_ENABLED", "true")
    monkeypatch.setattr(
        GptSettings,
        "for_request",
        lambda *_: pytest.fail("停用分歧入口不应读取模型配置"),
    )
    with TestClient(api.app) as client:
        for action in ("quote", "run"):
            response = client.post(
                "/analysis/gpt-review/" + action,
                json=review_request().model_dump(mode="json"),
                headers={"x-internal-token": token},
            )
            assert response.status_code == 200
            assert response.json()["error_code"] == "GPT_DISAGREEMENT_DISABLED"
            assert response.json()["attempts"] == 0
            assert response.json()["cost_usd"] == "0"
