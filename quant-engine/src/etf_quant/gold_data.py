"""黄金独立数据契约：真实来源、双时间与修订保留，不把回填时刻冒充历史公开时刻。"""

import calendar
import csv
import hashlib
import io
import itertools
import json
import platform
import re
import subprocess
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo

import httpx
import pandas as pd
from pydantic import BaseModel, Field, field_validator

BENCHMARK = "SGE_AU9999_CLOSE"
SGE_URL = "https://www.sge.com.cn/graph/Dailyhq"
SGE_PAGE = "https://www.sge.com.cn/sjzx/mrhq"
WORLD_BANK_PAGE = "https://www.worldbank.org/en/research/commodity-markets"
# Windows固定使用系统原生公开HTTP客户端；不在403后切换身份或处理任何安全挑战。
SGE_WINDOWS_SCRIPT = r"""
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
    $response = Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri 'https://www.sge.com.cn/graph/Dailyhq' `
        -Body @{instid='Au99.99'} -TimeoutSec 18
    [Console]::Out.Write($response.Content)
} catch {
    $status = 0
    if ($_.Exception.Response) {
        try { $status = [int]$_.Exception.Response.StatusCode } catch { $status = 0 }
    }
    [Console]::Error.Write('SGE_HTTP_' + $status)
    exit 1
}
"""
SERIES = {
    BENCHMARK: ("CNY/g", "上海黄金交易所 Au99.99 官方日收盘", SGE_PAGE),
    "FRED_DEXCHUS": (
        "CNY/USD",
        "美联储 H.10 纽约午间美元兑人民币",
        "https://fred.stlouisfed.org/series/DEXCHUS",
    ),
    "FRED_DFII10": (
        "percent",
        "美国10年期通胀保值国债实际收益率",
        "https://fred.stlouisfed.org/series/DFII10",
    ),
    "FRED_DTWEXBGS": ("index", "美联储广义美元指数", "https://fred.stlouisfed.org/series/DTWEXBGS"),
    "WORLD_BANK_GOLD_MONTHLY": ("USD/troy_oz", "世界银行Pink Sheet国际黄金月均价", WORLD_BANK_PAGE),
}


class GoldObservation(BaseModel):
    """所有数值使用十进制文本传输；未知公开时间保持空值。"""

    series_id: str
    observation_date: date
    value: Decimal
    unit: str
    source: str
    source_url: str
    available_at: datetime | None = None
    availability_precision: str = "UNKNOWN_HISTORICAL"
    fetched_at: datetime
    revision: str
    quality: str = "REAL_OFFICIAL_CURRENT_VINTAGE"
    details: dict = Field(default_factory=dict)

    @field_validator("value")
    @classmethod
    def finite_value(cls, value):
        """禁止NaN/无穷值进入数据库和训练。"""
        if not value.is_finite():
            raise ValueError("黄金观察值必须是有限十进制数")
        return value

    @field_validator("available_at", "fetched_at")
    @classmethod
    def aware_time(cls, value):
        """时间必须含时区，避免美国数据被误当作北京时间。"""
        if value is not None and value.tzinfo is None:
            raise ValueError("黄金时间必须包含时区")
        return value


class GoldSyncRequest(BaseModel):
    """增量过滤边界只限制保存范围，官方整段图表不作分页猜测。"""

    start: date = date(2000, 1, 1)
    end: date | None = None


class GoldSourceFailure(ValueError):
    """仅传递受控故障分类，不将外部HTML或系统异常正文写进日志/健康接口。"""

    def __init__(self, code, http_status=None):
        """保存公开HTTP状态便于区别安全拒绝、超时与数据格式变化。"""
        super().__init__(code)
        self.code = code
        self.http_status = http_status


