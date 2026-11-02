"""基金主体防串用回归；构造材料与历史保存响应均不发网络请求。"""

import json
from pathlib import Path

import httpx
import pytest
from etf_ai.fund import _messages, analyze_fund, quote_fund, validate_fund
from etf_ai.fund_identity import fund_identity, require_fund_mechanism
from test_fund_evidence import output, prepared, request, settings


def bond_request():
    """构造能合法供债基使用的普通利率新闻，黄金仅作为宏观比较。"""
    req = request(code="007169")
    req.news[0].title = "利率与债券市场观察"
    req.news[0].summary = "利率下降可能影响债券价格，也可能影响金价。"
    return req


def gold_disclosure(code="007169"):
    """模拟黄金基金专属季报，正文同时包含会误命中的债券和利率词。"""
    req = request(code=code)
    req.news[0].title = "华夏黄金交易型开放式证券投资基金发起式联接基金季度报告"
    req.news[0].summary = "本基金跟踪Au99.99。利率变化与债券收益率也影响黄金。"
    req.news[0].metadata = {
        "fund_code": "008701",
        "content_scope": "FUND_DISCLOSURE_PDF_EXCERPT_NOT_LIVE_MARKET_NEWS",
    }
    return req


@pytest.mark.parametrize("missing_metadata", [False, True])
def test_other_fund_disclosure_rejected_despite_rate_keyword(missing_metadata):
    """已知异基公告按真实主体拒收，缺元数据时仍能从正式标题识别。"""
    req = gold_disclosure()
    if missing_metadata:
        req.news[0].metadata = {}
    quoted = quote_fund(req, settings())
    assert quoted["eligible_source_ids"] == []
    assert quoted["rejected"][0]["reason"] == "FUND_SOURCE_SUBJECT_MISMATCH"


@pytest.mark.parametrize(
    "source_name", ["CHINAAMC_008701", "CHINAAMC_008701_DATE_PRECISION"]
)
def test_source_label_preserves_disclosure_owner(source_name):
    """缺少标题身份和元数据时，已登记的专属采集来源仍不能借给债基。"""
    req = gold_disclosure()
    req.news[0].title = "季度运作报告"
    req.news[0].metadata = {}
    req.news[0].source_name = source_name
    assert (
        quote_fund(req, settings())["rejected"][0]["reason"]
        == "FUND_SOURCE_SUBJECT_MISMATCH"
    )


def test_ambiguous_and_conflicting_disclosure_fail_closed():
    """无法确定是哪只基金的季报宁可不足，显式归属与标题冲突也不放行。"""
    req = gold_disclosure()
    req.news[0].title = "某基金季度报告"
    req.news[0].metadata = {}
    assert (
        quote_fund(req, settings())["rejected"][0]["reason"]
        == "FUND_SOURCE_SUBJECT_UNKNOWN"
    )
    req = gold_disclosure()
    req.news[0].metadata["fund_code"] = "007169"
    assert (
        quote_fund(req, settings())["rejected"][0]["reason"]
        == "FUND_SOURCE_SUBJECT_CONFLICT"
    )


def test_no_evidence_does_not_construct_http_client(monkeypatch):
    """筛掉其他基金专属资料后零调用，不为制造解释去付费。"""
    monkeypatch.setenv("ETF_FUND_LLM_ENABLED", "true")
    req, _ = prepared(gold_disclosure())
    monkeypatch.setattr(
        httpx, "Client", lambda *args, **kwargs: pytest.fail("禁止外呼")
    )
    result = analyze_fund(req, settings())
    assert result["status"] == "MISSING"
    assert result["error_code"] == "NO_QUALIFIED_FUND_NEWS"
    assert result["attempts"] == 0 and result["cost_usd"] == "0"


def test_prompt_snapshot_and_catalog_carry_trusted_identity():
    """可信名称、资产类型、机制和资料归属进入提示词及快照，变更不能沿用旧指纹。"""
    req = bond_request()
    quoted = quote_fund(req, settings())
    prompt = json.loads(
        _messages(quoted["evidence_catalog"], "007169", 20)[1]["content"]
    )
    assert prompt["fund_identity"] == fund_identity("007169")
    assert prompt["fund_identity"]["asset_class"] == "BOND"
    assert quoted["schema_version"] == "fund-news-evidence-v2"
    assert quoted["prompt_version"] == "fund-evidence-explanation-v3"
    entry = quoted["evidence_catalog"][0]
    assert entry["material_scope"] == "MACRO" and entry["subject_fund_code"] is None
    assert entry["source_title"] == req.news[0].title
    # 相同文字增加经过采集识别的专属归属后，输入快照和请求指纹都必须变化。
    req.news[0].metadata["fund_code"] = "007169"
    revised = quote_fund(req, settings())
    assert revised["evidence_snapshot_id"] != quoted["evidence_snapshot_id"]
    assert revised["request_fingerprint"] != quoted["request_fingerprint"]


