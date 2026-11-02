"""财经情报的本地分类、可解释排序和保守事件聚合；不计算交易参数。"""

import hashlib
import re
from datetime import timedelta
from difflib import SequenceMatcher
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from etf_ai.relevance import ADMIN_TERMS, INDEX_TERMS

SELECTION_VERSION = "news-selection-v2.1"
MAX_ARTICLES = 15
# UTF-8字节数作为保守Token上界，避免依赖不匹配供应商的分词器。
MAX_INPUT_TOKEN_BOUND = 24000
MAX_CANDIDATES = 150
BEIJING = ZoneInfo("Asia/Shanghai")
CATEGORIES = {
    "MONETARY_POLICY": (
        "降准",
        "降息",
        "加息",
        "存款准备金",
        "LPR",
        "货币政策",
        "逆回购",
        "公开市场操作",
        "社会融资",
        "金融统计",
    ),
    "FISCAL_POLICY": (
        "财政政策",
        "财政收支",
        "财政收入",
        "财政支出",
        "政府债券",
        "国债",
        "专项债",
        "增值税",
        "减税",
        "预算执行",
    ),
    "MACRO_ECONOMY": (
        "国内生产总值",
        "GDP",
        "CPI",
        "PPI",
        "居民消费价格",
        "工业生产者",
        "工业增加值",
        "工业生产",
        "社会消费品",
        "固定资产投资",
        "就业",
        "国民经济",
        "经济运行",
    ),
    "CAPITAL_MARKET": (
        "资本市场",
        "证券市场",
        "股票市场",
        "上市公司",
        "并购重组",
        "融资融券",
        "交易制度",
        "退市",
        "IPO",
        "首次公开发行",
        "投资者保护",
    ),
    "MARKET_RISK": (
        "债务违约",
        "金融风险",
        "流动性风险",
        "系统性风险",
        "风险警示",
        "重大风险",
        "金融稳定",
    ),
    "INDUSTRY_POLICY": (
        "产业政策",
        "行业政策",
        "半导体",
        "集成电路",
        "人工智能",
        "新能源",
        "科技创新",
        "数字经济",
        "生物医药",
        "先进制造",
    ),
}
OFFICIAL_HOSTS = {
    "www.pbc.gov.cn",
    "www.csrc.gov.cn",
    "www.stats.gov.cn",
    "www.mof.gov.cn",
}


def normalize(text):
    """统一空白与大小写用于匹配，保留数字、否定词和原始证据文本。"""
    return re.sub(r"\s+", "", text).lower()


def enrich_item(item, cutoff):
    """给新快照补充研究元数据；重要性只用于输入选择，绝不进入交易公式。"""
    metadata = dict(item.metadata)
    original_content = str(metadata.get("content", ""))
    content = original_content[:6000]
    if len(original_content) > 6000:
        metadata.update(content_type="EXCERPT", content_truncated=True)
    text = normalize(item.title + " " + item.summary + " " + content)
    direct = [
        symbol
        for symbol, terms in INDEX_TERMS.items()
        if any(normalize(term) in text for term in terms)
    ]
    matches = {
        category: [term for term in terms if normalize(term) in text]
        for category, terms in CATEGORIES.items()
    }
    matched = {category: terms for category, terms in matches.items() if terms}
    administrative = any(term in item.title for term in ADMIN_TERMS)
    category = "ETF_INDEX" if direct else next(iter(matched), "UNRELATED")
    relevant = bool(direct or matched) and not (administrative and not direct)
    symbols = direct or (list(INDEX_TERMS) if relevant else [])
    reason = (
        "DIRECT_INDEX_TOPIC"
        if direct
        else "LOCAL_TOPIC_REQUIRES_SEMANTIC_REVIEW"
        if relevant
        else "ADMINISTRATIVE_NEWS"
        if administrative
        else "NO_SUPPORTED_ETF_TOPIC"
    )
    reasons, score = [], 0
    if relevant:
        if urlsplit(str(item.source_url)).hostname in OFFICIAL_HOSTS:
            score += 25
            reasons.append({"reason": "OFFICIAL_PRIMARY_SOURCE", "points": 25})
        if direct:
            score += 25
            reasons.append({"reason": "DIRECT_ETF_INDEX", "points": 25})
        elif category != "INDUSTRY_POLICY":
            score += 15
            reasons.append({"reason": "BROAD_MARKET_TOPIC", "points": 15})
        announced = any(
            term in item.title
            for term in (
                "发布",
                "公布",
                "通知",
                "决定",
                "公告",
                "数据",
                "统计",
                "核算结果",
                "工业增加值",
                "财政收支情况",
            )
        ) or (
            urlsplit(str(item.source_url)).hostname == "www.stats.gov.cn"
            and category == "MACRO_ECONOMY"
        )
        if announced:
            score += 15
            reasons.append({"reason": "ANNOUNCED_POLICY_OR_DATA", "points": 15})
        elif any(term in item.title for term in ("会见", "会谈", "座谈", "论坛")):
            score -= 15
            reasons.append(
                {"reason": "MEETING_WITHOUT_ANNOUNCED_POLICY_OR_DATA", "points": -15}
            )
        age = cutoff - item.published_at
        points = (
            20
            if timedelta(0) <= age <= timedelta(days=1)
            else 10
            if timedelta(0) <= age <= timedelta(days=3)
            else 0
        )
        score += points
        reasons.append({"reason": "RECENCY", "points": points})
    event_title = re.sub(r"[\W_]+", "", item.title).lower()
    event_id = hashlib.sha256(
        (
            item.published_at.astimezone(BEIJING).date().isoformat() + ":" + event_title
        ).encode()
    ).hexdigest()[:24]
    metadata.update(
        content=content,
        content_type=metadata.get("content_type", "SUMMARY"),
        category=category,
        related_symbols=symbols,
        relevance_status="RELEVANT" if relevant else "IRRELEVANT",
        relevance_reason=reason,
        matched_terms=[term for terms in matched.values() for term in terms],
        importance_score=score,
        importance_level="high" if score >= 60 else "medium" if score >= 35 else "low",
        importance_reasons=reasons,
        event_id=event_id,
        selection_version=SELECTION_VERSION,
    )
    return item.model_copy(update={"metadata": metadata})


