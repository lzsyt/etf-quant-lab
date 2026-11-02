"""统一独立预测协议：仅内存HTTP，验证时点、能力、身份和共享预算边界。"""

import json
from dataclasses import replace
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from etf_ai.forecast import (
    ForecastAnalysisRequest,
    analyze_forecast,
    quote_forecast,
    validate_forecast,
)
from etf_ai.providers import Settings
from etf_ai.schemas import NewsItem


def request(asset="008701", target="FUND_NAV_CHANGE", horizon=20, nature="MOCK"):
    """构造有明确可用时间的价格和新闻；ETF前瞻起点是尚未到来的开盘。"""
    cutoff = datetime(2026, 10, 28, 8, tzinfo=UTC)
    anchor = date(2026, 10, 29) if target == "ETF_FORWARD_OPEN" else date(2026, 10, 28)
    return ForecastAnalysisRequest(
        asset=asset,
        target=target,
        horizon=horizon,
        cutoff=cutoff,
        anchor_date=anchor,
        target_date=anchor + timedelta(days=horizon),
        target_version="ISOLATED_NATIVE_TARGET_V1",
        prices=[
            {
                "date": "2026-10-28",
                "value": "1.230000",
                "available_at": cutoff,
                "source": "ISOLATED_TEST",
            }
        ],
        news=[
            NewsItem(
                id="source-fixture",
                title=f"{asset}行情观察资料",
                summary=f"{asset}相关行情出现变化，但信息是否已经定价尚不确定。",
                source_url="https://www.mof.gov.cn/isolated-forecast-test",
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
    """显式使用测试凭据，永不加载用户受保护配置。"""
    return Settings(enabled=True, api_key="isolated-test-only-secret", rate_seconds=0)


def prepared(req, config=None):
    """模拟Java在共享账本完成预留后回传的冻结身份。"""
    quoted = quote_forecast(req, config or settings())
    return req.model_copy(
        update={
            "expected_input_sha256": quoted["input_sha256"],
            "expected_evidence_snapshot_id": quoted["evidence_snapshot_id"],
            "reserved_usd": quoted["reserved_usd"],
        }
    ), quoted


def prediction(req, quoted, available=True):
    """按目标能力提供数值，不把情绪分数当收益或概率。"""
    probability = req.target in {"FUND_NAV_UP", "ETF_FORWARD_OPEN", "ETF_LEGACY_CLOSE"}
    return {
        "asset": req.asset,
        "target": req.target,
        "horizon": req.horizon,
        "status": "AVAILABLE" if available else "ABSTAIN",
        "output": {
            "point_return": "0" if available and not probability else None,
            "probability_up": "0" if available and probability else None,
        },
        "summary": "资料可能已定价，结论仍需前瞻验证。",
        "evidence_ids": [quoted["evidence_catalog"][0]["id"]] if available else [],
        "counter_factors": ["其他驱动因素可能抵消变化。"] if available else [],
        "invalidation": "后续新增资料改变原假设。",
        "uncertainty": "所给信息有限，概率未经校准。",
    }


def envelope(result, usage=None, finish="stop"):
    """构造供应商响应；缺失账单用例另行删除usage。"""
    return {
        "usage": {"prompt_tokens": 80, "completion_tokens": 20}
        if usage is None
        else usage,
        "choices": [
            {"finish_reason": finish, "message": {"content": json.dumps(result)}}
        ],
    }


NATIVE_TARGETS = (
    [
        (asset, "FUND_NAV_CHANGE", horizon)
        for asset in ("460300", "007169", "008701")
        for horizon in (20, 60)
    ]
    + [(asset, "FUND_NAV_UP", 5) for asset in ("460300", "007169", "008701")]
    + [(asset, "ETF_FORWARD_OPEN", 1) for asset in ("510300", "510500", "159915")]
    + [
        (asset, "ETF_LEGACY_CLOSE", horizon)
        for asset in ("510300", "510500", "159915")
        for horizon in (1, 5)
    ]
    + [
        (asset, "ETF_CLOSE_RETURN", horizon)
        for asset in ("510300", "510500", "159915")
        for horizon in (20, 60)
    ]
    + [("Au99.99", "GOLD_BENCHMARK_RETURN", horizon) for horizon in (63, 126)]
)


@pytest.mark.parametrize("asset,target,horizon", NATIVE_TARGETS)
def test_all_native_target_capabilities(asset, target, horizon):
    """七资产、六种目标协议各自拥有精确能力，不互相复制不同口径的结果。"""
    req = request(asset, target, horizon)
    quoted = quote_forecast(req, settings())
    result = validate_forecast(
        json.dumps(prediction(req, quoted)), req, quoted["evidence_catalog"]
    )
    assert quoted["asset"] == asset and quoted["horizon"] == horizon
    probability = target in {"FUND_NAV_UP", "ETF_FORWARD_OPEN", "ETF_LEGACY_CLOSE"}
    key = "probability_up" if probability else "point_return"
    assert result["output"][key] == "0"
    assert result["facts"][0]["text"] == quoted["evidence_catalog"][0]["text"]
    assert result["probability_validation_status"] == (
        "UNCALIBRATED" if probability else "NOT_PROVIDED"
    )


@pytest.mark.parametrize(
    "asset,target,horizon,input_hash,request_hash",
    [
        (
            "008701",
            "FUND_NAV_CHANGE",
            20,
            "914031f39001c842732938826d0e21f6d334f69c17c50864718e333e20e08d6e",
            "513d53b4a94559952347a47af07dec0e8c4b0aff84d910df2c974ff103ccdcf6",
        ),
        (
            "510300",
            "ETF_FORWARD_OPEN",
            1,
            "935cd1e9e3fa6e4f97c6b4736ae4bad2be539b82ce7e3459b16c7a51bf8f9bfa",
            "04aa0db7c1f3b0dae8791c4754e984d625b40520e74ac571e45e79e25c7ca67b",
        ),
        (
            "510300",
            "ETF_LEGACY_CLOSE",
            5,
            "6019e14ab62ef8ddd77eb898efa8ba677e6c8d01c6bc05d110b550664f03ec90",
            "7ce94e6884f3dac749b8e441ffec4da471e2f840f96ef774840f7728c920a275",
        ),
        (
            "Au99.99",
            "GOLD_BENCHMARK_RETURN",
            63,
            "a42fec5c087162a019c5e32d62ba9d2ec5f11154f37d3f34e4ce77f97d6ae405",
            "2d97f6e9c50caeb651313fa380355e7343b21d36977a00c7ed6619ad38d0931e",
        ),
    ],
)
def test_legacy_quotes_keep_pre_expansion_fingerprints(
    asset, target, horizon, input_hash, request_hash
):
    """对照改动前实际报价哈希，能力扩展不能让旧目标同资料再次消耗费用。"""
    quoted = quote_forecast(request(asset, target, horizon), settings())
    assert quoted["input_sha256"] == input_hash
    assert quoted["request_fingerprint"] == request_hash
    assert quoted["prompt_version"] == "independent-forecast-v1"
    assert quoted["schema_version"] == "independent-forecast-output-v1"


@pytest.mark.parametrize(
    "asset,target,horizon",
    [
        ("460300", "FUND_NAV_UP", 5),
        ("510300", "ETF_CLOSE_RETURN", 20),
        ("159915", "ETF_CLOSE_RETURN", 60),
    ],
)
def test_new_targets_run_independently_with_exact_output_capability(
    asset, target, horizon
):
    """新方向和幅度目标真实走单次内存HTTP，输入只有行情与证据，输出不能混用。"""
    req, quoted = prepared(request(asset, target, horizon))
    calls = []

    def handle(message):
        """检查新增目标明确时间口径且不读取其他算法结果。"""
        body = json.loads(message.content)
        calls.append(body)
        assert "5个有效观测不是5个自然日" in body["messages"][0]["content"]
        assert "不是完整分红总回报" in body["messages"][0]["content"]
        user = json.loads(body["messages"][1]["content"])
        assert user["target"] == target and user["horizon"] == horizon
        assert "model_results" not in user
        return httpx.Response(200, json=envelope(prediction(req, quoted)))

    result = analyze_forecast(req, settings(), transport=httpx.MockTransport(handle))
    assert result["status"] == "SUCCEEDED" and len(calls) == 1
    assert result["prompt_version"] == "independent-forecast-v2"
    assert result["schema_version"] == "independent-forecast-output-v2"
    assert result["nature"] == "MOCK"
    expected = (
        {"point_return": None, "probability_up": "0"}
        if target == "FUND_NAV_UP"
        else {"point_return": "0", "probability_up": None}
    )
    assert result["result"]["output"] == expected
    for output in (
        {"point_return": "0.01", "probability_up": "0.6"},
        {"point_return": None, "probability_up": None},
        {"point_return": "0.01", "probability_up": None}
        if target == "FUND_NAV_UP"
        else {"point_return": None, "probability_up": "0.6"},
    ):
        raw = prediction(req, quoted)
        raw["output"] = output
        with pytest.raises(ValueError):
            validate_forecast(json.dumps(raw), req, quoted["evidence_catalog"])


@pytest.mark.parametrize(
    "asset,target,horizon",
    [
        ("510300", "FUND_NAV_UP", 5),
        ("008701", "FUND_NAV_UP", 20),
        ("008701", "ETF_CLOSE_RETURN", 20),
        ("510300", "ETF_CLOSE_RETURN", 5),
    ],
)
def test_new_targets_reject_cross_asset_or_horizon_contracts(asset, target, horizon):
    """资产域和期限是训练目标的一部分，不能用旧目标的参数冒充新能力。"""
    with pytest.raises(ValueError):
        request(asset, target, horizon)


def test_new_targets_preserve_observation_and_exchange_date_boundaries():
    """新中期目标可等待跨年交易日历，但保留观测锚点和既定交易日长度。"""
    fund = request("008701", "FUND_NAV_UP", 5).model_copy(update={"target_date": None})
    assert quote_forecast(fund, settings())["input_payload"]["target_date"] is None
    etf = request("510300", "ETF_CLOSE_RETURN", 20)
    quoted = quote_forecast(etf.model_copy(update={"target_date": None}), settings())
    assert quoted["input_payload"]["target_date"] is None
    assert quoted["input_payload"]["horizon"] == 20
    with pytest.raises(ValueError):
        quote_forecast(
            etf.model_copy(update={"anchor_date": date(2026, 10, 27)}), settings()
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "asset",
        "horizon",
        "other_model",
        "duplicate_price",
        "future_fetch",
        "future_price",
        "preknown_price",
        "wrong_anchor",
        "duplicate_news",
        "late_forward",
    ],
)
def test_rejects_foreign_predictions_and_time_leaks(mutation):
    """禁止其他个体输出入参，也拒绝未来可用价格和错位的前瞻锚点。"""
    req = (
        request("510300", "ETF_FORWARD_OPEN", 1)
        if mutation == "late_forward"
        else request()
    )
    body = req.model_dump()
    if mutation == "asset":
        body["asset"] = "510300"
    elif mutation == "horizon":
        body["horizon"] = 1
    elif mutation == "other_model":
        body["model_results"] = [{"model": "LightGBM", "point_return": "0.2"}]
    elif mutation == "duplicate_price":
        body["prices"] *= 2
    elif mutation == "future_fetch":
        body["prices"][0]["available_at"] = req.cutoff + timedelta(seconds=1)
    elif mutation == "future_price":
        body["prices"][0]["date"] += timedelta(days=1)
    elif mutation == "preknown_price":
        body["prices"][0]["available_at"] = req.cutoff - timedelta(days=1)
    elif mutation == "wrong_anchor":
        body["anchor_date"] -= timedelta(days=1)
    elif mutation == "duplicate_news":
        body["news"] *= 2
    else:
        body["cutoff"] = datetime(2026, 10, 29, 2, tzinfo=UTC)
    with pytest.raises(ValueError):
        ForecastAnalysisRequest.model_validate(body)


def test_polling_deduplicates_only_identical_evidence_and_target():
    """同证据轮询不重新收费；真实价格、来源内容、目标或提示版本变化必须分离。"""
    req = request()
    base = quote_forecast(req, settings())
    later = quote_forecast(
        req.model_copy(update={"cutoff": req.cutoff + timedelta(hours=1)}), settings()
    )
    for key in ("input_sha256", "evidence_snapshot_id", "request_fingerprint"):
        assert base[key] == later[key]
    for update in ({"horizon": 60}, {"target_version": "NATIVE_V2"}):
        assert (
            base["request_fingerprint"]
            != quote_forecast(req.model_copy(update=update), settings())[
                "request_fingerprint"
            ]
        )
    revised = req.model_copy(deep=True)
    revised.prices[0].value = Decimal("1.24")
    assert (
        base["request_fingerprint"]
        != quote_forecast(revised, settings())["request_fingerprint"]
    )
    revised = req.model_copy(deep=True)
    revised.news[0].summary += "新增细节。"
    assert (
        base["request_fingerprint"]
        != quote_forecast(revised, settings())["request_fingerprint"]
    )
    assert (
        base["request_fingerprint"]
        != quote_forecast(req, replace(settings(), model_version="VERSION_2"))[
            "request_fingerprint"
        ]
    )
    assert "cutoff" not in base["input_payload"]


@pytest.mark.parametrize(
    "reason", ["future", "unverified", "stale", "unrelated", "administrative"]
)
def test_ineligible_news_never_enters_paid_input(reason):
    """有文章并不等于有合法证据，无法支持当前资产时明确不预测。"""
    req = request()
    item = req.news[0]
    if reason == "future":
        item.fetched_at = req.cutoff + timedelta(seconds=1)
    elif reason == "unverified":
        item.publication_verified = False
    elif reason == "stale":
        item.published_at = req.cutoff - timedelta(days=31)
    elif reason == "unrelated":
        item.title, item.summary = "短债资料", "债券收益率曲线变化。"
    else:
        item.title = "008701采购公告"
    req, quoted = prepared(req)
    assert quoted["eligible_source_ids"] == []
    result = analyze_forecast(
        req,
        settings(),
        transport=httpx.MockTransport(lambda _: pytest.fail("不得外呼")),
    )
    assert result["status"] == "MISSING" and result["error_code"] == "NO_QUALIFIED_NEWS"
    assert result["attempts"] == 0 and "result" not in result


@pytest.mark.parametrize("available", [True, False])
def test_success_or_abstention_preserves_identity_and_cost(available):
    """HTTP只调用一次，合法弃权不填充中性值，事实由服务端冻结目录回填。"""
    req, quoted = prepared(request())
    calls = []

    def handle(message):
        """检查实际送模载荷和报价完全一致，绝不提供量化预测结果。"""
        calls.append(message)
        body = json.loads(message.content)
        user = json.loads(body["messages"][1]["content"])
        assert user == {
            **quoted["input_payload"],
            "evidence": quoted["evidence_catalog"],
        }
        assert body["thinking"] == {"type": "disabled"}
        assert "其他模型结论" in body["messages"][0]["content"]
        assert "模型" not in json.dumps(user, ensure_ascii=False)
        return httpx.Response(200, json=envelope(prediction(req, quoted, available)))

    result = analyze_forecast(req, settings(), transport=httpx.MockTransport(handle))
    assert (
        result["status"] == "SUCCEEDED"
        and result["nature"] == "MOCK"
        and len(calls) == 1
    )
    assert result["result"]["status"] == ("AVAILABLE" if available else "ABSTAIN")
    assert result["billing_uncertain"] is False and result["attempts"] == 1
    assert Decimal(result["cost_usd"]) == Decimal("0.000048")
    for key in ("input_sha256", "evidence_snapshot_id", "request_fingerprint"):
        assert result[key] == quoted[key]
    assert result["input_cutoff"] == req.cutoff.isoformat()
    assert result["target_version"] == req.target_version
    assert "raw_output" not in result
    if not available:
        assert result["result"]["output"] == {
            "point_return": None,
            "probability_up": None,
        }
        assert result["result"]["facts"] == []


@pytest.mark.parametrize(
    "mutation",
    [
        "unknown_evidence",
        "wrong_target",
        "wrong_asset",
        "wrong_horizon",
        "bad_probability",
        "nan",
        "exponent",
        "numeric_type",
        "impossible_loss",
        "missing_point",
        "abstain_value",
        "no_counter_factor",
        "blank_summary",
        "extra_output",
    ],
)
def test_invalid_model_output_never_becomes_prediction(mutation):
    """引用、目标、数值能力、收益范围和说明中的缺陷不能伪装为成功结果。"""
    req, quoted = prepared(request())
    result = prediction(req, quoted)
    if mutation == "unknown_evidence":
        result["evidence_ids"] = ["EV_unknown"]
    elif mutation == "wrong_target":
        result["target"] = "GOLD_BENCHMARK_RETURN"
    elif mutation == "wrong_asset":
        result["asset"] = "007169"
    elif mutation == "wrong_horizon":
        result["horizon"] = 60
    elif mutation == "bad_probability":
        result["output"]["probability_up"] = "1.01"
    elif mutation in {
        "nan",
        "exponent",
        "numeric_type",
        "impossible_loss",
        "missing_point",
    }:
        result["output"]["point_return"] = {
            "nan": "NaN",
            "exponent": "1e-2",
            "numeric_type": 0.2,
            "impossible_loss": "-1.1",
            "missing_point": None,
        }[mutation]
    elif mutation == "abstain_value":
        result["status"] = "ABSTAIN"
    elif mutation == "no_counter_factor":
        result["counter_factors"] = []
    elif mutation == "blank_summary":
        result["summary"] = "   "
    else:
        result["stance"] = "BULLISH"
    with pytest.raises(ValueError):
        validate_forecast(json.dumps(result), req, quoted["evidence_catalog"])


def test_no_http_without_matching_quote_or_enabled_configuration(monkeypatch):
    """报价身份、功能开关和凭据是发起外呼前的必要条件。"""
    req, _ = prepared(request(nature="REAL"))
    monkeypatch.setattr(
        httpx, "Client", lambda *args, **kwargs: pytest.fail("不得创建HTTP客户端")
    )
    assert (
        analyze_forecast(request(nature="REAL"), settings())["error_code"]
        == "INPUT_SNAPSHOT_MISMATCH"
    )
    for config in (replace(settings(), enabled=False), replace(settings(), api_key="")):
        result = analyze_forecast(req, config)
        assert (
            result["error_code"] == "PROVIDER_DISABLED_OR_KEY_MISSING"
            and result["attempts"] == 0
        )
    with pytest.raises(ValueError):
        analyze_forecast(req.model_copy(update={"reserved_usd": "0"}), settings())
    with pytest.raises(ValueError):
        analyze_forecast(req.model_copy(update={"reserved_usd": "NaN"}), settings())
    with pytest.raises(ValueError):
        analyze_forecast(req, replace(settings(), base_url="https://untrusted.example"))


@pytest.mark.parametrize(
    "change",
    [
        {"model": "changed-model"},
        {"model_version": "changed-version"},
        {"max_tokens": 500},
    ],
)
def test_quote_model_identity_drift_is_blocked_before_charge(monkeypatch, change):
    """模型与生成预算属于报价身份，运行时配置漂移不能消耗旧的共享预算预留。"""
    req, _ = prepared(request(nature="REAL"))
    monkeypatch.setattr(
        httpx, "Client", lambda *args, **kwargs: pytest.fail("不得创建HTTP客户端")
    )
    result = analyze_forecast(req, replace(settings(), **change))
    assert result["error_code"] == "INPUT_SNAPSHOT_MISMATCH" and result["attempts"] == 0


def test_etf_requires_exact_target_date_and_probability_only():
    """交易日终点缺失时不猜自然日，ETF也不得越过其已声明的输出能力。"""
    req = request("510300", "ETF_FORWARD_OPEN", 1)
    with pytest.raises(ValueError):
        quote_forecast(req.model_copy(update={"target_date": None}), settings())
    quoted = quote_forecast(req, settings())
    result = prediction(req, quoted)
    result["output"]["point_return"] = "0.01"
    with pytest.raises(ValueError):
        validate_forecast(json.dumps(result), req, quoted["evidence_catalog"])
    result["output"] = {"point_return": None, "probability_up": None}
    with pytest.raises(ValueError):
        validate_forecast(json.dumps(result), req, quoted["evidence_catalog"])


def test_real_prediction_rejects_mock_news_and_mutated_future_prices(monkeypatch):
    """Python内部调用也重验修改后的模型对象，不能绕过FastAPI的时点和来源检查。"""
    req, _ = prepared(request())
    monkeypatch.setattr(
        httpx, "Client", lambda *args, **kwargs: pytest.fail("不得创建HTTP客户端")
    )
    with pytest.raises(ValueError):
        analyze_forecast(req, settings())
    req.prices[0].available_at = req.cutoff + timedelta(seconds=1)
    with pytest.raises(ValueError):
        quote_forecast(req, settings())


@pytest.mark.parametrize(
    "failure",
    [
        "503",
        "429",
        "timeout",
        "invalid_output",
        "truncated",
        "secret",
        "no_usage",
        "bad_usage",
        "list_body",
        "null_usage",
        "bad_choice",
    ],
)
def test_provider_failures_are_single_attempt_and_billing_is_conservative(failure):
    """超时与未知账单保留预留，已收费的无效输出保留费用，任何失败都不重试。"""
    req, quoted = prepared(request())
    calls = []

    def handle(message):
        """只制造内存故障，从不连接DeepSeek。"""
        calls.append(message)
        if failure in {"503", "429"}:
            return httpx.Response(int(failure))
        if failure == "timeout":
            raise httpx.ReadTimeout("测试超时")
        body = envelope(prediction(req, quoted))
        if failure == "invalid_output":
            body["choices"][0]["message"]["content"] = "not json"
        elif failure == "truncated":
            body["choices"][0]["finish_reason"] = "length"
        elif failure == "secret":
            body["choices"][0]["message"]["content"] = settings().api_key
        elif failure == "no_usage":
            del body["usage"]
        elif failure == "bad_usage":
            body["usage"]["prompt_tokens"] = -1
        elif failure == "null_usage":
            body["usage"] = None
        elif failure == "list_body":
            body = []
        elif failure == "bad_choice":
            body["choices"] = [None]
        return httpx.Response(200, json=body)

    result = analyze_forecast(req, settings(), transport=httpx.MockTransport(handle))
    assert len(calls) == 1 and result["attempts"] == 1
    assert settings().api_key not in json.dumps(result)
    uncertain = failure in {
        "503",
        "timeout",
        "no_usage",
        "bad_usage",
        "null_usage",
        "list_body",
    }
    assert result["billing_uncertain"] == uncertain
    if failure in {"no_usage", "bad_usage", "null_usage"}:
        assert result["status"] == "SUCCEEDED" and Decimal(result["cost_usd"]) == 0
    else:
        assert result["status"] == "FAILED" and "result" not in result
    if failure in {"invalid_output", "truncated", "secret", "bad_choice"}:
        assert Decimal(result["cost_usd"]) > 0


def test_internal_endpoints_require_token_and_hide_invalid_request(monkeypatch):
    """统一入口继承内部鉴权，坏请求不回显敏感正文。"""
    from etf_quant import api
    from fastapi.testclient import TestClient

    monkeypatch.setenv("ETF_INTERNAL_TOKEN", "isolated-internal-token-longer-than-32")
    req = request().model_dump(mode="json")
    with TestClient(api.app) as client:
        assert client.post("/analysis/forecast/quote", json=req).status_code == 401
        assert client.post("/analysis/forecast/run", json=req).status_code == 401
        monkeypatch.setattr(
            api, "quote_forecast", lambda payload: {"asset": payload.asset}
        )
        headers = {"x-internal-token": "isolated-internal-token-longer-than-32"}
        assert client.post(
            "/analysis/forecast/quote", json=req, headers=headers
        ).json() == {"asset": "008701"}
        req["model_results"] = "private-model-output"
        response = client.post("/analysis/forecast/quote", json=req, headers=headers)
        assert (
            response.status_code == 422 and "private-model-output" not in response.text
        )
