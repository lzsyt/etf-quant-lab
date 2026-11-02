"""证据ID完全离线验证：原文切片、稳定快照、表格上下文与绕过模型校验的时间边界。"""

import copy
import hashlib
import re
from datetime import UTC, datetime, timedelta
from itertools import pairwise
from types import SimpleNamespace

import pytest
from etf_ai.evidence import EVIDENCE_VERSION, MAX_EVIDENCE_PER_SOURCE, build_evidence
from etf_ai.schemas import NewsItem


def request(content="货币政策已经公布。银行体系流动性保持合理充裕。", summary=None):
    """构造明确标记Mock的官方域名样本，不触发采集或模型。"""
    cutoff = datetime(2026, 10, 25, 8, tzinfo=UTC)
    item = NewsItem(
        id="test-source",
        title="货币政策公开信息",
        summary=summary or content,
        source_url="https://www.stats.gov.cn/sj/zxfb/test.html",
        source_name="NBS",
        published_at=cutoff - timedelta(days=1),
        fetched_at=cutoff - timedelta(hours=2),
        nature="MOCK",
        publication_verified=True,
        content_sha256="a" * 64,
        metadata={
            "content": content,
            "content_type": "EXCERPT",
            "publication_precision": "SOURCE_TIMESTAMP",
        },
    )
    return SimpleNamespace(
        cutoff=cutoff, horizon=1, symbols=["510300", "510500", "159915"], news=[item]
    )


def test_same_day_ids_are_stable_without_mutating_input():
    """秒级截止时间和对象复制不产生新快照，原新闻和原摘要保持不变。"""
    req = request()
    before = req.news[0].model_dump(mode="json")
    first = build_evidence(req)
    later = copy.deepcopy(req)
    later.cutoff += timedelta(seconds=40)
    assert build_evidence(later) == first
    assert req.news[0].model_dump(mode="json") == before
    assert first["evidence_version"] == EVIDENCE_VERSION
    assert re.fullmatch(r"[a-f0-9]{64}", first["snapshot_id"])
    assert all(
        re.fullmatch(r"EV_[a-f0-9]{64}", item["id"]) for item in first["evidence"]
    )


@pytest.mark.parametrize(
    "change",
    [
        "day",
        "symbol",
        "selection",
        "text",
        "source_hash",
        "original_hash",
        "precision",
        "published",
        "fetched",
        "nature",
        "type",
    ],
)
def test_evidence_identity_is_scoped_to_immutable_snapshot(change):
    """最终来源集合、字段、审计身份或决策日变化使所有旧证据ID失效，不能跨快照引用。"""
    req = request()
    original = build_evidence(req)
    changed = copy.deepcopy(req)
    item = changed.news[0]
    if change == "day":
        changed.cutoff += timedelta(days=1)
    elif change == "symbol":
        changed.symbols = ["510300"]
    elif change == "selection":
        changed.news.append(item.model_copy(update={"id": "additional-source"}))
    elif change == "text":
        item.metadata["content"] += "新增加的事实。"
    elif change == "source_hash":
        item.content_sha256 = "b" * 64
    elif change == "original_hash":
        item.metadata["original_content_hash"] = "c" * 64
    elif change == "precision":
        item.metadata["publication_precision"] = "DATE_CONSERVATIVE_END_OF_DAY"
    elif change == "published":
        item.published_at -= timedelta(seconds=1)
    elif change == "fetched":
        item.fetched_at -= timedelta(seconds=1)
    elif change == "nature":
        item.nature = "REAL"
    elif change == "type":
        item.metadata["content_type"] = "FULL_TEXT"
    result = build_evidence(changed)
    assert result["snapshot_id"] != original["snapshot_id"]
    assert {row["id"] for row in result["evidence"]}.isdisjoint(
        row["id"] for row in original["evidence"]
    )


def test_unicode_offsets_and_hash_preserve_exact_single_field_text():
    """NBSP、全角空格、换行、百分号、负号和小数逐字保留，不拼接字段或修正数字。"""
    content = "GDP\u00a0同比增长　4.5%，环比−0.2%。\n第二项数据为10.4%。"
    req = request(content)
    result = build_evidence(req)
    source = req.news[0]
    for item in result["evidence"]:
        field_text = (
            source.metadata["content"]
            if item["field"] == "content"
            else getattr(source, item["field"])
        )
        assert field_text[item["start"] : item["end"]] == item["text"]
        assert hashlib.sha256(item["text"].encode()).hexdigest() == item["text_sha256"]
        assert item["source_content_sha256"] == source.content_sha256
    joined = "".join(
        item["text"] for item in result["evidence"] if item["field"] == "content"
    )
    assert joined == content
    assert "−0.2%" in joined and "\u00a0" in joined


def test_duplicate_summary_is_not_resent_and_excerpt_never_claims_full_text():
    """前缀摘要已包含在实际正文时省略，部分正文证据始终EXCERPT，标题是SUMMARY。"""
    req = request("GDP公布数据。具体情况已经说明。", "GDP 公布\n数据。")
    req.news[0].metadata["content_type"] = "FULL_TEXT"
    result = build_evidence(req)
    assert not any(item["field"] == "summary" for item in result["evidence"])
    assert any(
        item["reason"] == "DUPLICATE_OF_CONTENT_PREFIX"
        for item in result["diagnostics"]
    )
    for item in result["evidence"]:
        assert item["content_type"] == (
            "EXCERPT" if item["field"] == "content" else "SUMMARY"
        )
        assert item["source_content_type"] == "FULL_TEXT"


