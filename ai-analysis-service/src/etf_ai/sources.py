"""V2.1 有限官方财经来源；每次检查访问规则，保留失败和停用依据。"""

import hashlib
import re
import time
from datetime import UTC, datetime, timedelta
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import httpx
from bs4 import BeautifulSoup

from etf_ai.news import BEIJING, make_item, safe_url

USER_AGENT = "ETFQuantLab/2.1 Research"
MAX_ARTICLES = 5
MAX_CONTENT = 6000
SOURCE_SECONDS = 40
BATCH_SECONDS = 135
CSRC_API = (
    "https://www.csrc.gov.cn/searchList/a1a078ee0bc54721ab6b148884c784a8"
    "?_isAgg=true&_isJson=true&_pageSize=18&_template=index"
    "&_rangeTimeGte=&_channelName=&page=1"
)
SOURCES = (
    {
        "source": "CHINAAMC_008701",
        "name": "华夏基金008701官方公告",
        "enabled": True,
        "url": "https://www.chinaamc.com/product/publishGgList.do?fundcode=008701",
        "kind": "CHINAAMC_008701",
        "policy_url": "https://www.chinaamc.com/product/publishGgList.do?fundcode=008701",
        "policy_basis": "基金管理人公开信息披露；仅保存署名有限摘录、日期与原件链接用于本基金研究",
    },
    {
        "source": "CSRC",
        "name": "中国证监会",
        "enabled": True,
        "url": CSRC_API,
        "kind": "CSRC",
        "policy_url": "https://www.csrc.gov.cn/csrc/c100227/c1362477/content.shtml",
        "policy_basis": "官方公开新闻，仅署名非商业研究引用，不更改、销售或租赁原文",
    },
    {
        "source": "NBS",
        "name": "国家统计局",
        "enabled": True,
        "url": "https://www.stats.gov.cn/sj/zxfb/",
        "kind": "NBS",
        "policy_url": "https://www.stats.gov.cn/wzgl/202302/t20230217_1912857.html",
        "policy_basis": "服务条款允许署名引用官方数据，排除第三方及个人署名作品",
    },
    {
        "source": "MOF",
        "name": "财政部",
        "enabled": True,
        "url": "https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/",
        "kind": "MOF",
        "policy_url": "https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/",
        "policy_basis": "官网页脚允许注明来源转载，仅同域官方内容及有限摘录",
    },
    {
        "source": "PBOC",
        "name": "中国人民银行",
        "enabled": False,
        "url": "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html",
        "error_code": "ROBOTS_DISALLOWED",
        "reason": "2026-10-25 robots.txt 对通用爬虫禁止全部路径；保留历史，不继续自动抓取",
        "policy_url": "https://www.pbc.gov.cn/robots.txt",
    },
    {
        "source": "SINA_FINANCE",
        "name": "新浪财经旧RSS",
        "enabled": False,
        "url": "https://rss.sina.com.cn/roll/finance/hot_roll.xml",
        "error_code": "STALE_SOURCE_FEED",
        "reason": "原RSS更新时间停留在2018年，停止作为正常来源",
    },
    {
        "source": "SINA_FUND",
        "name": "新浪基金旧RSS",
        "enabled": False,
        "url": "https://rss.sina.com.cn/finance/fund.xml",
        "error_code": "STALE_SOURCE_FEED",
        "reason": "原RSS更新时间停留在2018年，停止作为正常来源",
    },
    {
        "source": "SSE",
        "name": "上海证券交易所",
        "enabled": False,
        "url": "https://www.sse.com.cn/aboutus/mediacenter/hotandd/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "可非商业浏览下载，但声明限制文章深链接；本轮不接入原文链接展示",
        "policy_url": "https://www.sse.com.cn/home/legal/",
    },
    {
        "source": "SZSE",
        "name": "深圳证券交易所",
        "enabled": False,
        "url": "https://www.szse.cn/aboutus/trends/news/index.html",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "可非商业浏览下载，但声明限制文章深链接；本轮不接入原文链接展示",
        "policy_url": "https://www.szse.cn/application/laws/index.html",
    },
)


