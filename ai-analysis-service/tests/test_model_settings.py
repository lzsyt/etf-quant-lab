"""模型设置、计费与在途配置冻结测试：只用内存HTTP，不读取真实凭据。"""

import json
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from etf_ai.forecast import analyze_forecast, quote_forecast
from etf_ai.forward import analyze_forward, quote_forward
from etf_ai.fund import analyze_fund, quote_fund
from etf_ai.gold import analyze_gold, quote_gold
from etf_ai.gpt import GptSettings, analyze_gpt, quote_gpt
from etf_ai.model_settings import (
    MODEL_CATALOG,
    ModelSelection,
    validate_selection,
)
from etf_ai.providers import Settings
from fastapi.testclient import TestClient
from test_forecast_prediction import prediction
from test_forecast_prediction import request as forecast_request
from test_forward_news import request as forward_request
from test_fund_evidence import request as fund_request
from test_gold_evidence import request as gold_request
from test_gpt import envelope, review_output, review_request


@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    """覆盖所有提供方密钥为无效测试值，调用仅由MockTransport接管。"""
    for key, value in {
        "ETF_LLM_PROVIDER": "deepseek",
        "ETF_LLM_MODEL": "deepseek-flash",
        "ETF_LLM_MODEL_VERSION": "DeepSeek-V4.1-Flash-1017",
        "ETF_LLM_INPUT_USD_PER_MILLION": "0.3",
        "ETF_LLM_OUTPUT_USD_PER_MILLION": "1.2",
        "ETF_LLM_ENABLED": "true",
        "ETF_FUND_LLM_ENABLED": "true",
        "DEEPSEEK_API_KEY": "settings-test-ds",
        "OPENAI_API_KEY": "settings-test-gpt",
        "ETF_GPT_MODEL": "gpt-6.1-sol",
        "ETF_GPT_ENABLED": "true",
        "ETF_GPT_INPUT_USD_PER_MILLION": "2",
        "ETF_GPT_OUTPUT_USD_PER_MILLION": "10",
        "ETF_GPT_PROXY_URL": "http://127.0.0.1:17891",
        "ETF_INTERNAL_TOKEN": "t" * 40,
    }.items():
        monkeypatch.setenv(key, value)


@pytest.mark.parametrize(
    "provider,model,effort",
    [
        (provider, model, effort)
        for provider, models in MODEL_CATALOG.items()
        for model, details in models.items()
        for effort in details["efforts"]
    ],
)
def test_every_model_and_effort_has_its_own_price(provider, model, effort):
    """模型和强度选择必须同时加载正确模型版本及官方单价。"""
    selected = validate_selection(
        provider, {"model": model, "reasoning_effort": effort}
    )
    cls = GptSettings if provider == "openai" else Settings
    settings = cls.for_selection(selected)
    assert settings.model == model and settings.reasoning_effort == effort
    assert settings.input_price == Decimal(
        MODEL_CATALOG[provider][model]["input_per_million"]
    )
    assert settings.output_price == Decimal(
        MODEL_CATALOG[provider][model]["output_per_million"]
    )
    assert settings.max_tokens >= (4000 if provider == "openai" else 1400)


@pytest.mark.parametrize(
    "provider,model,effort",
    [
        ("deepseek", "deepseek-flash", "medium"),
        ("openai", "gpt-6.1-sol", "none"),
        ("openai", "gpt-6-astra", "ultra"),
        ("deepseek", "gpt-6.1-sol", "low"),
        ("openai", "unknown", "low"),
    ],
)
def test_invalid_combination_never_silently_falls_back(provider, model, effort):
    """不支持的组合必须拒绝，不能把用户选的强度静默换成另一个。"""
    with pytest.raises(ValueError):
        validate_selection(provider, {"model": model, "reasoning_effort": effort})


def test_internal_settings_endpoints_are_free_private_and_secret_free(monkeypatch):
    """目录与保存校验无外部网络，额外凭据/价格字段在落库之前被拒绝。"""
    from etf_quant.api import app

    with TestClient(app) as client:
        assert client.post("/analysis/settings", json={}).status_code == 401
        headers = {"X-Internal-Token": "t" * 40}
        response = client.post("/analysis/settings", headers=headers, json={})
        assert response.status_code == 200
        assert "settings-test" not in response.text and "17891" not in response.text
        values = response.json()["providers"]
        assert values[0]["current"]["reasoning_effort"] == "none"
        assert values[1]["current"]["reasoning_effort"] == "low"
        assert len(values[0]["models"]) == 2 and len(values[1]["models"]) == 3
        for extra in ["api_key", "base_url", "proxy_url", "input_price", "max_tokens"]:
            response = client.post(
                "/analysis/settings/validate",
                headers=headers,
                json={
                    "provider": "openai",
                    "selection": {
                        "model": "gpt-6.1-sol",
                        "reasoning_effort": "low",
                        extra: "NEVER_ECHO_TEST_SECRET_VALUE",
                    },
                },
            )
            assert (
                response.status_code == 422
                and "NEVER_ECHO_TEST_SECRET_VALUE" not in response.text
            )


