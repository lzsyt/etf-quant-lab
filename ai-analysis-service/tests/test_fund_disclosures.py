"""固定基金公告采集的时间、来源及原文边界；HTTP为隔离夹具。"""

import time
from datetime import UTC, datetime

import httpx
import pytest
from etf_ai import fund_disclosures, sources

NOW = datetime(2026, 10, 29, 4, tzinfo=UTC)
TITLE = fund_disclosures.FUND_NAME + "2026年第3季度报告"


def collect(
    monkeypatch,
    date="2026-10-27",
    attachment="/file/report.pdf",
    robots=None,
    listed_date="2026-10-27",
    now=NOW,
):
    """仅运行华夏来源，返回请求路径以核对未越界抓取。"""
    calls = []
    monkeypatch.setattr(
        sources,
        "SOURCES",
        tuple(s for s in sources.SOURCES if s["source"] == "CHINAAMC_008701"),
    )
    monkeypatch.setattr(
        fund_disclosures,
        "pdf_excerpt",
        lambda data, deadline: ("原文008701黄金" * 20, "基金008701跟踪国内黄金现货。"),
    )

    def handler(request):
        calls.append(str(request.url))
        if request.url.path == "/robots.txt":
            return (
                httpx.Response(404)
                if robots is None
                else httpx.Response(200, text=robots)
            )
        if request.url.path.endswith("publishGgList.do"):
            return httpx.Response(
                200,
                text=f'<div class="div"><div class="item"><a href="/c/report.shtml">{TITLE}</a></div><div class="item">{listed_date}</div></div>',
            )
        if request.url.path.endswith("report.shtml"):
            return httpx.Response(
                200, text=f'<div>时间：{date}</div><a href="{attachment}">报告</a>'
            )
        return httpx.Response(200, content=b"%PDF-fixture")

    return sources.collect_official_news(
        httpx.MockTransport(handler), now=now, enrich=False
    ), calls


def test_disclosure_preserves_actual_publication_and_pdf_scope(monkeypatch):
    """报告保留发布日期及PDF原件链接，不能冒充采集日的新市场新闻。"""
    result, calls = collect(monkeypatch)
    assert len(result["items"]) == 1 and len(calls) == 4
    item = result["items"][0]
    assert item["published_at"] == "2026-10-27T23:59:59+08:00"
    assert item["source_url"] == "https://www.chinaamc.com/file/report.pdf"
    assert item["metadata"]["fund_code"] == "008701"
    assert item["metadata"]["content_type"] == "EXCERPT"
    assert item["metadata"]["content_scope"].endswith("NOT_LIVE_MARKET_NEWS")
    assert result["sources"][0]["status"] == "OK"
    assert item["nature"] == "MOCK"


def test_same_day_disclosure_is_available_with_explicit_observation(monkeypatch):
    """实际抓取成功的当日公告无需等到午夜，日期与观测时间分别保留。"""
    result, calls = collect(monkeypatch, date="2026-10-29", listed_date="2026-10-29")
    assert len(result["items"]) == 1 and len(calls) == 4
    item = result["items"][0]
    assert datetime.fromisoformat(item["published_at"]) == NOW
    assert item["metadata"]["publication_precision"] == "DATE_ONLY_OBSERVED_AVAILABLE"
    assert item["metadata"]["source_publication_date"] == "2026-10-29"
    assert datetime.fromisoformat(item["metadata"]["observed_at"]) == (
        datetime.fromisoformat(item["fetched_at"])
    )
    assert "不是源站精确发布时间" in item["metadata"]["publication_time_note"]


def test_future_dated_disclosure_is_not_fetched_as_available(monkeypatch):
    """未来日期的列表记录不能因当前能看到列表而提前作为可用正文。"""
    result, calls = collect(monkeypatch, date="2026-10-30", listed_date="2026-10-30")
    assert not result["items"] and len(calls) == 2
    assert result["sources"][0]["excluded"][0]["reason"] == "NOT_YET_AVAILABLE"


@pytest.mark.parametrize("date", ["2026-10-27", "2026-10-29"])
def test_repeated_observation_keeps_content_identity_and_original_snapshot(
    monkeypatch, date
):
    """观测时间改变不改内容标识，既有日末规则和已返回的历史快照保持原样。"""
    original, _ = collect(monkeypatch, date=date, listed_date=date)
    repeated, _ = collect(
        monkeypatch, date=date, listed_date=date, now=NOW.replace(hour=6)
    )
    first = original["items"][0]
    later = repeated["items"][0]
    assert first["id"] == later["id"]
    assert first["content_sha256"] == later["content_sha256"]
    if date == "2026-10-27":
        assert (
            first["published_at"]
            == later["published_at"]
            == ("2026-10-27T23:59:59+08:00")
        )
        assert first["metadata"]["publication_precision"] == (
            "DATE_CONSERVATIVE_END_OF_DAY"
        )
    else:
        assert datetime.fromisoformat(first["published_at"]) == NOW
        assert datetime.fromisoformat(first["metadata"]["observed_at"]) == NOW
    assert datetime.fromisoformat(first["fetched_at"]) == NOW


def test_disclosure_rejects_conflicting_date_without_fetching_pdf(monkeypatch):
    """列表和详情日期冲突时，不能用列表日期强行标记正文可得。"""
    result, calls = collect(monkeypatch, date="2026-10-28")
    assert not result["items"] and len(calls) == 3
    assert result["sources"][0]["error_code"] == "PUBLICATION_DATE_CONFLICT"


def test_disclosure_rejects_cross_host_pdf(monkeypatch):
    """详情中的第三方PDF不能扩展既定来源域名。"""
    result, calls = collect(monkeypatch, attachment="https://example.org/other.pdf")
    assert not result["items"] and len(calls) == 3
    assert (
        result["sources"][0]["error_code"] == "SOURCE_HOST_NOT_APPROVED"
        or result["sources"][0]["status"] == "EMPTY"
    )


def test_disclosure_respects_attachment_robots(monkeypatch):
    """robots允许列表但禁止附件时，不发出附件请求。"""
    result, calls = collect(monkeypatch, robots="User-agent: *\nDisallow: /file/")
    assert not result["items"] and len(calls) == 3
    assert result["sources"][0]["error_code"] == "ROBOTS_DISALLOWED"


def test_pdf_rejects_html_error_page():
    """HTTP200错误HTML不能被当作成功公告正文。"""
    with pytest.raises(sources.SourceError, match="INVALID_PDF_RESPONSE"):
        fund_disclosures.pdf_excerpt(b"<html>error</html>", time.monotonic() + 2)
