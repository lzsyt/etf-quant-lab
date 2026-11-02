"""场外基金净值独立数据域；只接收实际观测，不把ETF行情、累计净值当总回报。"""

import hashlib
import json
import multiprocessing as mp
import os
import re
import threading
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation
from itertools import pairwise
from typing import Literal
from zoneinfo import ZoneInfo

import httpx
from pydantic import AwareDatetime, BaseModel, Field, model_validator

FUND_CODES = ("460300", "007169", "008701")
FundCode = Literal["460300", "007169", "008701"]
PROVIDER = "EASTMONEY_FUND_NAV"
SOURCE = "https://fund.eastmoney.com/pingzhongdata/"
FETCH_LOCK = threading.Lock()


def canonical_hash(value):
    """对原始输入做稳定摘要，供持久层区分供应商修订，不混入抓取时间。"""
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=False, default=str).encode()
    ).hexdigest()


class FundObservation(BaseModel):
    """实际净值及可得时间；未知发布时间必须为空，当前下载不证明历史点时可得。"""

    fund_code: FundCode
    nav_date: date
    unit_nav: Decimal = Field(gt=0, allow_inf_nan=False)
    accumulated_nav: Decimal | None = Field(default=None, gt=0, allow_inf_nan=False)
    published_at: AwareDatetime | None = None
    first_observed_at: AwareDatetime
    fetched_at: AwareDatetime
    provider: str = PROVIDER
    source_record_id: str
    revision_hash: str = Field(min_length=64, max_length=64)
    quality_status: Literal["VALID", "WARNING", "REJECTED"] = "VALID"
    quality_flags: list[str] = Field(default_factory=list)
    nature: Literal["REAL", "DEMO"] = "REAL"
    source_url: str = SOURCE
    subscription_status: str | None = None
    redemption_status: str | None = None
    corporate_action_note: str | None = None

    @model_validator(mode="after")
    def valid_times(self):
        """拒绝未来净值和晚于抓取时间的可得时间，避免日期伪装。"""
        if self.first_observed_at > self.fetched_at:
            raise ValueError("FIRST_OBSERVED_AFTER_FETCH")
        if self.nav_date > self.fetched_at.date():
            raise ValueError("NAV_DATE_IN_FUTURE")
        if self.published_at and self.published_at > self.fetched_at:
            raise ValueError("PUBLISHED_AFTER_FETCH")
        return self


class FundFetchRequest(BaseModel):
    """固定来源与三只基金白名单，限制历史范围和请求页数。"""

    fund_code: FundCode
    start: date = date(2010, 1, 1)
    end: date = Field(default_factory=lambda: datetime.now(UTC).date())

    @model_validator(mode="after")
    def valid_range(self):
        """日期错误直接拒绝，不静默交换查询区间。"""
        if self.start > self.end or self.end > datetime.now(UTC).date():
            raise ValueError("INVALID_NAV_RANGE")
        return self


def normalize_nav(records, request, fetched_at):
    """保留原始经济字段摘要，拒收冲突重复日，突变保留并明确分红待核验。"""
    rows, rejected, seen = [], [], {}
    for raw in records:
        try:
            day = date.fromisoformat(str(raw["FSRQ"]))
            if not request.start <= day <= request.end:
                continue
            digest = canonical_hash(raw)
            if day in seen:
                if seen[day] != digest:
                    raise ValueError("CONFLICTING_SOURCE_DATE")
                continue
            seen[day] = digest
            accumulated = raw.get("LJJZ")
            note = raw.get("FHSP") or raw.get("FHFCZ") or None
            row = FundObservation(
                fund_code=request.fund_code,
                nav_date=day,
                unit_nav=Decimal(raw["DWJZ"]),
                accumulated_nav=None if accumulated in (None, "", "--") else Decimal(accumulated),
                first_observed_at=fetched_at,
                fetched_at=fetched_at,
                source_record_id=f"{request.fund_code}:{day}",
                revision_hash=digest,
                subscription_status=raw.get("SGZT"),
                redemption_status=raw.get("SHZT"),
                corporate_action_note=note,
                quality_flags=["HISTORICAL_AVAILABILITY_UNVERIFIED"],
            )
            rows.append(row)
        except (ValueError, TypeError, KeyError, InvalidOperation):
            rejected.append({"nav_date": str(raw.get("FSRQ", "")), "reason": "INVALID_NAV_RECORD"})
    rows.sort(key=lambda row: row.nav_date)
    for previous, current in pairwise(rows):
        if abs(current.unit_nav / previous.unit_nav - 1) > Decimal("0.10"):
            current.quality_status = "WARNING"
            current.quality_flags.append("LARGE_CHANGE_CORPORATE_ACTION_REVIEW")
    return rows, rejected


