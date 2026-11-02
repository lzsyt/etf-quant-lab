"""基金新闻免费准备与严格校验；资料是数据，不执行网页或模型中的指令。"""

from datetime import UTC, datetime
from decimal import Decimal
from urllib.parse import urlsplit

from etf_ai.fund_identity import (
    FUND_NEWS_SCHEMA_VERSION,
    classify_fund_source,
    fund_identity,
    require_fund_mechanism,
)
from pydantic import AliasChoices, AwareDatetime, BaseModel, Field, model_validator

from etf_quant.fund_data import FundCode, canonical_hash

ASSET_TERMS = {
    "460300": ("沪深300", "权益市场", "股票市场", "资本市场", "股市", "A股"),
    "007169": ("国开", "债券", "政策性金融债", "利率", "降息", "收益率曲线"),
    "008701": ("黄金", "金价", "贵金属", "人民币汇率", "美元汇率"),
}


class FundNewsDocument(BaseModel):
    """有限摘要和原始来源；仅允许有明确首次获取时间的记录。"""

    id: str = Field(min_length=1, max_length=200)
    title: str = Field(max_length=500)
    excerpt: str = Field(
        default="", max_length=12000, validation_alias=AliasChoices("excerpt", "summary")
    )
    source_name: str = Field(max_length=200)
    source_url: str = Field(max_length=2000)
    published_at: AwareDatetime | None = None
    first_seen_at: AwareDatetime = Field(
        validation_alias=AliasChoices("first_seen_at", "fetched_at")
    )
    content_type: str = "SUMMARY"
    revision: str = "1"
    metadata: dict = Field(default_factory=dict)
    subject_fund_code: str | None = None

    @model_validator(mode="before")
    @classmethod
    def source_metadata(cls, value):
        """复用已有新闻元数据时，只继承实际材料范围，不把摘要升级为全文。"""
        data = dict(value)
        # 现有候选的summary不能因原文元数据为FULL_TEXT而被升级成全文输入。
        if "excerpt" not in data:
            data["content_type"] = "SUMMARY" if data.get("summary") else "TITLE_ONLY"
        elif data.get("content_type") == "FULL_TEXT":
            data["content_type"] = "EXCERPT"
        return data


class FundNewsPrepareRequest(BaseModel):
    """只准备与资产及截止时间有关的资料，不调用收费接口。"""

    fund_code: FundCode
    horizon: int = Field(ge=20, le=60)
    input_cutoff_at: AwareDatetime = Field(
        validation_alias=AliasChoices("input_cutoff_at", "cutoff")
    )
    documents: list[FundNewsDocument] = Field(max_length=200)


class FundNewsValidateRequest(FundNewsPrepareRequest):
    """对外部调用返回的结构做本地验证；服务端仍负责预算预留和模型调用。"""

    analysis: dict


def prepare_fund_news(request: FundNewsPrepareRequest):
    """时间、来源及资产相关性全部先过滤，并冻结输入身份，不能靠模型自报搜索。"""
    if request.horizon not in (20, 60):
        raise ValueError("UNSUPPORTED_FUND_HORIZON")
    selected, excluded, seen = [], [], set()
    for document in request.documents:
        if document.id in seen:
            raise ValueError("DUPLICATE_NEWS_ID")
        seen.add(document.id)
        url = urlsplit(document.source_url)
        reasons = []
        metadata = dict(document.metadata)
        if document.subject_fund_code:
            # 接收已冻结目录投影时仍核对标题，不能靠自报主体绕开其他基金的专属材料。
            if metadata.get("fund_code") not in (None, document.subject_fund_code):
                reasons.append("FUND_SOURCE_SUBJECT_CONFLICT")
            metadata["fund_code"] = document.subject_fund_code
        subject = classify_fund_source(
            request.fund_code, document.title, document.source_name, metadata
        )
        if subject["reason_code"]:
            reasons.append(subject["reason_code"])
        if url.scheme not in ("https", "http") or not url.hostname or url.username or url.password:
            reasons.append("UNSAFE_SOURCE_URL")
        if document.first_seen_at > request.input_cutoff_at or (
            document.published_at and document.published_at > request.input_cutoff_at
        ):
            reasons.append("NEWS_AFTER_CUTOFF")
        text = document.title + " " + document.excerpt
        matches = [term for term in ASSET_TERMS[request.fund_code] if term in text]
        if not matches:
            reasons.append("NO_ASSET_RELEVANCE")
        if reasons:
            excluded.append({"id": document.id, "reason_codes": reasons})
            continue
        selected.append(
            document.model_dump(mode="json")
            | {
                "relevance_terms": matches,
                "material_scope": subject["material_scope"],
                "subject_fund_code": subject["subject_fund_code"],
                "relevance_basis": "ASSET_KEYWORD_FILTER_NOT_MODEL_CONCLUSION",
                "content_hash": canonical_hash(document.model_dump(mode="json")),
            }
        )
    snapshot = {
        "fund_code": request.fund_code,
        "fund_identity": fund_identity(request.fund_code),
        "horizon": request.horizon,
        "input_cutoff_at": request.input_cutoff_at.isoformat(),
        "documents": selected,
        "schema_version": FUND_NEWS_SCHEMA_VERSION,
    }
    return snapshot | {
        "input_snapshot_id": canonical_hash(snapshot),
        "input_snapshot_hash": canonical_hash(snapshot),
        "excluded": excluded,
        "analysis_status": "READY" if selected else "INSUFFICIENT_INPUT",
        "sentiment_score": None,
        "facts": [],
        "inferences": [],
        "uncertainties": ["尚未调用基金专用分析；已准备的资料不等于模型结论"],
        "reason_codes": ["NOT_ANALYZED"] if selected else ["NEWS_NOT_AVAILABLE"],
        "forecast_usage": "NOT_USED",
        "paid_calls": 0,
    }