def same_event(left, right):
    """仅合并同日同类且标题高度相近、数值及政策方向一致的报道，不按主题强行聚类。"""
    if set(left.metadata["related_symbols"]) != set(right.metadata["related_symbols"]):
        return False
    if (
        left.published_at.astimezone(BEIJING).date()
        != right.published_at.astimezone(BEIJING).date()
        or left.metadata["category"] != right.metadata["category"]
    ):
        return False
    a, b = normalize(left.title), normalize(right.title)
    if re.findall(r"\d+(?:\.\d+)?", a) != re.findall(r"\d+(?:\.\d+)?", b):
        return False
    for word in ("不", "未", "降", "升", "增", "减", "暂停", "恢复"):
        if (word in a) != (word in b):
            return False
    return a == b or SequenceMatcher(None, a, b, autojunk=False).ratio() >= 0.88


def select_intelligence(request):
    """筛选真实截止内的候选并记录每条去向；没有证据时不制造中性分析。"""
    from etf_ai.news import safe_url

    accepted, rejected, candidates = [], [], []
    # 同URL多次修订只选截止前最新已获取版本，旧行仍留在数据库及审计中。
    latest = {}
    for item in sorted(request.news, key=lambda x: (x.fetched_at, x.id), reverse=True):
        url = str(item.source_url)
        if url in latest:
            rejected.append(
                {
                    "id": item.id,
                    "reason": "SUPERSEDED_ARTICLE_VERSION",
                    "selected_id": latest[url].id,
                }
            )
        else:
            latest[url] = item
    for item in latest.values():
        reason = None
        if item.published_at > request.cutoff or item.fetched_at > request.cutoff:
            raise ValueError("新闻在输入截止时尚不可得")
        if not item.publication_verified:
            reason = "UNVERIFIED_PUBLICATION_TIME"
        elif item.published_at < request.cutoff - timedelta(days=7):
            reason = "STALE_NEWS"
        else:
            try:
                safe_url(str(item.source_url))
            except ValueError:
                reason = "SOURCE_NOT_ALLOWED"
        enriched = enrich_item(item, request.cutoff)
        if reason is None and enriched.metadata["relevance_status"] != "RELEVANT":
            reason = enriched.metadata["relevance_reason"]
        if reason is None and not set(enriched.metadata["related_symbols"]) & set(
            request.symbols
        ):
            reason = "NO_REQUESTED_ETF_TOPIC"
        if reason:
            rejected.append({"id": item.id, "reason": reason})
        else:
            candidates.append(enriched)
    ranked = sorted(
        candidates,
        key=lambda x: (
            -int(urlsplit(str(x.source_url)).hostname in OFFICIAL_HOSTS),
            -x.metadata["importance_score"],
            -x.published_at.timestamp(),
            x.id,
        ),
    )
    groups = []
    for item in ranked:
        group = next((group for group in groups if same_event(group[0], item)), None)
        if group is None:
            groups.append([item])
        else:
            group.append(item)
    groups.sort(
        key=lambda g: (
            -g[0].metadata["importance_score"],
            -g[0].published_at.timestamp(),
            g[0].id,
        )
    )
    chosen, events = [], []
    for group in groups:
        primary = group[0]
        event = {
            "event_id": primary.metadata["event_id"],
            "primary_source_id": primary.id,
            "source_ids": [x.id for x in group],
            "title": primary.title,
            "published_at": primary.published_at.isoformat(),
            "importance_score": primary.metadata["importance_score"],
            "importance_level": primary.metadata["importance_level"],
            "aggregation_basis": "SAME_DAY_CATEGORY_NUMBERS_DIRECTION_SIMILAR_TITLE",
        }
        events.append(event)
        if len(chosen) >= MAX_ARTICLES:
            rejected.append({"id": primary.id, "reason": "ARTICLE_LIMIT"})
        else:
            chosen.append(primary)
            accepted.append(
                {
                    "id": primary.id,
                    "symbols": [
                        s
                        for s in primary.metadata["related_symbols"]
                        if s in request.symbols
                    ],
                    "basis": primary.metadata["relevance_reason"],
                    "event_id": event["event_id"],
                    "importance_score": event["importance_score"],
                    "importance_reasons": primary.metadata["importance_reasons"],
                }
            )
        supplemented = False
        for duplicate in group[1:]:
            # 同事件最多保留一条有不同事实摘要的补充；共享event_id，报道数不加分。
            different = (
                SequenceMatcher(
                    None,
                    normalize(primary.summary),
                    normalize(duplicate.summary),
                    autojunk=False,
                ).ratio()
                < 0.65
            )
            if (
                not supplemented
                and different
                and len(duplicate.summary) >= 40
                and len(chosen) < MAX_ARTICLES
            ):
                metadata = {**duplicate.metadata, "event_id": event["event_id"]}
                chosen.append(duplicate.model_copy(update={"metadata": metadata}))
                accepted.append(
                    {
                        "id": duplicate.id,
                        "symbols": [
                            s
                            for s in duplicate.metadata["related_symbols"]
                            if s in request.symbols
                        ],
                        "basis": "SUPPLEMENTARY_EVENT_SOURCE",
                        "event_id": event["event_id"],
                        "importance_score": event["importance_score"],
                        "importance_reasons": primary.metadata["importance_reasons"],
                    }
                )
                supplemented = True
            else:
                rejected.append(
                    {
                        "id": duplicate.id,
                        "reason": "DUPLICATE_EVENT",
                        "primary_source_id": primary.id,
                    }
                )
    audit = {
        "version": SELECTION_VERSION,
        "candidate_count": len(request.news),
        "relevant_count": len(candidates),
        "event_count": len(groups),
        "events": events,
        "accepted": accepted,
        "rejected": rejected,
        "article_limit": MAX_ARTICLES,
        "input_token_upper_bound_limit": MAX_INPUT_TOKEN_BOUND,
        "token_estimation_basis": "UTF8_BYTES_CONSERVATIVE_BOUND_NOT_TOKENIZER",
        "note": "重要性仅用于输入排序；相似事件保守合并，不能证明完整事件识别或预测能力。",
    }
    refresh_selection(audit, chosen, request.symbols)
    return request.model_copy(update={"news": chosen}), audit


def refresh_selection(audit, items, symbols):
    """预算截断后同步真实允许引用集合，不能引用没有送给模型的文章。"""
    ids = {item.id for item in items}
    audit["accepted"] = [item for item in audit["accepted"] if item["id"] in ids]
    audit["accepted_count"] = len(items)
    audit["excluded_count"] = len(audit["rejected"])
    audit["input_content_type"] = (
        "MIXED"
        if any(
            item.metadata.get("content_type", "SUMMARY") != "SUMMARY" for item in items
        )
        else "SUMMARY"
    )
    audit["by_symbol"] = {
        symbol: [item["id"] for item in audit["accepted"] if symbol in item["symbols"]]
        for symbol in symbols
    }
