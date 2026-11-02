"""官方来源采集的协议与故障测试；所有HTTP使用隔离传输，不访问公网。"""

from datetime import UTC, datetime

import httpx
import pytest
from etf_ai import sources
from etf_ai.news import make_item

NOW = datetime(2026, 10, 25, 4, tzinfo=UTC)


def run_source(monkeypatch, name, handler):
    """在固定时间和单一来源下执行真实解析路径，禁用待集成的语义准备层。"""
    monkeypatch.setattr(
        sources,
        "SOURCES",
        tuple(source for source in sources.SOURCES if source["source"] == name),
    )
    return sources.collect_official_news(
        httpx.MockTransport(handler), now=NOW, enrich=False
    )


def article_html(
    date="2026/10/24 09:30",
    body="国内生产总值公布，工业生产同比增长。",
    selector="TRS_UEDITOR",
):
    """构造含源站日期与正文区域的最小HTML，不依赖生产新闻。"""
    return f'<meta name="PubDate" content="{date}"><meta name="ArticleTitle" content="经济运行数据"><div class="{selector}">{body}</div>'


def test_nbs_collects_precise_date_content_and_deduplicates_mobile_links(monkeypatch):
    """桌面和手机同URL只请求一次，时间来自详情metadata而非URL路径。"""
    calls = []

    def handler(request):
        calls.append(str(request.url))
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path == "/sj/zxfb/":
            return httpx.Response(
                200,
                text='<ul><li><a href="./202610/t20261023_test.html">新闻</a><a href="./202610/t20261023_test.html">手机</a><span>2026-10-24</span></li></ul>',
            )
        return httpx.Response(200, text=article_html())

    result = run_source(monkeypatch, "NBS", handler)
    assert len(result["items"]) == 1 and len(calls) == 3
    item = result["items"][0]
    assert item["published_at"] == "2026-10-24T09:30:00+08:00"
    assert item["metadata"]["content_type"] == "FULL_TEXT"
    assert item["metadata"]["publication_precision"] == "SOURCE_TIMESTAMP"
    assert result["sources"][0]["status"] == "OK"


def test_mof_does_not_follow_unapproved_subdomains_and_limits_content(monkeypatch):
    """财政部列表外链不扩展抓取范围，正文截断必须标为摘录。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path.endswith("caizhengxinwen/"):
            return httpx.Response(
                200,
                text='<li><a href="https://gks.mof.gov.cn/news.html">外站</a>2026-10-24</li><li><a href="./article.htm">财政政策</a>2026-10-24</li>',
            )
        return httpx.Response(
            200,
            text=article_html(
                "2026-10-24 15:01:00", "国债财政政策。" * 1200, "TRS_Editor"
            ),
        )

    result = run_source(monkeypatch, "MOF", handler)
    item = result["items"][0]
    assert len(item["summary"]) <= 500
    assert len(item["metadata"]["content"]) == 6000
    assert item["metadata"]["content_type"] == "EXCERPT"
    assert result["sources"][0]["excluded"][0]["reason"] == "SOURCE_HOST_NOT_APPROVED"


def test_csrc_official_api_retains_recent_rows_without_article_calls(monkeypatch):
    """同一公开列表已有正文时只用两次HTTP，保留所有近期候选供事件排序。"""
    calls = []

    def handler(request):
        calls.append(str(request.url))
        if request.url.path == "/robots.txt":
            return httpx.Response(302, headers={"location": "http://www.csrc.gov.cn"})
        return httpx.Response(
            200,
            json={
                "data": {
                    "total": 7,
                    "results": [
                        {
                            "title": "资本市场政策" + str(i),
                            "url": f"//www.csrc.gov.cn/csrc/c100028/c{i}/content.shtml",
                            "publishedTimeStr": "2026-10-24 13:00:00",
                            "contentHtml": "<p>证券市场规则征求意见。</p>",
                        }
                        for i in range(7)
                    ],
                }
            },
        )

    result = run_source(monkeypatch, "CSRC", handler)
    assert len(result["items"]) == 7 and len(calls) == 2
    assert all(
        item["metadata"]["publication_time_basis"] == "OFFICIAL_LIST_API_PUBLISHED_TIME"
        for item in result["items"]
    )


@pytest.mark.parametrize(
    "mode,expected",
    [
        ("timeout", "SOURCE_TIMEOUT"),
        ("structure", "STRUCTURE_CHANGED"),
        ("http", "HTTP_503"),
        ("empty", None),
    ],
)
def test_source_failures_are_visible_and_empty_is_separate(monkeypatch, mode, expected):
    """超时、失效与页面空壳不能冒充无新闻；明确空列表单独展示。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if mode == "timeout":
            raise httpx.ReadTimeout("isolated timeout")
        if mode == "http":
            return httpx.Response(503)
        return httpx.Response(
            200, text="暂无数据" if mode == "empty" else "<html>页面已改版</html>"
        )

    result = run_source(monkeypatch, "NBS", handler)
    report = result["sources"][0]
    assert report["status"] == ("EMPTY" if mode == "empty" else "FAILED")
    assert report["error_code"] == expected
    assert result["items"] == []