# 媒体候选保留评估状态，许可未满足时不发出新闻采集请求。
SOURCES += (
    {
        "source": "EASTMONEY",
        "name": "东方财富",
        "enabled": False,
        "url": "https://www.eastmoney.com/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "公开首页可浏览，robots Allow /；官方列出的Choice API有登录和申请试用，不能当成免授权接口。本次未取得新闻自动采集/外发许可，也不注册或购买，不逆向未文档接口。",
        "policy_url": "https://quantapi.eastmoney.com/",
    },
    {
        "source": "STCN",
        "name": "证券时报",
        "enabled": False,
        "url": "https://www.stcn.com/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "现行版权声明明确要求书面授权，并列举抓取、缓存、部分展示等行为；不做内容采集。主页/robots本机检查另有超时，不能以超时替代许可原因。",
        "policy_url": "https://www.stcn.com/public/copyright.html",
    },
    {
        "source": "CS",
        "name": "中国证券报",
        "enabled": False,
        "url": "https://www.cs.com.cn/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "版权声明要求原创内容使用书面许可，涵盖摘编、链接、使用和二次转载。网站可浏览不表示已获授权。",
        "policy_url": "https://www.cs.com.cn/aboutsite2020/bqsm/",
    },
    {
        "source": "CNSTOCK",
        "name": "上海证券报",
        "enabled": False,
        "url": "https://www.cnstock.com/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "协议5.1允许私人非商业保存在一台计算机，但限制未授权发布等；未明确许可持续采集及发送第三方模型，本次不将局部私人存储许可扩大解释为此链路授权。首页可见10月24日报道，不是陈旧源。",
        "policy_url": "https://www.cnstock.com/help/agreement.htm",
    },
    {
        "source": "CLS",
        "name": "财联社",
        "enabled": False,
        "url": "https://www.cls.cn/",
        "error_code": "SOURCE_TERMS_LIMITATION",
        "reason": "版权声明要求书面授权方可复制、改动、展示或使用局部或全部内容；robots只禁若干路径/图片并不授予版权许可。不逆向签名接口。",
        "policy_url": "https://www.cls.cn/our?nav=copyrightNotice",
    },
)


class SourceError(ValueError):
    """提供可展示的固定原因，避免将HTTP200空壳误报为成功。"""


def source_date(value, observed_at=None):
    """解析源站时间；仅日期时用日末或真实观测时刻给出保守可用上界。

    同日已经抓到的正文无需等到午夜，但观测时间并不是源站精确发布时间。
    未提供观测证据时保留原来的日末规则；未来日期仍拒绝，不以抓取倒填。
    """
    value = " ".join(str(value).strip().split())
    for pattern in (
        "%Y-%m-%d %H:%M:%S",
        "%Y/%m/%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y/%m/%d %H:%M",
    ):
        try:
            return datetime.strptime(value, pattern).replace(
                tzinfo=BEIJING
            ), "SOURCE_TIMESTAMP"
        except ValueError:
            continue
    for pattern in ("%Y-%m-%d", "%Y/%m/%d", "%Y年%m月%d日"):
        try:
            day = datetime.strptime(value, pattern)
        except ValueError:
            continue
        end_of_day = day.replace(hour=23, minute=59, second=59, tzinfo=BEIJING)
        if observed_at is not None:
            if observed_at.tzinfo is None:
                raise SourceError("UNVERIFIED_OBSERVATION_TIME")
            observed = observed_at.astimezone(BEIJING)
            if day.date() > observed.date():
                raise SourceError("NOT_YET_AVAILABLE")
            if day.date() == observed.date() and observed < end_of_day:
                return observed, "DATE_ONLY_OBSERVED_AVAILABLE"
        return end_of_day, "DATE_CONSERVATIVE_END_OF_DAY"
    raise SourceError("UNVERIFIED_PUBLICATION_TIME")


