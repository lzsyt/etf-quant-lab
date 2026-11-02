"""公开发布机构/出版方源；仅保存有限摘要，不绕过访问控制。"""

import hashlib
import re
from datetime import datetime, time, timedelta
from email.utils import parsedate_to_datetime
from urllib.parse import urljoin, urlparse
from zoneinfo import ZoneInfo

import httpx
from bs4 import BeautifulSoup
from defusedxml import ElementTree

from etf_ai.schemas import NewsItem

BEIJING = ZoneInfo("Asia/Shanghai")
PBC = "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html"
FEEDS = {
    "SINA_FINANCE": "https://rss.sina.com.cn/roll/finance/hot_roll.xml",
    "SINA_FUND": "https://rss.sina.com.cn/finance/fund.xml",
}
ALLOWED_HOSTS = {
    "www.chinaamc.com",
    "www.pbc.gov.cn",
    "rss.sina.com.cn",
    "finance.sina.com.cn",
    "news.sina.com.cn",
    "www.csrc.gov.cn",
    "www.stats.gov.cn",
    "www.mof.gov.cn",
}


def safe_url(url):
    """固定允许的HTTPS出版方来源，不跟随任意重定向或访问内网地址。"""
    parsed = urlparse(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in ALLOWED_HOSTS
        or parsed.username
        or parsed.password
        or parsed.port not in (None, 443)
    ):
        raise ValueError("新闻地址不在允许来源中")
    return url


def fetch(client, url):
    """读取有上限的公开页面，禁用外部代理继承和自动重定向。"""
    with client.stream(
        "GET", safe_url(url), headers={"User-Agent": "ETFQuantLab/1.0 Research"}
    ) as r:
        r.raise_for_status()
        data = bytearray()
        for chunk in r.iter_bytes():
            data.extend(chunk)
            if len(data) > 2000000:
                raise ValueError("新闻响应过大")
        return bytes(data)


def make_item(title, summary, url, source, published, fetched, metadata=None):
    """同一文章内容修订生成新快照，历史摘要与首次获取时间不覆盖。"""
    safe_url(url)
    if published.tzinfo is None:
        raise ValueError("新闻时间缺少时区")
    summary = " ".join(summary.split())[:1500]
    hash_input = title + summary
    if metadata and metadata.get("content"):
        hash_input += "\n" + metadata["content"]
        hash_input += "\n" + metadata.get("original_content_hash", "")
    content_hash = hashlib.sha256(hash_input.encode()).hexdigest()
    identity = hashlib.sha256((url + content_hash).encode()).hexdigest()
    return NewsItem(
        id=identity,
        title=title[:300],
        summary=summary,
        source_url=url,
        source_name=source,
        published_at=published,
        fetched_at=fetched,
        publication_verified=True,
        content_sha256=content_hash,
        metadata=metadata or {},
    )


def parse_rss(data, source, fetched, audit=None):
    """XML禁用实体解析；必须有日期、摘要及有效来源链接，缺项不猜测。"""
    root = ElementTree.fromstring(data)
    if root.tag != "rss":
        raise ValueError("NOT_RSS_FEED")
    audit = audit if audit is not None else {}
    nodes = root.findall(".//item")
    audit.update(
        {"candidate_count": len(nodes), "excluded": [], "content_type": "SUMMARY"}
    )
    channel_date = root.findtext("channel/lastBuildDate") or root.findtext(
        "channel/pubDate"
    )
    if channel_date:
        try:
            updated = parsedate_to_datetime(channel_date)
            if updated.tzinfo is not None:
                audit["source_updated_at"] = updated.isoformat()
                audit["source_stale"] = updated < fetched - timedelta(days=7)
        except (ValueError, TypeError):
            audit["source_timestamp_status"] = "INVALID_SOURCE_TIMESTAMP"
    items = []
    for index, node in enumerate(nodes[:30]):
        try:
            url = node.findtext("link", "").replace("http://", "https://", 1)
            summary = BeautifulSoup(
                node.findtext("description", ""), "html.parser"
            ).get_text(" ")
            item = make_item(
                node.findtext("title", ""),
                summary,
                url,
                source,
                parsedate_to_datetime(node.findtext("pubDate", "")),
                fetched,
            )
            if fetched - timedelta(days=7) <= item.published_at <= fetched:
                items.append(item)
            else:
                audit["excluded"].append(
                    {"candidate": index, "reason": "OUTSIDE_PUBLICATION_WINDOW"}
                )
        except (ValueError, TypeError):
            audit["excluded"].append(
                {"candidate": index, "reason": "INVALID_SOURCE_DATE_OR_CONTENT"}
            )
            continue
    audit["accepted_count"] = len(items)
    return items


def pbc_news(client, fetched, audit=None):
    """央行官方发布日期只有日期时以当日结束作为可用时间，不伪造精确时刻。"""
    soup = BeautifulSoup(fetch(client, PBC), "html.parser")
    candidates = []
    for a in soup.select("font.newslist_style a"):
        parent = a.find_parent("td")
        match = re.search(r"\d{4}-\d{2}-\d{2}", parent.get_text() if parent else "")
        if match:
            candidates.append((match.group(), urljoin(PBC, a.get("href", ""))))
    audit = audit if audit is not None else {}
    audit.update(
        {
            "candidate_count": len(set(candidates)),
            "excluded": [],
            "content_type": "SUMMARY",
            "publication_precision": "DATE_CONSERVATIVE_END_OF_DAY",
        }
    )
    items = []
    # 原先先截最新六篇，可能在相关性判断前丢掉同页七天内的政策资料。
    # 仅扩大同一个已授权官方列表页内的候选，不改变主题与时间标准。
    for day, url in sorted(set(candidates), reverse=True)[:15]:
        published = datetime.combine(
            datetime.strptime(day, "%Y-%m-%d").date(), time(23, 59, 59), BEIJING
        )
        if not fetched - timedelta(days=7) <= published <= fetched:
            audit["excluded"].append(
                {"source_url": url, "reason": "OUTSIDE_PUBLICATION_WINDOW"}
            )
            continue
        try:
            article = BeautifulSoup(fetch(client, url), "html.parser")
        except (httpx.HTTPError, ValueError) as error:
            audit["excluded"].append(
                {
                    "source_url": url,
                    "reason": "ARTICLE_FETCH_FAILED",
                    "error_code": type(error).__name__,
                }
            )
            continue
        title = article.find("meta", attrs={"name": "ArticleTitle"})
        date_meta = article.find("meta", attrs={"name": "PubDate"})
        description = article.find("meta", attrs={"name": "Description"})
        if title and date_meta and description and date_meta.get("content") == day:
            items.append(
                make_item(
                    title["content"],
                    description["content"],
                    url,
                    "PBOC_OFFICIAL_DATE_PRECISION",
                    published,
                    fetched,
                )
            )
        else:
            audit["excluded"].append(
                {"source_url": url, "reason": "MISSING_OR_INCONSISTENT_SOURCE_METADATA"}
            )
    audit["accepted_count"] = len(items)
    return items


def collect_news():
    """正式采集委托V2.1来源登记；旧解析器保留用于历史兼容和隔离回归。"""
    from etf_ai.sources import collect_official_news

    return collect_official_news()
