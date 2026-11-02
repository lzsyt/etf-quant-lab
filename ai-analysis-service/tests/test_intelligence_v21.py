"""V2.1新闻输入选择及语义证据边界，全部为隔离数据、零付费。"""

import json
from datetime import timedelta

import pytest
from etf_ai.forward import ForwardAnalysisRequest, quote_forward
from etf_ai.intelligence import MAX_INPUT_TOKEN_BOUND, enrich_item, select_intelligence
from test_forward_news import batch, request, run, settings


def news(req, index, title=None, summary=None, source="TEST", url=None):
    """复制明确标记的Mock快照，不生成真实市场材料。"""
    return req.news[0].model_copy(
        update={
            "id": "fixture-" + str(index),
            "title": title or "货币政策公告" + str(index),
            "summary": summary or req.news[0].summary,
            "source_url": url or "https://www.pbc.gov.cn/fixture-" + str(index),
            "source_name": source,
        }
    )


def test_input_candidates_are_separate_from_article_and_token_limits():
    """150候选先分类再排序，超过文章/字节上界明确排除而非整批失败。"""
    req = request()
    req.news = [news(req, i, summary="公开数据的隔离摘要。" * 100) for i in range(100)]
    quoted = quote_forward(req, settings())
    assert quoted["relevance"]["candidate_count"] == 100
    assert 0 < quoted["relevance"]["accepted_count"] <= 15
    assert quoted["input_token_upper_bound"] <= MAX_INPUT_TOKEN_BOUND
    assert len(quoted["eligible_source_ids"]) == quoted["relevance"]["accepted_count"]
    assert {item["reason"] for item in quoted["relevance"]["rejected"]} >= {
        "ARTICLE_LIMIT",
        "INPUT_TOKEN_BUDGET",
    }
    actual = json.loads(json.loads(quoted["input_text"])[1]["content"])
    assert set(quoted["eligible_source_ids"]) == {item["id"] for item in actual["news"]}


def test_official_duplicate_event_not_counted_repeatedly_and_revisions_retained():
    """跨源同事件优先官方；同URL新修订按首次获取时间选取，保留排除证据。"""
    req = request()
    original = news(
        req, 1, title="央行公布降准政策", url="https://www.pbc.gov.cn/policy"
    )
    revised = original.model_copy(
        update={
            "id": "revision",
            "fetched_at": original.fetched_at + timedelta(minutes=2),
        }
    )
    duplicate = news(
        req, 2, title=original.title, url="https://finance.sina.com.cn/policy"
    )
    req.news = [original, duplicate, revised]
    selected, audit = select_intelligence(req)
    assert [item.id for item in selected.news] == ["revision"]
    assert audit["event_count"] == 1
    assert set(audit["events"][0]["source_ids"]) == {"revision", "fixture-2"}
    assert {item["reason"] for item in audit["rejected"]} == {
        "SUPERSEDED_ARTICLE_VERSION",
        "DUPLICATE_EVENT",
    }


def test_distinct_values_and_opposite_events_are_not_merged():
    """同主题不等于同事件，数值和政策方向变化不能去重丢失。"""
    req = request()
    req.news = [
        news(req, i, title=title)
        for i, title in enumerate(
            ["公布降准0.5个百分点", "公布降准0.25个百分点", "公布不降准0.5个百分点"]
        )
    ]
    selected, audit = select_intelligence(req)
    assert len(selected.news) == audit["event_count"] == 3


def test_classification_is_only_candidate_not_semantic_score():
    """行业话题只授予进一步语义判断资格，不提前认为创业板看涨。"""
    req = request("人工智能行业政策说明")
    enriched = enrich_item(req.news[0], req.cutoff)
    assert enriched.metadata["category"] == "INDUSTRY_POLICY"
    assert set(enriched.metadata["related_symbols"]) == set(req.symbols)
    assert "sentiment_score" not in enriched.metadata
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


def test_keyword_hit_can_be_semantically_irrelevant_without_a_score():
    """关键词命中而语义无关时，各ETF允许明确无新闻，不能强行打分。"""
    req = request("货币政策历史展览说明")
    payload = batch(req)
    for item in payload["items"]:
        item.update(
            status="NO_RELEVANT_NEWS",
            relevance_level="none",
            sentiment_score=None,
            risk_level=None,
            direction="unknown",
            evidence_ids=[],
            relevant_source_ids=[],
        )
    assert run(req, payload)["status"] == "SUCCEEDED"


@pytest.mark.parametrize(
    "change", ["invented_quote", "no_facts", "none_relevance", "missing_explanation"]
)
def test_success_requires_traceable_facts_and_semantic_evidence(change):
    """即使旧策略字段合法，新版缺实质证据或伪造事实也应拒绝。"""
    req = request()
    payload = batch(req)
    item = payload["items"][0]
    if change == "invented_quote":
        item["evidence_ids"] = ["EV_invented"]
    elif change == "no_facts":
        item["evidence_ids"] = []
    elif change == "none_relevance":
        item["relevance_level"] = "none"
    else:
        del item["uncertainty"]
    assert run(req, payload)["status"] == "FAILED"