@pytest.mark.parametrize(
    "mechanism",
    [
        "本基金跟踪Au99.99，净值主要由金价决定。",
        "本基金是黄金ETF联接基金。",
        "007169主要投资黄金现货。",
        "净值主要反映国内金价。",
        "本基金跟踪沪深300股票指数。",
        "本基金不是债券基金而是黄金ETF联接基金。",
        "易方达中债1–3年国开行债券指数A的净值主要由金价决定。",
        "本基金不直接持有黄金，而是借助目标ETF跟踪黄金价格。",
    ],
)
def test_correct_code_and_valid_citations_cannot_hide_wrong_mechanism(mechanism):
    """编号和引用均正确时，明确矛盾的投资机制仍必须拒绝。"""
    quoted = quote_fund(bond_request(), settings())
    raw = output(quoted)
    raw["fund_code"] = "007169"
    raw["mechanisms"][0]["mechanism"] = mechanism
    with pytest.raises(ValueError, match="FUND_MECHANISM_MISMATCH"):
        validate_fund(json.dumps(raw), quoted["evidence_catalog"], "007169", 20)


@pytest.mark.parametrize(
    "mechanism",
    [
        "本基金持有国开行债券，利率下降可能推高债券价格。",
        "金价与债券都可能受到利率变化影响，但不能把金价直接当成本基金收益。",
        "本基金不跟踪黄金，其净值主要反映债券价格和票息。",
        "黄金基金跟踪Au99.99，而本基金为国开行债券指数基金。",
        "本基金不是黄金基金而是国开行债券指数基金。",
        "本基金净值主要反映债券价格，而黄金基金不是债基而是黄金ETF联接基金。",
    ],
)
def test_legitimate_bond_mechanism_and_macro_gold_comparison_pass(mechanism):
    """债券机制、否定错误机制和宏观黄金比较不应被关键词黑名单误杀。"""
    quoted = quote_fund(bond_request(), settings())
    raw = output(quoted)
    raw["fund_code"] = "007169"
    raw["mechanisms"][0]["mechanism"] = mechanism
    result = validate_fund(json.dumps(raw), quoted["evidence_catalog"], "007169", 20)
    assert (
        result["status"] == "AVAILABLE"
        and result["fund_identity"]["asset_class"] == "BOND"
    )
    assert result["schema_version"] == quoted["schema_version"]


def test_own_gold_disclosure_and_mechanism_are_valid():
    """008701可以使用自己的黄金公告，不能为了阻断债基串用破坏正常黄金分析。"""
    quoted = quote_fund(gold_disclosure("008701"), settings())
    assert quoted["eligible_source_ids"]
    assert quoted["evidence_catalog"][0]["subject_fund_code"] == "008701"
    raw = output(quoted)
    raw["mechanisms"][0]["mechanism"] = (
        "本基金通过黄金ETF反映国内金价，利率变化可能影响黄金。"
    )
    result = validate_fund(json.dumps(raw), quoted["evidence_catalog"], "008701", 20)
    assert result["fund_identity"]["asset_class"] == "GOLD"


def test_validator_rechecks_source_ownership():
    """即使调用者绕过准备过滤，引用的目录归属不符也不能形成有效结果。"""
    quoted = quote_fund(bond_request(), settings())
    raw = output(quoted)
    raw["fund_code"] = "007169"
    entry = quoted["evidence_catalog"][0]
    entry.update(material_scope="FUND_SPECIFIC", subject_fund_code="008701")
    with pytest.raises(ValueError, match="FUND_SOURCE_SUBJECT_MISMATCH"):
        validate_fund(json.dumps(raw), quoted["evidence_catalog"], "007169", 20)


def test_saved_007169_gold_analysis_is_rejected():
    """2026-10-29真实坏响应回放：保存引用恢复投影，明确黄金机制必须被当前规则拒收。"""
    fixture = json.loads(
        (
            Path(__file__).parent
            / "fixtures/fund_007169_identity_mismatch_20261029.json"
        ).read_text()
    )
    catalog = [
        {"id": fact["evidence_id"], "text": fact["quote"], **fact}
        for fact in fixture["saved_facts"]
    ]
    with pytest.raises(ValueError, match="FUND_MECHANISM_MISMATCH"):
        validate_fund(fixture["raw_output"], catalog, "007169", 20)


def test_wrong_mechanism_response_never_becomes_valid(monkeypatch):
    """内存供应商返回错误机制时保留原响应和费用信息，不能转换成VALID或重试。"""
    monkeypatch.setenv("ETF_FUND_LLM_ENABLED", "true")
    req, quoted = prepared(bond_request())
    raw = output(quoted)
    raw["fund_code"] = "007169"
    raw["mechanisms"][0]["mechanism"] = "本基金跟踪Au99.99。"
    calls = []

    def handle(message):
        """仅在内存提供一次结构合法但主体错误的模型响应。"""
        calls.append(message)
        return httpx.Response(
            200,
            json={
                "usage": {"prompt_tokens": 80, "completion_tokens": 20},
                "choices": [
                    {"finish_reason": "stop", "message": {"content": json.dumps(raw)}}
                ],
            },
        )

    result = analyze_fund(req, settings(), transport=httpx.MockTransport(handle))
    assert len(calls) == 1 and result["status"] == "FAILED"
    assert result["error_code"] == "INVALID_OUTPUT"
    assert result["raw_output"] and "analysis" not in result
    assert result["input_tokens"] == 80 and not result["billing_uncertain"]


def test_other_asset_identity_is_also_checked():
    """同一三资产规则适用于黄金和权益，避免只给007169打一条专用补丁。"""
    with pytest.raises(ValueError, match="FUND_MECHANISM_MISMATCH"):
        require_fund_mechanism("008701", ["本基金跟踪国开行债券指数。"])
    with pytest.raises(ValueError, match="FUND_MECHANISM_MISMATCH"):
        require_fund_mechanism("460300", ["净值主要由金价决定。"])