@pytest.mark.parametrize(
    "request_factory,quote_fn",
    [
        (forecast_request, quote_forecast),
        (forward_request, quote_forward),
        (fund_request, quote_fund),
        (gold_request, quote_gold),
    ],
)
def test_unchanged_default_reuses_identity_and_changed_effort_does_not(
    request_factory, quote_fn
):
    """保存原配置不制造新收费身份，改变强度会同时改变输入身份与预留费用。"""
    request = request_factory()
    original = quote_fn(request)
    request.model_settings = ModelSelection(
        model="deepseek-flash", reasoning_effort="none"
    )
    same = quote_fn(request)
    assert same["input_sha256"] == original["input_sha256"]
    assert same["request_fingerprint"] == original["request_fingerprint"]
    assert same["reserved_usd"] == original["reserved_usd"]
    request.model_settings = ModelSelection(
        model="deepseek-flash", reasoning_effort="high"
    )
    high = quote_fn(request)
    assert high["input_sha256"] != original["input_sha256"]
    assert high["request_fingerprint"] != original["request_fingerprint"]
    assert Decimal(high["reserved_usd"]) > Decimal(original["reserved_usd"])


@pytest.mark.parametrize(
    "request_factory,quote_fn,run_fn",
    [
        (forecast_request, quote_forecast, analyze_forecast),
        (forward_request, quote_forward, analyze_forward),
        (fund_request, quote_fund, analyze_fund),
        (gold_request, quote_gold, analyze_gold),
    ],
)
def test_deepseek_calls_send_selected_model_and_actual_thinking_parameters(
    request_factory, quote_fn, run_fn
):
    """四个业务入口均发送实际选中的模型与档位，而非仅在设置页修改显示。"""
    request = request_factory()
    request.model_settings = ModelSelection(
        model="deepseek-v4-pro", reasoning_effort="high"
    )
    settings = replace(Settings.for_request(request), rate_seconds=0, attempts=1)
    quoted = quote_fn(request, settings)
    request.reserved_usd = quoted["reserved_usd"]
    request.expected_input_sha256 = quoted["input_sha256"]
    request.expected_evidence_snapshot_id = quoted["evidence_snapshot_id"]
    calls = []

    def handler(http):
        """模拟已收费但不合格输出，只验证请求及费用记录，不伪造有效财经结论。"""
        body = json.loads(http.content)
        calls.append(body)
        assert body["model"] == "deepseek-v4-pro"
        assert body["thinking"] == {"type": "enabled"}
        assert body["reasoning_effort"] == "high" and body["max_tokens"] == 8000
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "{}"}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 500},
            },
        )

    result = run_fn(request, settings, httpx.MockTransport(handler))
    assert len(calls) == 1
    assert result["model_settings"] == request.model_settings.model_dump()


@pytest.mark.parametrize("review", [False, True])
def test_gpt_both_roles_use_frozen_settings_even_if_environment_changes(
    monkeypatch, review
):
    """独立预测沿用冻结配置；为分歧指定模型也不能绕过停用规则。"""
    request = review_request() if review else forecast_request()
    request.model_settings = ModelSelection(model="gpt-6-luna", reasoning_effort="high")
    quoted = quote_gpt(request)
    if review:
        assert quoted["error_code"] == "GPT_DISAGREEMENT_DISABLED"
        result = analyze_gpt(
            request,
            transport=httpx.MockTransport(lambda _: pytest.fail("停用分歧不得外呼")),
        )
        assert result["error_code"] == "GPT_DISAGREEMENT_DISABLED"
        assert result["attempts"] == 0
        return
    request.reserved_usd = quoted["reserved_usd"]
    request.expected_input_sha256 = quoted["input_sha256"]
    request.expected_evidence_snapshot_id = quoted["evidence_snapshot_id"]
    monkeypatch.setenv("ETF_GPT_MODEL", "gpt-6-astra")
    calls = []

    def handler(http):
        """内存响应包含推理token，其费用继续计入output_tokens。"""
        body = json.loads(http.content)
        calls.append(body)
        assert body["model"] == "gpt-6-luna"
        assert (
            body["reasoning"] == {"effort": "high"}
            and body["max_output_tokens"] == 8000
        )
        return httpx.Response(
            200,
            json=envelope(
                review_output(request) if review else prediction(request, quoted),
                model="gpt-6-luna",
            ),
        )

    result = analyze_gpt(request, transport=httpx.MockTransport(handler))
    assert result["status"] == "SUCCEEDED" and len(calls) == 1
    assert result["model_settings"] == request.model_settings.model_dump()
    assert Decimal(result["cost_usd"]) == Decimal("0.00035")


def test_reasoning_drift_is_rejected_before_http(monkeypatch):
    """伪造旧报价对应的新强度必须零外呼拒绝，不让低档预留支付高档请求。"""
    request = forecast_request(nature="REAL")
    request.model_settings = ModelSelection(
        model="deepseek-flash", reasoning_effort="low"
    )
    quoted = quote_forecast(request)
    request.reserved_usd = quoted["reserved_usd"]
    request.expected_input_sha256 = quoted["input_sha256"]
    request.expected_evidence_snapshot_id = quoted["evidence_snapshot_id"]
    request.model_settings = ModelSelection(
        model="deepseek-flash", reasoning_effort="high"
    )
    monkeypatch.setattr(
        httpx, "Client", lambda *a, **k: pytest.fail("不得创建HTTP客户端")
    )
    assert analyze_forecast(request)["error_code"] == "INPUT_SNAPSHOT_MISMATCH"