def test_robots_disallow_stops_before_list_request(monkeypatch):
    """实时来源规则禁止抓取时不请求列表或文章，不伪装浏览器。"""
    calls = []

    def handler(request):
        calls.append(str(request.url))
        return httpx.Response(200, text="User-agent: *\nDisallow: /\n")

    result = run_source(monkeypatch, "NBS", handler)
    assert len(calls) == 1 and result["sources"][0]["error_code"] == "ROBOTS_DISALLOWED"


def test_deactivated_pbc_and_legacy_feeds_never_fetch(monkeypatch):
    """停用保留原因与历史标识，不执行收费或网络调用。"""
    monkeypatch.setattr(
        sources,
        "SOURCES",
        tuple(source for source in sources.SOURCES if not source["enabled"]),
    )
    result = sources.collect_official_news(
        httpx.MockTransport(lambda r: pytest.fail("disabled source fetched")),
        now=NOW,
        enrich=False,
    )
    assert all(source["status"] == "DISABLED" for source in result["sources"])
    assert {source["error_code"] for source in result["sources"]} >= {
        "ROBOTS_DISALLOWED",
        "STALE_SOURCE_FEED",
    }


def test_missing_or_conflicting_dates_never_enter_forward_inputs(monkeypatch):
    """列表与详情日期不一致时拒绝，不靠URL日期或抓取时间填充。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path == "/sj/zxfb/":
            return httpx.Response(
                200,
                text='<li><a href="./a.html">数据</a>2026-10-24</li><li><a href="./b.html">数据</a>2026-10-24</li>',
            )
        if request.url.path.endswith("a.html"):
            return httpx.Response(200, text=article_html("2026/10/23 09:30"))
        return httpx.Response(200, text='<div class="TRS_UEDITOR">缺少日期</div>')

    result = run_source(monkeypatch, "NBS", handler)
    assert not result["items"]
    assert {row["reason"] for row in result["sources"][0]["excluded"]} == {
        "PUBLICATION_DATE_CONFLICT",
        "UNVERIFIED_PUBLICATION_TIME",
    }


def test_date_only_and_unknown_date_are_conservative():
    """日期精度保持日末，缺失时间拒绝而非猜测。"""
    parsed, precision = sources.source_date("2026-10-24")
    assert (
        parsed.hour == 23
        and parsed.minute == 59
        and parsed.utcoffset().total_seconds() == 28800
    )
    assert precision == "DATE_CONSERVATIVE_END_OF_DAY"
    with pytest.raises(sources.SourceError, match="UNVERIFIED_PUBLICATION_TIME"):
        sources.source_date("昨天")


def test_same_day_date_only_uses_observed_upper_bound_without_faking_precision():
    """仅日期的当日正文已实际可得时立即可用，精度仍明确为日期而非源站时分。"""
    parsed, precision = sources.source_date("2026-10-25", observed_at=NOW)
    assert parsed == NOW
    assert precision == "DATE_ONLY_OBSERVED_AVAILABLE"
    assert sources.source_date("2026-10-24", observed_at=NOW) == sources.source_date(
        "2026-10-24"
    )
    assert sources.source_date(
        "2026-10-25 09:30:00", observed_at=NOW
    ) == sources.source_date("2026-10-25 09:30:00")


def test_observation_cannot_make_future_dated_material_available():
    """未来日期或缺少时区的观测不能成为当下已可用的证据。"""
    with pytest.raises(sources.SourceError, match="NOT_YET_AVAILABLE"):
        sources.source_date("2026-10-26", observed_at=NOW)
    with pytest.raises(sources.SourceError, match="UNVERIFIED_OBSERVATION_TIME"):
        sources.source_date("2026-10-25", observed_at=NOW.replace(tzinfo=None))


@pytest.mark.parametrize(
    "date,available", [("2026-10-25", True), ("2026-10-26", False)]
)
def test_date_only_html_availability_follows_actual_observation(
    monkeypatch, date, available
):
    """财政部同日列表与正文一致时可用，未来日期仍不进入分析输入。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path.endswith("caizhengxinwen/"):
            return httpx.Response(
                200, text=f'<li><a href="./article.htm">财政政策</a>{date}</li>'
            )
        return httpx.Response(200, text=article_html(date, selector="TRS_Editor"))

    result = run_source(monkeypatch, "MOF", handler)
    if not available:
        assert not result["items"]
        assert result["sources"][0]["excluded"][0]["reason"] == "NOT_YET_AVAILABLE"
        return
    item = result["items"][0]
    assert datetime.fromisoformat(item["published_at"]) == NOW
    assert item["metadata"]["publication_precision"] == "DATE_ONLY_OBSERVED_AVAILABLE"
    assert item["metadata"]["source_publication_date"] == date
    assert datetime.fromisoformat(item["metadata"]["observed_at"]) == (
        datetime.fromisoformat(item["fetched_at"])
    )
    assert "不是源站精确发布时间" in item["metadata"]["publication_time_note"]