def parse_nav_script(script, request):
    """静态公开净值文件仅解析指定JSON变量，不执行JavaScript；先核对基金身份。"""

    def variable(name):
        """从受限变量赋值中读取JSON，禁止eval或执行供应商脚本。"""
        match = re.search(r"var\s+" + name + r"\s*=\s*(.*?);", script)
        if not match:
            raise ValueError("NAV_PROVIDER_SCHEMA_INVALID")
        return json.loads(match.group(1), parse_float=Decimal)

    if variable("fS_code") != request.fund_code:
        raise ValueError("NAV_PROVIDER_FUND_IDENTITY_MISMATCH")
    values = variable("Data_netWorthTrend")
    accumulated = dict(variable("Data_ACWorthTrend"))
    if not isinstance(values, list) or len(values) > 20000:
        raise ValueError("NAV_PROVIDER_SIZE_INVALID")
    records = []
    for row in values:
        day = (
            datetime.fromtimestamp(row["x"] / 1000, UTC)
            .astimezone(ZoneInfo("Asia/Shanghai"))
            .date()
        )
        records.append(
            {
                "FSRQ": str(day),
                "DWJZ": str(row["y"]),
                "LJJZ": str(accumulated[row["x"]]) if row["x"] in accumulated else None,
                "FHSP": row.get("unitMoney") or None,
            }
        )
    return records


def _fetch_fund_nav_direct(request: FundFetchRequest):
    """一次读取固定公开历史文件，绕过分页源1000条截断；不执行脚本或回退演示。"""
    fetched = datetime.now(UTC)
    source_url = SOURCE + request.fund_code + ".js"
    with (
        httpx.Client(
            timeout=httpx.Timeout(15, connect=5),
            trust_env=os.getenv("ETF_MARKET_ENV_PROXY", "false").lower() == "true",
            follow_redirects=False,
            headers={"Referer": "https://fund.eastmoney.com/", "User-Agent": "Mozilla/5.0"},
        ) as client,
    ):
        try:
            with client.stream("GET", source_url) as response:
                response.raise_for_status()
                parts, size = [], 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > 8_000_000:
                        raise ValueError("NAV_PROVIDER_RESPONSE_TOO_LARGE")
                    parts.append(chunk)
                script = b"".join(parts).decode("utf-8-sig")
            records = parse_nav_script(script, request)
        except (httpx.HTTPError, KeyError, TypeError, json.JSONDecodeError) as error:
            raise ValueError("NAV_PROVIDER_UNAVAILABLE:" + type(error).__name__) from error
    rows, rejected = normalize_nav(records, request, fetched)
    for row in rows:
        row.source_url = source_url
    return {
        "fund_code": request.fund_code,
        "nature": "REAL",
        "provider": PROVIDER,
        "source_url": source_url,
        "fetched_at": fetched.isoformat(),
        "observations": [row.model_dump(mode="json") for row in rows],
        "count": len(rows),
        "rejected": rejected,
        "complete": not rejected,
        "latest_nav_date": str(rows[-1].nav_date) if rows else None,
        "corporate_action_status": "NOT_VERIFIED_NAV_CHANGE_ONLY",
        "calendar_status": "CHANNEL_RULES_UNVERIFIED",
        "fees_status": "CHANNEL_RULES_UNVERIFIED",
        "warnings": ["HISTORICAL_AVAILABILITY_UNVERIFIED", "NOT_TOTAL_RETURN"],
    }


def _fund_fetch_worker(connection, request):
    """独立进程限制DNS与第三方网络的总耗时，错误不透传认证或代理配置。"""
    try:
        connection.send(
            {"result": _fetch_fund_nav_direct(FundFetchRequest.model_validate(request))}
        )
    except Exception as error:  # noqa: BLE001 — 子进程边界必须完整收敛第三方异常
        connection.send({"error": type(error).__name__})
    finally:
        connection.close()


def fetch_fund_nav(request: FundFetchRequest):
    """在墙钟上限内采集；DNS不受HTTP超时控制时也能终止，不占死持久任务队列。"""
    timeout = float(os.getenv("ETF_FUND_FETCH_TIMEOUT_SECONDS", "45"))
    if not 1 <= timeout <= 120:
        raise ValueError("INVALID_FUND_FETCH_TIMEOUT")
    with FETCH_LOCK:
        context = mp.get_context("spawn")
        receiving, sending = context.Pipe(duplex=False)
        process = context.Process(
            target=_fund_fetch_worker, args=(sending, request.model_dump(mode="json"))
        )
        process.start()
        sending.close()
        try:
            if not receiving.poll(timeout):
                raise ValueError("NAV_PROVIDER_TIMEOUT")
            response = receiving.recv()
            if "error" in response:
                raise ValueError("NAV_PROVIDER_UNAVAILABLE:" + response["error"])
            return response["result"]
        except EOFError as error:
            raise ValueError("NAV_PROVIDER_WORKER_EXITED") from error
        finally:
            receiving.close()
            process.join(timeout=0.2)
            if process.is_alive():
                process.terminate()
                process.join(timeout=2)
            if process.is_alive():
                process.kill()
                process.join(timeout=1)