def fetch_sge(client, transport):
    """单次使用预选客户端请求固定公开接口，拒绝后不换客户端、不仿造身份或降级TLS。"""
    if transport == "WINDOWS_INVOKE_WEBREQUEST":
        try:
            result = subprocess.run(
                [
                    "powershell.exe",
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    SGE_WINDOWS_SCRIPT,
                ],
                capture_output=True,
                encoding="utf-8-sig",
                timeout=25,
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise GoldSourceFailure("SGE_NATIVE_TIMEOUT") from error
        except OSError as error:
            raise GoldSourceFailure("SGE_NATIVE_CLIENT_UNAVAILABLE") from error
        if result.returncode != 0:
            match = re.fullmatch(r"SGE_HTTP_(\d{1,3})", result.stderr.strip())
            status = int(match.group(1)) if match else 0
            raise GoldSourceFailure(
                f"SGE_HTTP_{status}" if status else "SGE_NATIVE_REQUEST_FAILED", status or None
            )
        try:
            return json.loads(result.stdout)
        except ValueError as error:
            raise GoldSourceFailure("SGE_INVALID_JSON") from error
    response = client.post(SGE_URL, data={"instid": "Au99.99"}, headers={"Referer": SGE_PAGE})
    response.raise_for_status()
    return response.json()


def canonical_hash(value):
    """稳定JSON摘要绑定来源内容，而不是把每次抓取都当成数据修订。"""
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def make_observation(series_id, day, value, fetched_at, details=None):
    """官方当前历史版本缺逐日首次发布时间证据，明确标为非严格点时。"""
    unit, source, url = SERIES[series_id]
    number = Decimal(str(value))
    if not number.is_finite() or (series_id != "FRED_DFII10" and number <= 0):
        raise ValueError("来源返回无效黄金或宏观价格")
    payload = {
        "series_id": series_id,
        "observation_date": str(day),
        "value": str(number),
        "unit": unit,
        "source": source,
        "source_url": url,
        "available_at": None,
        "availability_precision": "UNKNOWN_HISTORICAL",
        "fetched_at": fetched_at.isoformat(),
        "revision": canonical_hash([series_id, str(day), str(number), details or {}]),
        "quality": "REAL_OFFICIAL_CURRENT_VINTAGE",
        "details": {
            "pit_status": "CURRENT_VINTAGE_NOT_STRICT_PIT",
            "public_time_note": "首次公开时刻及历史修订不可证实；抓取时间不是历史可用时间",
            **(details or {}),
        },
    }
    return GoldObservation.model_validate(payload).model_dump(mode="json")


def parse_sge(payload, fetched_at):
    """解析交易所K线的开、收、低、高；只将收盘值写入唯一基准序列。"""
    if not isinstance(payload, dict):
        raise ValueError("SGE图表根结构发生变化")  # noqa: TRY004 对外来源契约错误统一422。
    rows = payload.get("time")
    if not isinstance(rows, list):
        raise ValueError("SGE图表缺少time数组")  # noqa: TRY004 固定以业务422表达来源契约变化。
    result, seen = [], set()
    local_now = fetched_at.astimezone(ZoneInfo("Asia/Shanghai"))
    today = local_now.date()
    for row in rows:
        if not isinstance(row, list) or len(row) != 5:
            raise ValueError("SGE图表字段数量变化")
        day = date.fromisoformat(str(row[0]))
        if day > today or day in seen:
            raise ValueError("SGE图表存在未来日期或重复日期")
        seen.add(day)
        # 盘中图表可能已有当日临时行，只能等正式日盘收盘后进入基准。
        if day == today and (local_now.hour, local_now.minute) < (15, 30):
            continue
        opening, close, low, high = [Decimal(str(value)) for value in row[1:]]
        if any(not x.is_finite() or x <= 0 for x in (opening, close, low, high)):
            raise ValueError("SGE图表存在空值或无效价格")
        inconsistent = low > high or not low <= close <= high or not low <= opening <= high
        result.append(
            make_observation(
                BENCHMARK,
                day,
                close,
                fetched_at,
                {
                    "field": "close",
                    "raw_ohlc": [str(x) for x in (opening, close, low, high)],
                    "ohlc_consistency": "INCONSISTENT_SOURCE_ROW" if inconsistent else "PASS",
                    "usage": "仅收盘进入模型，官方OHLC矛盾原样保留并披露，不修补、不删除交易日",
                },
            )
        )
    return sorted(result, key=lambda row: row["observation_date"])


def parse_fred(text, series_id, fetched_at):
    """FRED下载为当前修订版；宏观不进入首版历史模型，不按所属日期提前生效。"""
    symbol = series_id.removeprefix("FRED_")
    reader = csv.DictReader(io.StringIO(text))
    if reader.fieldnames != ["observation_date", symbol]:
        raise ValueError("FRED响应不是指定系列CSV")
    result, seen = [], set()
    for row in reader:
        if row[symbol] in ("", "."):
            continue
        day = date.fromisoformat(row["observation_date"])
        if day > fetched_at.date() or day in seen:
            raise ValueError("FRED存在未来或重复观察日期")
        seen.add(day)
        result.append(
            make_observation(
                series_id,
                day,
                row[symbol],
                fetched_at,
                {
                    "historical_model_eligible": False,
                    "reason": "未取得ALFRED逐次修订和实际发布日期，首版仅用于当前宏观观察",
                    "quote_timezone": "America/New_York",
                },
            )
        )
    return result


def parse_world_bank(content, fetched_at, download_url):
    """固定读取Gold月均列及单位，保存2025年6月方法变化，历史公开时刻不猜测。"""
    sheet = pd.read_excel(io.BytesIO(content), sheet_name="Monthly Prices", header=None)
    if "World Bank Commodity Price Data" not in str(sheet.iloc[0, 0]):
        raise ValueError("世界银行黄金文件标题不匹配")
    headers = list(sheet.iloc[4])
    if "Gold" not in headers:
        raise ValueError("世界银行文件缺Gold列")
    column = headers.index("Gold")
    if str(sheet.iloc[5, column]).strip() != "($/troy oz)":
        raise ValueError("世界银行黄金单位发生变化")
    vintage_text = str(sheet.iloc[3, 0])
    description = pd.read_excel(io.BytesIO(content), sheet_name="Description", header=None)
    gold_notes = []
    for _, row in description.iterrows():
        if any(str(value).startswith("Gold,") for value in row):
            gold_notes = [str(value) for value in row if pd.notna(value)]
            break
    if not gold_notes:
        raise ValueError("世界银行缺黄金方法描述")
    result = []
    for _, row in sheet.iloc[6:].iterrows():
        match = re.fullmatch(r"(\d{4})M(\d{2})", str(row.iloc[0]))
        if not match:
            continue
        year, month = map(int, match.groups())
        day = date(year, month, calendar.monthrange(year, month)[1])
        if day >= fetched_at.date().replace(day=1):
            raise ValueError("世界银行月均包含未结束的观察月")
        result.append(
            make_observation(
                "WORLD_BANK_GOLD_MONTHLY",
                day,
                row.iloc[column],
                fetched_at,
                {
                    "frequency": "MONTHLY",
                    "price_field": "monthly_average_USD_per_troy_ounce",
                    "observation_period": f"{year:04d}-{month:02d}",
                    "download_url": download_url,
                    "file_vintage": vintage_text,
                    "methodology": gold_notes,
                    "methodology_break": "2025-06起现货日价月均；此前为伦敦午盘定盘日价月均",
                    "historical_model_eligible": False,
                    "usage": "仅国际月均宏观参考，不是今日现货或人民币可成交价，不与日汇率强行折算",
                    "license": "World Bank Pink Sheet，按公开数据条款注明World Bank及原数据提供方",
                    "license_url": "https://data.worldbank.org/summary-terms-of-use",
                },
            )
        )
    return result


def source_health(series_id, rows, checked_at, failure=None):
    """分别表达失败、空数据、过期和部分覆盖；失败不刷新旧值日期。"""
    last = rows[-1]["observation_date"] if rows else None
    age = (
        (checked_at.astimezone(ZoneInfo("Asia/Shanghai")).date() - date.fromisoformat(last)).days
        if last
        else None
    )
    max_age = 10 if series_id == BENCHMARK else 62 if series_id == "WORLD_BANK_GOLD_MONTHLY" else 14
    status = (
        "FAILED" if failure else "EMPTY" if not rows else "STALE" if age > max_age else "HEALTHY"
    )
    if (
        rows
        and series_id == BENCHMARK
        and (date.fromisoformat(last) - date.fromisoformat(rows[0]["observation_date"])).days < 3653
        and status == "HEALTHY"
    ):
        status = "PARTIAL_COVERAGE"
    return {
        "series_id": series_id,
        "name": SERIES[series_id][1],
        "source_url": SERIES[series_id][2],
        "unit": SERIES[series_id][0],
        "status": status,
        "checked_at": checked_at.isoformat(),
        "last_observation_date": last,
        "coverage_start": rows[0]["observation_date"] if rows else None,
        "coverage_end": last,
        "count": len(rows),
        "data_age_days": age,
        "max_age_days": max_age,
        "error": failure,
        "pit_status": "CURRENT_VINTAGE_NOT_STRICT_PIT",
        "usage_terms": "公开官方数据，仅在登录研究台展示并注明来源；无商用数据授权或实时成交承诺",
        "details": {
            "ohlc_inconsistent_rows": sum(
                r["details"].get("ohlc_consistency") == "INCONSISTENT_SOURCE_ROW" for r in rows
            ),
            "calendar": calendar_quality(rows) if series_id == BENCHMARK else None,
        },
    }


def sync_gold(request: GoldSyncRequest):
    """每个固定来源单次有限超时；失败保留其他成功来源，不无限重试。"""
    now = datetime.now(UTC)
    end = request.end or now.astimezone(ZoneInfo("Asia/Shanghai")).date()
    if request.start > end or end > now.astimezone(ZoneInfo("Asia/Shanghai")).date():
        raise ValueError("黄金采集日期范围无效")
    observations, sources, dates = [], [], []
    with httpx.Client(timeout=httpx.Timeout(18, connect=5), follow_redirects=True) as client:
        for series_id in SERIES:
            transport = (
                "WINDOWS_INVOKE_WEBREQUEST"
                if series_id == BENCHMARK and platform.system() == "Windows"
                else "HTTPX"
            )
            try:
                if series_id == BENCHMARK:
                    payload = fetch_sge(client, transport)
                    fetched = datetime.now(UTC)
                    rows = parse_sge(payload, fetched)
                    dates = [row["observation_date"] for row in rows]
                elif series_id == "WORLD_BANK_GOLD_MONTHLY":
                    page = client.get(WORLD_BANK_PAGE)
                    page.raise_for_status()
                    # 只跟随官方页面公开xlsx链接，旧固定URL可能返回过期版本，不能冒称最新。
                    matches = re.findall(
                        r'https://thedocs\.worldbank\.org/[^"<>\s]+/CMO-Historical-Data-Monthly\.xlsx',
                        page.text,
                    )
                    if not matches:
                        raise ValueError("世界银行当前月度下载链接未找到")
                    response = client.get(matches[0])
                    response.raise_for_status()
                    fetched = datetime.now(UTC)
                    rows = parse_world_bank(response.content, fetched, matches[0])
                else:
                    response = client.get(
                        "https://fred.stlouisfed.org/graph/fredgraph.csv",
                        params={"id": series_id.removeprefix("FRED_")},
                    )
                    response.raise_for_status()
                    fetched = datetime.now(UTC)
                    rows = parse_fred(response.text, series_id, fetched)
                health = source_health(series_id, rows, fetched)
                health["transport"] = transport
                sources.append(health)
                observations.extend(
                    row for row in rows if str(request.start) <= row["observation_date"] <= str(end)
                )
            except (httpx.HTTPError, ValueError, KeyError, InvalidOperation) as error:
                failure = (
                    error.code if isinstance(error, GoldSourceFailure) else type(error).__name__
                )
                health = source_health(series_id, [], datetime.now(UTC), failure)
                health["transport"] = transport
                health["http_status"] = (
                    error.http_status
                    if isinstance(error, GoldSourceFailure)
                    else error.response.status_code
                    if isinstance(error, httpx.HTTPStatusError)
                    else None
                )
                sources.append(health)
    sources.append(
        {
            "series_id": "INTERNATIONAL_GOLD_USD",
            "name": "国际美元金价（待可用授权源）",
            "source_url": "https://www.gold.org/goldhub/data/gold-prices",
            "unit": "USD/troy_oz",
            "status": "UNAVAILABLE",
            "count": 0,
            "checked_at": now.isoformat(),
            "error": "2026-10-26核验：FRED旧LBMA黄金序列404；WGC下载要求登录/使用权限，未购买或绕过",
            "details": {
                "conversion": "国际金价×USD/CNY÷31.1034768仅折算参考，缺同一时点输入时不计算"
            },
        }
    )
    return {
        "observations": observations,
        "sources": sources,
        "collected_at": datetime.now(UTC).isoformat(),
        "calendar": {
            "version": "SGE_OBSERVED_SESSIONS_V1",
            "status": "HISTORICAL_OBSERVED_ONLY",
            "dates": dates,
            "source_urls": [SGE_PAGE],
            "future_status": "ESTIMATED_NOT_CONFIRMED",
        },
    }


def asof_observation(observations, series_id, cutoff, max_age_days):
    """按有证据的公开时刻或系统首次抓取时刻向后生效，禁止反向填充。"""
    eligible = []
    for row in observations:
        observation = (
            row if isinstance(row, GoldObservation) else GoldObservation.model_validate(row)
        )
        effective = observation.available_at or observation.fetched_at
        if (
            observation.series_id == series_id
            and effective <= cutoff
            and observation.observation_date <= cutoff.date()
        ):
            age = (cutoff.date() - observation.observation_date).days
            if 0 <= age <= max_age_days:
                eligible.append(observation)
    return max(eligible, key=lambda row: (row.observation_date, row.fetched_at), default=None)


def estimated_target(reference, horizon):
    """仅提供工作日估计；上金所未来节假日尚未确认，实际到期按后续交易日记录追加。"""
    current, remaining = reference, horizon
    while remaining:
        current += timedelta(days=1)
        if current.weekday() < 5:
            remaining -= 1
    return current


def calendar_quality(rows):
    """列出较长观察间隔，不擅自把来源遗漏解释为法定休市。"""
    dates = sorted({date.fromisoformat(row["observation_date"]) for row in rows})
    gaps = [
        {"previous": str(left), "next": str(right), "calendar_days": (right - left).days}
        for left, right in itertools.pairwise(dates)
        if (right - left).days > 4
    ]
    return {
        "version": "SGE_OBSERVED_SESSIONS_V1",
        "session_count": len(dates),
        "dates_sha256": canonical_hash([str(day) for day in dates]),
        "completeness": "OFFICIAL_OBSERVATIONS_NOT_INDEPENDENTLY_CALENDAR_VERIFIED",
        "gaps_over_four_calendar_days": gaps,
        "warning": "长间隔可能为春节/国庆或来源缺失；未逐年核验独立休市公告，不把间隔自动定性为休市",
    }
