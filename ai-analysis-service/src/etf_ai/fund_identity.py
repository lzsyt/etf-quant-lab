"""基金新闻的固定主体边界；只覆盖当前目录中的三只基金，不推测陌生基金归属。"""

import re

FUND_NEWS_SCHEMA_VERSION = "fund-news-evidence-v2"

FUND_IDENTITIES = {
    "460300": {
        "fund_code": "460300",
        "name": "华泰柏瑞沪深300ETF联接A",
        "asset_class": "EQUITY",
        "investment_mechanism": "沪深300权益指数ETF联接基金，净值主要反映相关权益资产变化。",
    },
    "007169": {
        "fund_code": "007169",
        "name": "易方达中债1–3年国开行债券指数A",
        "asset_class": "BOND",
        "investment_mechanism": "1至3年国开行债券指数基金，净值主要反映债券价格、利率与票息。",
    },
    "008701": {
        "fund_code": "008701",
        "name": "华夏黄金ETF联接A",
        "asset_class": "GOLD",
        "investment_mechanism": "黄金ETF联接基金，通过目标黄金ETF等资产反映国内黄金价格变化。",
    },
}

_NAME_PATTERNS = {
    "460300": r"华泰柏瑞.*沪深\s*300.*联接",
    "007169": r"易方达.*中债\s*1\s*[-–—至]\s*3\s*年国开行债券指数",
    "008701": r"华夏黄金.*联接",
}
_ASSET_PATTERNS = {
    "EQUITY": r"沪深\s*300|股票指数|权益指数|股票型?基金|权益基金",
    "BOND": r"国开(?:行)?债|国开行债券|政策性金融债|债券指数|债券基金|债基|短债基金",
    "GOLD": r"Au\s*99[.]?99|黄金|贵金属基金",
}
_DRIVER_PATTERNS = {
    "EQUITY": r"股价|股票价格|权益市场|沪深\s*300",
    "BOND": r"债券价格|债券收益|国开(?:行)?债|票息",
    "GOLD": r"金价|黄金价格|黄金现货|Au\s*99[.]?99",
}


def fund_identity(fund_code):
    """返回目录身份副本，避免请求或测试篡改后影响后续基金。"""
    return dict(FUND_IDENTITIES[fund_code])


def classify_fund_source(fund_code, title, source_name, metadata):
    """优先核对专属材料的显式归属；宏观材料可共用，专属但归属不明则拒收。"""
    owners = set()
    explicit = metadata.get("fund_code")
    if explicit is not None and str(explicit).strip():
        owners.add(str(explicit).strip())
    # 标题和固定来源标签独立作证；正文中的“本基金”不能反过来声明分析对象。
    identity_text = title + " " + source_name
    owners.update(re.findall(r"(?<!\d)(?:460300|007169|008701)(?!\d)", identity_text))
    for code, pattern in _NAME_PATTERNS.items():
        if re.search(pattern, identity_text, re.IGNORECASE):
            owners.add(code)
    disclosure = bool(
        owners
        or "FUND_DISCLOSURE" in str(metadata.get("content_scope", ""))
        or re.search(
            r"(?:基金|ETF|联接).*?(?:季报|季度报告|年度报告|年报|中期报告|半年报|招募说明书|合同|公告)",
            title,
        )
    )
    reason = None
    if len(owners) > 1:
        reason = "FUND_SOURCE_SUBJECT_CONFLICT"
    elif owners and next(iter(owners)) != fund_code:
        reason = "FUND_SOURCE_SUBJECT_MISMATCH"
    elif disclosure and not owners:
        reason = "FUND_SOURCE_SUBJECT_UNKNOWN"
    return {
        "material_scope": "FUND_SPECIFIC" if disclosure else "MACRO",
        "subject_fund_code": next(iter(owners)) if len(owners) == 1 else None,
        "reason_code": reason,
    }


def require_fund_mechanism(fund_code, texts):
    """拒绝明确的跨资产身份或主要净值机制断言；不把提及黄金的宏观比较一律封禁。"""
    asset_class = FUND_IDENTITIES[fund_code]["asset_class"]
    name = re.escape(FUND_IDENTITIES[fund_code]["name"])
    subject = rf"(?:本基金|该基金|此基金|本产品|该产品|{fund_code}|{name})"
    # 逐个分句检查，避免“黄金基金跟踪金价，而本基金持有国开债”等比较被串成一句。
    # 这是有边界的矛盾检查，不宣称能证明任意自然语言推断正确。
    for text in texts:
        if not isinstance(text, str):
            raise TypeError("FUND_MECHANISM_TEXT_INVALID")
        # “不是债基而是黄金基金”中，否定只作用于前半句；显式承接的“而是”仍指原主体。
        # 不跨越其他逗号分句继承主体，避免把独立的黄金基金比较强行解释为本基金。
        text = re.sub(
            r"(" + subject + r"[^。；;！？!?，,\n]{0,100}?)[，,]?而是",
            lambda match: match.group(1) + "；本基金是",
            text,
        )
        for clause in re.split(r"[。；;！？!?，,\n]", text or ""):
            clause = clause.strip()
            for other_class, pattern in _ASSET_PATTERNS.items():
                if other_class == asset_class:
                    continue
                assertion = re.search(
                    subject
                    + r"(?:.{0,55}?(?:跟踪|追踪|投资于|投资标的为|主要投资|持有|配置)"
                    + r"|(?:目前|实际上|本质上|主要|并非|不是|不属于|不)?(?:属于|是|为))"
                    + r".{0,25}?(?:"
                    + pattern
                    + r")",
                    clause,
                    re.IGNORECASE,
                )
                # 明确否认该机制的说明可以保留；避免拒绝“本基金不跟踪黄金”。
                if assertion and not re.search(
                    r"(?:不|并非|而非).{0,6}(?:跟踪|追踪|投资|持有|配置|属于|是|为)|不是|不属于",
                    assertion.group(),
                ):
                    raise ValueError("FUND_MECHANISM_MISMATCH")
                driver = _DRIVER_PATTERNS[other_class]
                # 未重复基金名称的“净值主要由金价决定”仍是对目标基金的直接机制断言。
                direct = re.search(
                    r"^(?:"
                    + subject
                    + r"(?:的)?)?(?:净值|收益|价格|涨跌|走势|净值方向)"
                    + r".{0,25}?(?:主要|直接|被动|跟踪|取决于|由).{0,25}?(?:"
                    + driver
                    + r")",
                    clause,
                    re.IGNORECASE,
                )
                if direct and not re.search(
                    r"(?:不|并非|而非).{0,6}(?:主要|直接|被动|跟踪|取决于|由)",
                    direct.group(),
                ):
                    raise ValueError("FUND_MECHANISM_MISMATCH")