def date_only_metadata(raw_date, precision, observed_at):
    """单独记录日期与观测证据，明确可用上界不能解释成源站精确发布时间。"""
    if precision != "DATE_ONLY_OBSERVED_AVAILABLE":
        return {}
    return {
        "source_publication_date": raw_date,
        "observed_at": observed_at.isoformat(),
        "publication_time_note": (
            "源站仅公开日期；published_at为当日结束与实际观测完成时间中较早者，"
            "不是源站精确发布时间。首次入库快照保留首次观测，不追溯覆盖旧记录。"
        ),
    }


def text_content(html):
    """只保留可读正文，去除脚本、样式和隐藏说明，不保存图片或页面布局。"""
    soup = BeautifulSoup(html, "html.parser")
    for node in soup.select("script,style,noscript,iframe"):
        node.decompose()
    return " ".join(soup.get_text(" ", strip=True).split())


def _request(client, url, deadline):
    """低频有界读取，不跟随重定向，整个来源共享截止时间与响应大小上限。"""
    safe_url(url)
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise SourceError("SOURCE_DEADLINE_EXCEEDED")
    timeout = httpx.Timeout(min(5, remaining), connect=min(3, remaining))
    with client.stream(
        "GET", url, timeout=timeout, headers={"User-Agent": USER_AGENT}
    ) as response:
        content = bytearray()
        for chunk in response.iter_bytes():
            if time.monotonic() >= deadline:
                raise SourceError("SOURCE_DEADLINE_EXCEEDED")
            content.extend(chunk)
            if len(content) > 1000000:
                raise SourceError("SOURCE_RESPONSE_TOO_LARGE")
        # iter_bytes 已完成传输解压，重建响应时不能再次按原 gzip 头解压。
        headers = {
            key: value
            for key, value in response.headers.items()
            if key.lower() not in ("content-encoding", "content-length")
        }
        return httpx.Response(
            response.status_code,
            content=bytes(content),
            headers=headers,
            request=response.request,
        )


def _check_robots(client, source, deadline):
    """尊重实时robots；缺失文件不等于版权许可，来源条款另行登记核实。"""
    parsed = urlparse(source["url"])
    origin = f"https://{parsed.hostname}"
    response = _request(client, origin + "/robots.txt", deadline)
    if response.status_code == 404:
        return None, "NOT_PROVIDED_HTTP_404"
    if source["source"] == "CSRC" and response.status_code == 302:
        target = response.headers.get("location", "").rstrip("/")
        if target in ("http://www.csrc.gov.cn", "https://www.csrc.gov.cn"):
            return None, "NOT_PROVIDED_HOME_REDIRECT"
    if response.status_code != 200:
        raise SourceError("ROBOTS_UNAVAILABLE_HTTP_" + str(response.status_code))
    if "<html" in response.text.lower():
        raise SourceError("ROBOTS_INVALID_RESPONSE")
    robot = RobotFileParser()
    robot.parse(response.text.splitlines())
    if not robot.can_fetch(USER_AGENT, source["url"]):
        raise SourceError("ROBOTS_DISALLOWED")
    return robot, "ALLOWED_BY_ROBOTS"


def _respect_crawl_delay(robots, deadline):
    """源站明确要求抓取间隔时照办；超出本批时间预算则停止，不能为赶工忽略。"""
    if robots is None:
        return
    delay = robots.crawl_delay(USER_AGENT) or 0
    if delay >= deadline - time.monotonic():
        raise SourceError("SOURCE_DEADLINE_EXCEEDED")
    if delay:
        time.sleep(delay)


