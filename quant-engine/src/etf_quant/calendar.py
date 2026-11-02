"""有明确适用年度的交易所日历；覆盖范围外保持未知，不将调休工作日认作开市日。"""

from datetime import date, timedelta

OFFICIAL_YEAR = 2026
OFFICIAL_SOURCE = "https://www.sse.com.cn/disclosure/dealinstruc/closed/c/c_20251222_10802510.shtml"
OFFICIAL_SOURCE_SZSE = "https://www.szse.cn/disclosure/notice/t20260206_618970.html"
HOLIDAY_RANGES = (
    ("2026-01-01", "2026-01-03"),
    ("2026-02-15", "2026-02-23"),
    ("2026-04-04", "2026-04-06"),
    ("2026-05-01", "2026-05-05"),
    ("2026-06-19", "2026-06-21"),
    ("2026-09-25", "2026-09-27"),
    ("2026-10-01", "2026-10-07"),
)


def official_session(day: date) -> bool | None:
    """按2026正式全年公告判定沪深开市日；未经公告覆盖的年度返回未知。"""
    if day.year != OFFICIAL_YEAR:
        return None
    return day.weekday() < 5 and not any(
        date.fromisoformat(start) <= day <= date.fromisoformat(end) for start, end in HOLIDAY_RANGES
    )


def official_calendar() -> dict:
    """生成公告覆盖期内的精确开休市集合，并附出处供数据库校核和界面追溯。"""
    first, last = date(OFFICIAL_YEAR, 1, 1), date(OFFICIAL_YEAR, 12, 31)
    days = [first + timedelta(days=i) for i in range((last - first).days + 1)]
    return {
        "dates": [str(day) for day in days if official_session(day)],
        "closed_dates": [str(day) for day in days if not official_session(day)],
        "official_coverage_start": str(first),
        "official_coverage_end": str(last),
        "source_urls": [OFFICIAL_SOURCE, OFFICIAL_SOURCE_SZSE],
        "official_version": "SSE_2025_45_2026",
    }
