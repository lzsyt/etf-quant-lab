"""可审计的新闻相关性规则；主题相关仅是研究假设，不等于短期价格预测有效。"""

from etf_ai.schemas import AnalysisRequest

RELEVANCE_VERSION = "etf-news-relevance-v1"
INDEX_TERMS = {
    "159915": ("159915", "创业板"),
    "510300": ("510300", "沪深300", "沪深 300"),
    "510500": ("510500", "中证500", "中证 500"),
}
MACRO_TERMS = (
    "降准", "降息", "存款准备金率", "贷款市场报价利率", "LPR",
    "公开市场操作", "逆回购", "货币政策", "社会融资规模", "金融统计数据",
    "证券市场", "资本市场", "股票市场", "居民消费价格", "工业生产者出厂价格",
)
ADMIN_TERMS = ("干部", "任免", "巡视", "党课", "党纪", "主题教育", "机关招聘", "采购公告")


def select_relevant(request: AnalysisRequest):
    """按正文主题筛选并记录每次命中，不因央行或其他来源名称自动判为相关。"""
    accepted, rejected, eligible = [], [], []
    scope = sorted(set(request.symbols) & INDEX_TERMS.keys())
    for item in request.news:
        text = item.title + " " + item.summary
        direct = [symbol for symbol in scope if any(term in text for term in INDEX_TERMS[symbol])]
        macro = [term for term in MACRO_TERMS if term.lower() in text.lower()]
        administrative = any(term in item.title for term in ADMIN_TERMS)
        if direct or (macro and scope and not administrative):
            eligible.append(item)
            accepted.append({"id": item.id, "symbols": direct or scope,
                             "basis": "DIRECT_INDEX_TOPIC" if direct else "MACRO_RESEARCH_HYPOTHESIS",
                             "matched_terms": macro if not direct else direct})
        else:
            rejected.append({"id": item.id, "reason": "ADMINISTRATIVE_NEWS" if administrative
                             else "NO_SUPPORTED_ETF_TOPIC"})
    audit = {"version": RELEVANCE_VERSION, "scope": scope,
             "accepted": accepted, "rejected": rejected,
             "note": "主题相关不证明短期预测能力；新闻仅作为待前瞻验证的研究特征。"}
    return request.model_copy(update={"news": eligible}), audit