def _article_item(source, title, body, url, raw_date, fetched, time_basis):
    """正文和摘要分开标注；超限是摘录，完整仅指该文章可读正文，不包含附件。"""
    published, precision = source_date(raw_date, observed_at=fetched)
    if published > fetched:
        raise SourceError("NOT_YET_AVAILABLE")
    if published < fetched - timedelta(days=7):
        raise SourceError("STALE_NEWS")
    body = text_content(body)
    if not title or not body:
        raise SourceError("MISSING_TITLE_OR_CONTENT")
    content = body[:MAX_CONTENT]
    metadata = {
        "content": content,
        "content_type": "EXCERPT" if len(body) > MAX_CONTENT else "FULL_TEXT",
        "original_content_length": len(body),
        "original_content_hash": hashlib.sha256(body.encode()).hexdigest(),
        "content_truncated": len(body) > MAX_CONTENT,
        "publication_precision": precision,
        "publication_time_basis": time_basis,
        "attribution": "引自" + source["name"] + "官方网站",
        "policy_url": source["policy_url"],
        "content_scope": "ARTICLE_TEXT_ONLY_ATTACHMENTS_NOT_READ",
        **date_only_metadata(raw_date, precision, fetched),
    }
    return make_item(
        title, body[:500], url, source["source"], published, fetched, metadata=metadata
    )


def _collect_csrc(client, source, fetched, deadline, audit, robots):
    """使用官网列表脚本实际公开调用的JSON接口，单次最多十八篇候选，不追加详情请求。"""
    _respect_crawl_delay(robots, deadline)
    response = _request(client, source["url"], deadline)
    response.raise_for_status()
    try:
        data = response.json()["data"]
        rows = data["results"]
        if (
            not isinstance(rows, list)
            or not all(isinstance(row, dict) for row in rows)
            or "total" not in data
        ):
            raise ValueError("invalid list")
    except (ValueError, KeyError, TypeError) as error:
        raise SourceError("STRUCTURE_CHANGED") from error
    audit["candidate_count"] = len(rows)
    if not rows and int(data["total"]) > 0:
        raise SourceError("STRUCTURE_CHANGED")
    items = []
    for row in rows[:18]:
        try:
            raw_date = row.get("publishedTimeStr", "")
            source_published, source_precision = source_date(raw_date)
            _record_latest(audit, source_published, source_precision)
            published, _ = source_date(raw_date, observed_at=fetched)
            if published < fetched - timedelta(days=7):
                raise SourceError("STALE_NEWS")
            if published > fetched:
                raise SourceError("NOT_YET_AVAILABLE")
            url = urljoin(source["url"], row.get("url", ""))
            if urlparse(url).hostname != "www.csrc.gov.cn":
                raise SourceError("SOURCE_HOST_NOT_APPROVED")
            safe_url(url)
            if robots is not None and not robots.can_fetch(USER_AGENT, url):
                raise SourceError("ROBOTS_DISALLOWED")
            body = row.get("contentHtml")
            if not isinstance(body, str) or not body.strip():
                raise SourceError("MISSING_TITLE_OR_CONTENT")
            items.append(
                _article_item(
                    source,
                    row.get("title", ""),
                    body,
                    url,
                    row["publishedTimeStr"],
                    fetched,
                    "OFFICIAL_LIST_API_PUBLISHED_TIME",
                )
            )
        except (SourceError, ValueError, TypeError) as error:
            audit["excluded"].append(
                {"source_url": row.get("url"), "reason": str(error)}
            )
    return items


def _record_latest(audit, published, precision="SOURCE_TIMESTAMP"):
    """记录源列表的最近发布时间，不把采集时间当成新闻更新时间。"""
    value = published.isoformat()
    current = audit.get("source_updated_at")
    if current is None or published > datetime.fromisoformat(current):
        audit["source_updated_at"] = value
        audit["source_updated_precision"] = precision