def validate_fund_news(request: FundNewsValidateRequest):
    """引用、原文、范围及时间严格匹配；高风险保持风险约束，不降级绕开。"""
    prepared = prepare_fund_news(request)
    documents = {row["id"]: row for row in prepared["documents"]}
    result = request.analysis
    if (
        result.get("fund_code") != request.fund_code
        or result.get("horizon") != request.horizon
        or result.get("input_snapshot_id") != prepared["input_snapshot_id"]
        or result.get("schema_version") != FUND_NEWS_SCHEMA_VERSION
    ):
        raise ValueError("NEWS_ANALYSIS_IDENTITY_MISMATCH")
    if result.get("analysis_status") not in ("VALID", "INSUFFICIENT_INPUT"):
        raise ValueError("NEWS_ANALYSIS_STATUS_INVALID")
    require_fund_mechanism(
        request.fund_code,
        [result.get("summary", "")]
        + [item.get("text", item.get("mechanism", "")) for item in result.get("inferences", [])],
    )
    score = result.get("sentiment_score")
    if result["analysis_status"] == "INSUFFICIENT_INPUT":
        if score is not None or result.get("facts") or result.get("inferences"):
            raise ValueError("INSUFFICIENT_NEWS_HAS_CONCLUSION")
        return prepared | {"analysis_status": "INSUFFICIENT_INPUT", "analysis": result}
    if not documents or score is None:
        raise ValueError("VALID_NEWS_REQUIRES_EVIDENCE")
    try:
        number = Decimal(str(score))
        if not number.is_finite() or not -1 <= number <= 1:
            raise ValueError("NEWS_SCORE_OUT_OF_RANGE")
    except ArithmeticError as error:
        raise ValueError("NEWS_SCORE_INVALID") from error
    if result.get("stance") not in ("POSITIVE", "NEGATIVE", "MIXED", "NEUTRAL"):
        raise ValueError("NEWS_STANCE_INVALID")
    if (result["stance"] == "POSITIVE" and number <= 0) or (
        result["stance"] == "NEGATIVE" and number >= 0
    ):
        raise ValueError("NEWS_STANCE_SCORE_CONFLICT")
    facts = result.get("facts", [])
    if not facts:
        raise ValueError("NEWS_FACTS_REQUIRED")
    for fact in facts:
        source_ids = fact.get("source_ids", [])
        quote = fact.get("quote", "")
        if not source_ids or any(key not in documents for key in source_ids):
            raise ValueError("UNKNOWN_NEWS_SOURCE_ID")
        if (
            not quote
            or len(quote) > 240
            or not any(
                quote in documents[key]["title"] or quote in documents[key]["excerpt"]
                for key in source_ids
            )
        ):
            raise ValueError("NEWS_QUOTE_NOT_IN_INPUT")
    for inference in result.get("inferences", []):
        indices = inference.get("basis_fact_indices", [])
        if not indices or any(
            type(index) is not int or not 0 <= index < len(facts) for index in indices
        ):
            raise ValueError("NEWS_INFERENCE_WITHOUT_FACT")
    if not isinstance(result.get("uncertainties"), list) or not result["uncertainties"]:
        raise ValueError("NEWS_UNCERTAINTY_REQUIRED")
    risks = result.get("risk_flags", [])
    if not isinstance(risks, list) or any(not isinstance(risk, str) for risk in risks):
        raise ValueError("NEWS_RISK_FLAGS_INVALID")
    return prepared | {
        "analysis_status": "VALID",
        "analysis": result,
        "sentiment_score": str(number),
        "score_scale": {"min": "-1", "max": "1", "meaning": "news_sentiment_not_probability"},
        "validated_at": datetime.now(UTC).isoformat(),
        "forecast_usage": "DISPLAY_ONLY",
        "adjustment_blocked_by_risk": bool(risks),
        "reason_codes": ["NO_VALIDATED_NEWS_FUSION"],
    }