def test_future_input_rejected_even_after_model_copy_and_summary_truthful():
    """再次检查复制模型后的双时间边界；仅摘要请求不宣称完整正文。"""
    req = request()
    req.news[0].fetched_at = req.cutoff + timedelta(seconds=1)
    with pytest.raises(ValueError, match="截止"):
        quote_forward(req, settings())
    req = request()
    quoted = quote_forward(req, settings())
    assert quoted["input_content_type"] == "SUMMARY"
    req.news[0].metadata = {
        "content_type": "EXCERPT",
        "content": "官方公开内容的有限摘录",
    }
    quoted = quote_forward(req, settings())
    actual = json.loads(json.loads(quoted["input_text"])[1]["content"])
    assert quoted["input_content_type"] == "MIXED"
    assert actual["news"][0]["content_type"] == "EXCERPT"


def test_new_protocol_accepts_old_request_envelope_but_versions_new_output():
    """旧请求形状仍可调用，新输出版本显式记录，不冒称旧schema。"""
    data = request().model_dump()
    data["schema_version"] = "etf-symbol-events-v2"
    quoted = quote_forward(ForwardAnalysisRequest(**data), settings())
    assert quoted["schema_version"] == "etf-symbol-evidence-v2.1.2"
    assert quoted["prompt_version"] == "etf-symbol-h1-v2.1.3"


def test_similar_title_different_etfs_and_unrequested_news_are_not_mixed():
    """同标题不同ETF不聚合；单品种请求不能因别的ETF新闻触发收费。"""
    req = request()
    req.news = [
        news(req, 1, title="ETF申购赎回公告", summary="510300申购赎回公告内容"),
        news(req, 2, title="ETF申购赎回公告", summary="159915申购赎回公告内容"),
    ]
    selected, audit = select_intelligence(req)
    assert len(selected.news) == 2 and audit["event_count"] == 2
    req.symbols = ["510500"]
    quoted = quote_forward(req, settings())
    assert quoted["eligible_source_ids"] == []
    assert all(
        row["reason"] == "NO_REQUESTED_ETF_TOPIC"
        for row in quoted["relevance"]["rejected"]
    )


def test_truncated_full_text_never_remains_full_text():
    """对接口直接传入的超长内容也保持真实类型，而非仅依赖来源解析器。"""
    req = request()
    req.news[0].metadata = {"content": "甲" * 8000, "content_type": "FULL_TEXT"}
    enriched = enrich_item(req.news[0], req.cutoff)
    assert enriched.metadata["content_type"] == "EXCERPT"
    assert len(enriched.metadata["content"]) == 6000


def test_long_high_priority_article_cannot_exhaust_whole_batch():
    """长官方正文按模型摘录上限分配预算，不能造成全部相关候选被删除。"""
    req = request()
    req.news[0].metadata = {"content": "政策数据。" * 1500, "content_type": "EXCERPT"}
    quoted = quote_forward(req, settings())
    assert quoted["eligible_source_ids"] == ["fixture-news-1"]
    actual = json.loads(json.loads(quoted["input_text"])[1]["content"])
    assert (
        sum(len(e["text"]) for e in actual["evidence"] if e["field"] == "content")
        <= 1600
    )
    assert any(e["field"] == "content" for e in actual["evidence"])
    assert actual["news"][0]["content_type"] == "EXCERPT"


def test_irrelevant_revision_never_resurrects_old_relevant_version():
    """新版纠正为无关时，也要先淘汰旧快照，再进行主题筛选。"""
    req = request()
    old = req.news[0]
    revised = old.model_copy(
        update={
            "id": "revised",
            "title": "机关采购公告",
            "fetched_at": old.fetched_at + timedelta(minutes=1),
        }
    )
    req.news = [old, revised]
    selected, audit = select_intelligence(req)
    assert selected.news == []
    assert {r["reason"] for r in audit["rejected"]} == {
        "SUPERSEDED_ARTICLE_VERSION",
        "ADMINISTRATIVE_NEWS",
    }


def test_official_statistical_results_rank_above_routine_meetings():
    """明确统计结果不因标题缺少发布二字而输给无政策公布的礼节性会见。"""
    req = request()
    data = news(
        req,
        1,
        title="前三季度国内生产总值初步核算结果",
        url="https://www.stats.gov.cn/sj/zxfb/data.html",
    )
    meeting = news(
        req,
        2,
        title="会见资本市场来访代表",
        url="https://www.csrc.gov.cn/csrc/meeting.html",
    )
    assert (
        enrich_item(data, req.cutoff).metadata["importance_score"]
        > enrich_item(meeting, req.cutoff).metadata["importance_score"]
    )


def test_blank_facts_and_explanations_cannot_pass_substring_validation():
    """空字符串不能借助它总是原文子串的特性冒充可追溯事实。"""
    req = request()
    for field in ("facts", "rationale", "impact_logic", "uncertainty"):
        payload = batch(req)
        if field == "facts":
            payload["items"][0]["evidence_ids"] = ["    "]
        else:
            payload["items"][0][field] = " "
        assert run(req, payload)["status"] == "FAILED"


@pytest.mark.parametrize(
    "direction,score,expected",
    [
        ("positive", 0.2, "SUCCEEDED"),
        ("negative", -0.2, "SUCCEEDED"),
        ("neutral", 0.0, "SUCCEEDED"),
        ("neutral", 0.1, "FAILED"),
        ("positive", -0.1, "FAILED"),
        ("negative", 0.1, "FAILED"),
    ],
)
def test_real_failure_direction_score_boundary_is_not_relaxed(
    direction, score, expected
):
    """真实调用的中性正分失败固化为回归，补提示词后仍保留同一拒绝标准。"""
    req = request()
    payload = batch(req, score=score)
    for item in payload["items"]:
        item["direction"] = direction
    assert run(req, payload)["status"] == expected