def _collect_html(client, source, fetched, deadline, audit, robots):
    """只读取一个公开列表及最多五个同域详情；跨子域不隐式扩展授权来源。"""
    _respect_crawl_delay(robots, deadline)
    response = _request(client, source["url"], deadline)
    response.raise_for_status()
    soup = BeautifulSoup(response.content, "html.parser")
    candidates = []
    seen = set()
    for row in soup.select("li"):
        date = re.search(r"\d{4}-\d{2}-\d{2}", row.get_text(" ", strip=True))
        link = row.find("a", href=True)
        if date is None or link is None:
            continue
        url = urljoin(source["url"], link["href"])
        if url in seen:
            continue
        seen.add(url)
        candidates.append(
            (url, date.group(), link.get("title") or link.get_text(" ", strip=True))
        )
    audit["candidate_count"] = len(candidates)
    if not candidates:
        # 只有源站明确声明空列表时返回EMPTY；结构变动或JS空壳必须可见。
        if any(
            term in soup.get_text() for term in ("暂无数据", "暂无信息", "没有找到")
        ):
            return []
        raise SourceError("STRUCTURE_CHANGED")
    items, attempts = [], 0
    for url, listed_date, listed_title in candidates:
        try:
            listed, _ = source_date(listed_date)
            _record_latest(audit, listed, "DATE_CONSERVATIVE_END_OF_DAY")
            if listed < fetched - timedelta(days=7):
                raise SourceError("STALE_NEWS")
            if urlparse(url).hostname != urlparse(source["url"]).hostname:
                raise SourceError("SOURCE_HOST_NOT_APPROVED")
            if attempts >= MAX_ARTICLES:
                raise SourceError("SOURCE_ITEM_LIMIT")
            if robots is not None and not robots.can_fetch(USER_AGENT, url):
                raise SourceError("ROBOTS_DISALLOWED")
            attempts += 1
            _respect_crawl_delay(robots, deadline)
            article_response = _request(client, url, deadline)
            article_response.raise_for_status()
            article = BeautifulSoup(article_response.content, "html.parser")
            meta = article.find("meta", attrs={"name": "PubDate"})
            if meta is None or not meta.get("content"):
                raise SourceError("UNVERIFIED_PUBLICATION_TIME")
            raw_date = meta["content"]
            published, _ = source_date(raw_date, observed_at=fetched)
            if published.date() != listed.date():
                raise SourceError("PUBLICATION_DATE_CONFLICT")
            body = article.select_one(
                ".TRS_UEDITOR" if source["kind"] == "NBS" else ".TRS_Editor"
            )
            if body is None:
                raise SourceError("STRUCTURE_CHANGED")
            origin = article.find("meta", attrs={"name": "ContentSource"})
            origin_text = origin.get("content", "") if origin else ""
            allowed_origins = (
                ("国家统计局", "")
                if source["kind"] == "NBS"
                else ("财政部", "财政部新闻办公室", "")
            )
            if origin_text not in allowed_origins:
                raise SourceError("THIRD_PARTY_RIGHTS_UNVERIFIED")
            title_meta = article.find("meta", attrs={"name": "ArticleTitle"})
            title = title_meta.get("content") if title_meta else listed_title
            items.append(
                _article_item(
                    source,
                    title,
                    str(body),
                    url,
                    raw_date,
                    fetched,
                    "ARTICLE_PUBDATE_META",
                )
            )
        except (httpx.HTTPError, SourceError, ValueError) as error:
            reason = _error_code(error)
            audit["excluded"].append({"source_url": url, "reason": reason})
            if reason in ("HTTP_403", "HTTP_429", "SOURCE_DEADLINE_EXCEEDED"):
                break
    return items


def _error_code(error):
    """仅公开稳定错误码，避免网络异常对象携带非必要环境信息。"""
    if isinstance(error, httpx.HTTPStatusError):
        return "HTTP_" + str(error.response.status_code)
    if isinstance(error, httpx.TimeoutException):
        return "SOURCE_TIMEOUT"
    if isinstance(error, SourceError):
        return str(error)
    return type(error).__name__


