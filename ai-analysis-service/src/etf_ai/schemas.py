"""时间、来源和结构化输出的严格边界；情绪不是上涨概率。"""

from typing import Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    HttpUrl,
    model_validator,
)

from etf_ai.model_settings import ModelSelection


class NewsItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=300)
    summary: str = Field(min_length=1, max_length=1500)
    source_url: HttpUrl
    source_name: str = Field(max_length=80)
    published_at: AwareDatetime
    fetched_at: AwareDatetime
    nature: Literal["REAL", "MOCK"] = "REAL"
    publication_verified: bool
    content_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    metadata: dict = Field(default_factory=dict)


class AnalysisRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    cutoff: AwareDatetime
    news: list[NewsItem] = Field(default_factory=list, max_length=15)
    symbols: list[str] = Field(
        default_factory=lambda: ["159915", "510300", "510500"], max_length=30
    )
    reserved_usd: str = "0"
    model_settings: ModelSelection | None = None

    @model_validator(mode="after")
    def time_boundary(self):
        """首次获取时间也须早于截止时间，今天抓到的旧新闻不能倒填历史。"""
        if len({n.id for n in self.news}) != len(self.news):
            raise ValueError("新闻来源ID重复")
        for item in self.news:
            if item.published_at > self.cutoff or item.fetched_at > self.cutoff:
                raise ValueError("新闻在输入截止时尚不可得")
            if not item.publication_verified:
                raise ValueError("新闻发布时间未核验")
        return self


class EventAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    market_sentiment: Literal["bullish", "neutral", "bearish"]
    sentiment_score: float = Field(ge=-1, le=1)
    risk_level: Literal["low", "medium", "high"]
    key_factors: list[str] = Field(max_length=8)
    risk_factors: list[str] = Field(max_length=8)
    source_ids: list[str] = Field(min_length=1, max_length=15)
    analysis_summary: str = Field(min_length=1, max_length=2000)

    @model_validator(mode="after")
    def sentiment_consistency(self):
        """拒绝方向与情绪分数明显矛盾的模型输出。"""
        if self.market_sentiment == "bullish" and self.sentiment_score <= 0:
            raise ValueError("看多情绪与分数矛盾")
        if self.market_sentiment == "bearish" and self.sentiment_score >= 0:
            raise ValueError("看空情绪与分数矛盾")
        return self
