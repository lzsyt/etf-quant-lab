"""仅采集008701管理人公开公告；报告保持原发布日期，不冒充当日市场新闻。"""

import hashlib
import io
import re
import time
from datetime import timedelta
from urllib.parse import urljoin, urlparse

import httpx
from bs4 import BeautifulSoup
from pypdf import PdfReader
from pypdf.errors import PyPdfError

from etf_ai.news import make_item, safe_url
from etf_ai.sources import (
    MAX_CONTENT,
    USER_AGENT,
    SourceError,
    _error_code,
    _record_latest,
    _request,
    _respect_crawl_delay,
    date_only_metadata,
    source_date,
)

FUND_NAME = "华夏黄金交易型开放式证券投资基金发起式联接基金"


def pdf_excerpt(data, deadline):
    """只解析有界文本PDF，优先摘录管理人运作章节，不OCR、不执行附件内容。"""
    if not data.startswith(b"%PDF-"):
        raise SourceError("INVALID_PDF_RESPONSE")
    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted or len(reader.pages) > 40:
            raise SourceError("PDF_ENCRYPTED_OR_PAGE_LIMIT")
        pages = []
        for page in reader.pages:
            if time.monotonic() >= deadline:
                raise SourceError("SOURCE_DEADLINE_EXCEEDED")
            stream = page.get_contents()
            if stream is not None and len(stream.get_data()) > 1000000:
                raise SourceError("PDF_CONTENT_LIMIT")
            pages.append(page.extract_text() or "")
    except PyPdfError as error:
        raise SourceError("PDF_PARSE_FAILED") from error
    body = " ".join("\n".join(pages).split())
    if len(body) < 80 or ("008701" not in body and FUND_NAME not in body):
        raise SourceError("PDF_FUND_CONTENT_UNVERIFIED")
    # 保留连续原文及来源摘要；章节不存在时保留文首有限摘录，不生成分析摘要。
    start = re.search(r"4\.4\s*报告期内基金投资策略和运作分析", body)
    excerpt = body[start.start() :] if start else body
    stop = re.search(r"4\.6\s*报告期内基金持有人", excerpt) if start else None
    if stop:
        excerpt = excerpt[: stop.start()]
    return body, excerpt[:MAX_CONTENT].strip()


def collect_chinaamc(client, source, fetched, deadline, audit, robots):
    """固定基金列表、同域公告及PDF双日期校验，每批最多两篇近七天公告。"""
    _respect_crawl_delay(robots, deadline)
    response = _request(client, source["url"], deadline)
    response.raise_for_status()
    soup = BeautifulSoup(response.content, "html.parser")
    candidates = []
    for row in soup.select("div.div"):
        link = row.find("a", href=True)
        date = re.search(r"\d{4}-\d{2}-\d{2}", row.get_text(" ", strip=True))
        if link is not None and date:
            title = link.get_text(" ", strip=True)
            if FUND_NAME in title or "008701" in title:
                candidates.append(
                    (urljoin(source["url"], link["href"]), date.group(), title)
                )
    audit["candidate_count"] = len(candidates)
    if not candidates:
        raise SourceError("STRUCTURE_CHANGED")
    items, attempts = [], 0
    for url, listed_date, title in candidates:
        try:
            source_published, source_precision = source_date(listed_date)
            _record_latest(audit, source_published, source_precision)
            published, precision = source_date(listed_date, observed_at=fetched)
            if published > fetched:
                raise SourceError("NOT_YET_AVAILABLE")
            if published < fetched - timedelta(days=7):
                raise SourceError("STALE_NEWS")
            if attempts >= 2:
                raise SourceError("SOURCE_ITEM_LIMIT")
            if urlparse(url).hostname != "www.chinaamc.com":
                raise SourceError("SOURCE_HOST_NOT_APPROVED")
            safe_url(url)
            if robots is not None and not robots.can_fetch(USER_AGENT, url):
                raise SourceError("ROBOTS_DISALLOWED")
            attempts += 1
            _respect_crawl_delay(robots, deadline)
            article = _request(client, url, deadline)
            article.raise_for_status()
            page = BeautifulSoup(article.content, "html.parser")
            match = re.search(
                r"时间[：:]\s*(\d{4}-\d{2}-\d{2})", page.get_text(" ", strip=True)
            )
            if match is None or match.group(1) != listed_date:
                raise SourceError("PUBLICATION_DATE_CONFLICT")
            links = [
                a
                for a in page.find_all("a", href=True)
                if a["href"].lower().endswith(".pdf")
            ]
            if len(links) != 1:
                raise SourceError("PDF_ATTACHMENT_NOT_UNIQUE")
            pdf_url = urljoin(url, links[0]["href"])
            if urlparse(pdf_url).hostname != "www.chinaamc.com":
                raise SourceError("SOURCE_HOST_NOT_APPROVED")
            safe_url(pdf_url)
            if robots is not None and not robots.can_fetch(USER_AGENT, pdf_url):
                raise SourceError("ROBOTS_DISALLOWED")
            _respect_crawl_delay(robots, deadline)
            pdf = _request(client, pdf_url, deadline)
            pdf.raise_for_status()
            body, excerpt = pdf_excerpt(pdf.content, deadline)
            metadata = {
                "content": excerpt,
                "content_type": "EXCERPT",
                "content_scope": "FUND_DISCLOSURE_PDF_EXCERPT_NOT_LIVE_MARKET_NEWS",
                "original_content_length": len(body),
                "original_content_hash": hashlib.sha256(body.encode()).hexdigest(),
                "original_pdf_sha256": hashlib.sha256(pdf.content).hexdigest(),
                "content_truncated": True,
                "publication_precision": precision,
                "publication_time_basis": "OFFICIAL_LIST_AND_ANNOUNCEMENT_DATE",
                "announcement_url": url,
                "fund_code": "008701",
                "attribution": "引自华夏基金008701官方公告PDF，报告期以原文为准",
                "policy_url": source["policy_url"],
                **date_only_metadata(listed_date, precision, fetched),
            }
            items.append(
                make_item(
                    title,
                    excerpt[:500],
                    pdf_url,
                    source["source"],
                    published,
                    fetched,
                    metadata,
                )
            )
        except (httpx.HTTPError, SourceError, ValueError) as error:
            reason = _error_code(error)
            audit["excluded"].append({"source_url": url, "reason": reason})
            if reason in ("HTTP_403", "HTTP_429", "SOURCE_DEADLINE_EXCEEDED"):
                break
    return items