def collect_official_news(transport=None, now=None, enrich=True):
    """按来源独立采集和降级；所有状态与筛选证据都返回，失败不阻断其他来源。"""
    fetched = now or datetime.now(UTC)
    batch_deadline = time.monotonic() + BATCH_SECONDS
    items, reports = [], []
    with httpx.Client(
        trust_env=False, follow_redirects=False, transport=transport
    ) as client:
        for source in SOURCES:
            audit = {
                "source": source["source"],
                "name": source["name"],
                "source_url": source["url"],
                "policy_url": source.get("policy_url"),
                "policy_basis": source.get("policy_basis", source.get("reason")),
                "status": "DISABLED" if not source["enabled"] else "RUNNING",
                "candidate_count": 0,
                "count": 0,
                "relevant_count": 0,
                "source_updated_at": None,
                "error_code": source.get("error_code"),
                "fetched_at": fetched.isoformat(),
                "excluded": [],
                "content_type": "ARTICLE_TEXT_WITH_BOUNDED_EXCERPTS",
            }
            reports.append(audit)
            if not source["enabled"]:
                audit["reason"] = source["reason"]
                continue
            try:
                deadline = min(batch_deadline, time.monotonic() + SOURCE_SECONDS)
                robots, rule = _check_robots(client, source, deadline)
                audit["robots_status"] = rule
                if source["kind"] == "CHINAAMC_008701":
                    from etf_ai.fund_disclosures import collect_chinaamc

                    collector = collect_chinaamc
                else:
                    collector = (
                        _collect_csrc if source["kind"] == "CSRC" else _collect_html
                    )
                found = collector(client, source, fetched, deadline, audit, robots)
                audit["count"] = len(found)
                failures = [
                    row["reason"]
                    for row in audit["excluded"]
                    if row["reason"]
                    not in (
                        "STALE_NEWS",
                        "SOURCE_ITEM_LIMIT",
                        "SOURCE_HOST_NOT_APPROVED",
                        "NOT_YET_AVAILABLE",
                        "THIRD_PARTY_RIGHTS_UNVERIFIED",
                    )
                ]
                if found:
                    audit["status"] = "PARTIAL" if failures else "OK"
                    audit["error_code"] = failures[0] if failures else None
                    audit["last_success_at"] = (now or datetime.now(UTC)).isoformat()
                elif failures:
                    audit["status"] = "FAILED"
                    audit["error_code"] = failures[0]
                elif audit["source_updated_at"] and datetime.fromisoformat(
                    audit["source_updated_at"]
                ) < fetched - timedelta(days=7):
                    audit["status"], audit["error_code"] = "STALE", "STALE_SOURCE_FEED"
                else:
                    audit["status"] = "EMPTY"
                items.extend(found)
            except (httpx.HTTPError, ValueError, TypeError, KeyError) as error:
                audit["status"], audit["error_code"] = "FAILED", _error_code(error)
    completed = now or datetime.now(UTC)
    nature = "MOCK" if transport is not None or now is not None else "REAL"
    enriched = []
    for item in items:
        updates = {"fetched_at": completed, "nature": nature}
        if item.metadata.get("publication_precision") == "DATE_ONLY_OBSERVED_AVAILABLE":
            # 解析阶段的fetched是批次开始；只有完整采集结束后才有真实可用证据。
            # 采用批次完成时刻保守计时，避免把尚未下载的正文提前纳入分析。
            published, precision = source_date(
                item.metadata["source_publication_date"], observed_at=completed
            )
            updates["published_at"] = published
            updates["metadata"] = {
                **item.metadata,
                "publication_precision": precision,
                "observed_at": completed.isoformat(),
            }
        item = item.model_copy(update=updates)
        if enrich:
            from etf_ai.intelligence import enrich_item

            item = enrich_item(item, completed)
        enriched.append(item)
    for audit in reports:
        audit["nature"] = nature
        source_items = [
            item for item in enriched if item.source_name == audit["source"]
        ]
        audit["relevant_count"] = sum(
            bool(item.metadata.get("related_symbols")) for item in source_items
        )
        audit["category_counts"] = {}
        for item in source_items:
            category = item.metadata.get("category", "UNCLASSIFIED")
            audit["category_counts"][category] = (
                audit["category_counts"].get(category, 0) + 1
            )
        audit["fetched_at"] = completed.isoformat()
        audit["excluded_count"] = len(audit["excluded"])
        audit["source_stale"] = bool(
            audit["source_updated_at"]
            and datetime.fromisoformat(audit["source_updated_at"])
            < completed - timedelta(days=7)
        )
    return {
        "items": [item.model_dump(mode="json") for item in enriched],
        "sources": reports,
        "completed_at": completed.isoformat(),
    }