def test_date_only_csrc_rows_use_explicit_date_precision(monkeypatch):
    """证监会列表若仅给日期也必须保留该精度，不能默认成精确发布时间。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        return httpx.Response(
            200,
            json={
                "data": {
                    "total": 1,
                    "results": [
                        {
                            "title": "资本市场政策",
                            "url": "https://www.csrc.gov.cn/csrc/news.shtml",
                            "publishedTimeStr": "2026-10-25",
                            "contentHtml": "<p>证券市场规则征求意见。</p>",
                        }
                    ],
                }
            },
        )

    result = run_source(monkeypatch, "CSRC", handler)
    assert len(result["items"]) == 1
    assert result["sources"][0]["source_updated_precision"] == (
        "DATE_CONSERVATIVE_END_OF_DAY"
    )
    assert result["items"][0]["metadata"]["publication_precision"] == (
        "DATE_ONLY_OBSERVED_AVAILABLE"
    )


def test_date_only_availability_uses_completion_not_batch_start(monkeypatch):
    """完整采集完成前不能确认正文已可得，输出采用完成时刻的保守上界。"""
    completed = NOW.replace(minute=2)

    class Clock(datetime):
        """隔离时钟依次模拟采集开始、源成功和完整批次结束。"""

        values = iter([NOW, NOW.replace(minute=1), completed])

        @classmethod
        def now(cls, tz=None):
            """返回预设的有时区时间，避免测试访问真实时钟。"""
            return next(cls.values)

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path == "/sj/zxfb/":
            return httpx.Response(
                200, text='<li><a href="./article.htm">经济数据</a>2026-10-25</li>'
            )
        return httpx.Response(200, text=article_html("2026-10-25"))

    monkeypatch.setattr(sources, "datetime", Clock)
    monkeypatch.setattr(
        sources,
        "SOURCES",
        tuple(source for source in sources.SOURCES if source["source"] == "NBS"),
    )
    result = sources.collect_official_news(httpx.MockTransport(handler), enrich=False)
    item = result["items"][0]
    assert datetime.fromisoformat(item["published_at"]) == completed
    assert datetime.fromisoformat(item["metadata"]["observed_at"]) == completed
    assert datetime.fromisoformat(item["fetched_at"]) == completed


def test_same_article_is_stable_but_body_revision_changes_identity():
    """重复采集稳定ID；正文改动即使摘要相同也保留新的快照证据。"""
    args = (
        "标题",
        "固定摘要",
        "https://www.stats.gov.cn/sj/zxfb/a.html",
        "NBS",
        NOW,
        NOW,
    )
    first = make_item(*args, metadata={"content": "版本一"})
    repeat = make_item(*args, metadata={"content": "版本一"})
    changed = make_item(*args, metadata={"content": "版本二"})
    assert first.id == repeat.id and changed.id != first.id


def test_detail_failure_keeps_other_articles_and_reports_partial(monkeypatch):
    """单篇超时不得丢弃其他有效新闻，也不能把本来源冒称为全部正常。"""

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path == "/sj/zxfb/":
            return httpx.Response(
                200,
                text='<li><a href="./a.html">数据</a>2026-10-24</li><li><a href="./b.html">数据</a>2026-10-24</li>',
            )
        if request.url.path.endswith("a.html"):
            raise httpx.ReadTimeout("isolated timeout")
        return httpx.Response(200, text=article_html())

    result = run_source(monkeypatch, "NBS", handler)
    assert len(result["items"]) == 1
    assert result["sources"][0]["status"] == "PARTIAL"
    assert result["sources"][0]["error_code"] == "SOURCE_TIMEOUT"


def test_revision_beyond_retained_excerpt_is_detected():
    """未保存的正文尾部发生修订也保留新哈希，不无限存全文仍能发现修订。"""
    source = next(source for source in sources.SOURCES if source["source"] == "NBS")
    args = (
        source,
        "GDP数据",
        "甲" * 6100,
        "https://www.stats.gov.cn/sj/zxfb/a.html",
        "2026/10/24 09:30",
        NOW,
        "ARTICLE_PUBDATE_META",
    )
    first = sources._article_item(*args)
    changed = sources._article_item(*args[:2], "甲" * 6100 + "修订", *args[3:])
    assert first.metadata["content"] == changed.metadata["content"]
    assert first.id != changed.id


def test_gzip_response_is_not_decoded_twice(monkeypatch):
    """真实财政部使用gzip，流式解压后重建响应必须清除原压缩头。"""
    import gzip

    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(
                404,
                content=gzip.compress(b"not found"),
                headers={"content-encoding": "gzip"},
            )
        if request.url.path.endswith("caizhengxinwen/"):
            body = '<li><a href="./article.htm">财政政策</a>2026-10-24</li>'
        else:
            body = article_html(
                "2026-10-24 15:01:00", "财政支出数据公布。", "TRS_Editor"
            )
        return httpx.Response(
            200,
            content=gzip.compress(body.encode()),
            headers={"content-encoding": "gzip"},
        )

    result = run_source(monkeypatch, "MOF", handler)
    assert len(result["items"]) == 1 and result["sources"][0]["status"] == "OK"
    assert result["items"][0]["nature"] == "MOCK"
    assert result["sources"][0]["nature"] == "MOCK"