def test_nonduplicate_summary_is_kept_and_summary_only_is_honest():
    """摘要独有事实保留；没有正文时不生成正文证据。"""
    req = request("货币政策已经公布。", "独立摘要提供额外事实。")
    assert any(item["field"] == "summary" for item in build_evidence(req)["evidence"])
    req.news[0].metadata = {"content": "", "content_type": "SUMMARY"}
    result = build_evidence(req)
    assert {item["field"] for item in result["evidence"]} == {"title", "summary"}
    assert all(item["content_type"] == "SUMMARY" for item in result["evidence"])


def test_incomplete_tail_is_never_standalone():
    """1600字截断后的半句不进入独立证据；仅有残片时明确必须依赖上下文。"""
    req = request("完整事实已经公布。后面内容尚未完整")
    result = build_evidence(req)
    assert all("尚未完整" not in item["text"] for item in result["evidence"])
    assert any(
        item["reason"] == "INCOMPLETE_TRAILING_FRAGMENT"
        for item in result["diagnostics"]
    )
    req = request("没有完整句末的正文片段")
    body = next(
        item for item in build_evidence(req)["evidence"] if item["field"] == "content"
    )
    assert body["standalone"] is False and body["context_required"] is True


def test_flattened_gdp_table_keeps_one_long_original_context():
    """绝对额、同比和季度列不得被改写成猜测的GDP事实，完整表格片段只是上下文。"""
    content = (
        "根据有关资料核算。表 1 GDP 初步核算数据 绝对额（亿元） 比上年同期增长（ % ） "
        "三季度 前三季度 三季度 前三季度 GDP 375367 1071071 4.5 4.6 "
        "第一产业 27472 58994 3.6 3.7 第二产业 133349 383822 3.4 3.8 注：原表扁平化。"
    )
    result = build_evidence(request(content))
    body = [item for item in result["evidence"] if item["field"] == "content"]
    assert len(body) == 1
    assert body[0]["text"] == content
    assert body[0]["start"] == 0 and body[0]["end"] == len(content)
    assert body[0]["context_required"] is True and body[0]["standalone"] is False
    assert body[0]["context_reason"] == "TABULAR_OR_NUMERIC_DENSE"
    assert not any("GDP同比增长4.5%" in item["text"] for item in result["evidence"])


def test_per_source_count_character_bound_and_nonoverlapping_offsets():
    """最多四条证据，不重叠复制字段，不超过既有正文及标题摘要字符总量。"""
    req = request("公开数据已经核实。" * 160, "摘要中的独有事实。")
    result = build_evidence(req)
    assert len(result["evidence"]) <= MAX_EVIDENCE_PER_SOURCE
    assert sum(len(item["text"]) for item in result["evidence"]) <= len(
        req.news[0].title
    ) + len(req.news[0].summary) + len(req.news[0].metadata["content"])
    for field in ("title", "summary", "content"):
        spans = sorted(
            (item["start"], item["end"])
            for item in result["evidence"]
            if item["field"] == field
        )
        assert all(first[1] <= second[0] for first, second in pairwise(spans))


@pytest.mark.parametrize(
    "mutation",
    [
        "future_publication",
        "future_fetch",
        "unverified",
        "unknown_source",
        "too_long",
        "bad_hash",
        "unknown_nature",
        "naive_time",
    ],
)
def test_model_copy_cannot_bypass_evidence_boundaries(mutation):
    """模型复制绕过校验时仍拒绝未来、未核验、未知域名及超长正文，不构造虚假可用证据。"""
    req = request()
    changes = {}
    if mutation == "future_publication":
        changes["published_at"] = req.cutoff + timedelta(seconds=1)
    elif mutation == "future_fetch":
        changes["fetched_at"] = req.cutoff + timedelta(seconds=1)
    elif mutation == "unverified":
        changes["publication_verified"] = False
    elif mutation == "unknown_source":
        changes["source_url"] = "https://unapproved.example/news"
    elif mutation == "too_long":
        changes["metadata"] = {"content": "字" * 1601, "content_type": "FULL_TEXT"}
    elif mutation == "bad_hash":
        changes["content_sha256"] = "not-a-content-hash"
    elif mutation == "unknown_nature":
        changes["nature"] = "UNVERIFIED"
    elif mutation == "naive_time":
        changes["published_at"] = req.news[0].published_at.replace(tzinfo=None)
    req.news[0] = req.news[0].model_copy(update=changes)
    with pytest.raises(ValueError):
        build_evidence(req)


def test_evidence_version_changes_ids(monkeypatch):
    """切片算法版本变更也属于新的不可变快照。"""
    from etf_ai import evidence

    req = request()
    original = build_evidence(req)
    monkeypatch.setattr(evidence, "EVIDENCE_VERSION", "news-evidence-next-test")
    assert build_evidence(req)["snapshot_id"] != original["snapshot_id"]
