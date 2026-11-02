"""跨 Java/Python 的边界模型；交易价格和数量始终保留十进制语义。"""

from datetime import date
from decimal import Decimal
from typing import Any, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator


class Bar(BaseModel):
    model_config = ConfigDict(extra="forbid")
    symbol: str = Field(pattern=r"^\d{6}$")
    trade_date: date
    open: Decimal = Field(gt=0)
    high: Decimal = Field(gt=0)
    low: Decimal = Field(gt=0)
    close: Decimal = Field(gt=0)
    volume: Decimal = Field(ge=0)
    amount: Decimal | None = Field(default=None, ge=0)
    price_basis: Literal["RAW", "QFQ", "HFQ"] = "RAW"
    source: str
    nature: Literal["REAL", "DEMO", "CSV_UNVERIFIED"]
    fetched_at: AwareDatetime
    quality_status: Literal["VALID", "WARNING"] = "VALID"
    quality_flags: list[str] = Field(default_factory=list)
    raw_values: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def valid_price_range(self):
        """所有接口统一约束OHLC，不能通过训练或回测入口绕过采集校验。"""
        if self.high < max(self.open, self.close) or self.low > min(self.open, self.close):
            raise ValueError("INVALID_OHLC_RANGE")
        return self


class FetchRequest(BaseModel):
    symbol: str = Field(pattern=r"^\d{6}$")
    start: date
    end: date
    provider: Literal["EASTMONEY", "SINA", "TENCENT", "DEMO", "CSV"] = "EASTMONEY"
    price_basis: Literal["RAW", "QFQ", "HFQ"] = "RAW"
    csv_content: str | None = Field(default=None, max_length=3_000_000)
    expected_dates: list[date] = Field(default_factory=list)


class BarsRequest(BaseModel):
    bars: list[Bar] = Field(max_length=30000)
