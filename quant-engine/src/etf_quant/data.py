"""真实数据源显式切换；绝不把合成行情作为网络失败后的替代行情。"""

import json
import multiprocessing as mp
import os
import threading
from datetime import UTC, date, datetime, time
from decimal import Decimal, InvalidOperation
from io import StringIO
from itertools import pairwise
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
import requests

from etf_quant.calendar import official_calendar, official_session
from etf_quant.schemas import Bar, FetchRequest

FETCH_LOCK = threading.BoundedSemaphore(1)
MARKET_ZONE = ZoneInfo("Asia/Shanghai")
FINAL_BAR_TIME = time(15, 15)


def validate_final_bar(bar: Bar):
    """真实日线必须在交易日收盘宽限后取得，模型不得消费未完成当天行情。"""
    if bar.nature != "REAL":
        return
    fetched = bar.fetched_at.astimezone(MARKET_ZONE)
    if bar.trade_date > fetched.date() or (
        bar.trade_date == fetched.date() and fetched.time().replace(tzinfo=None) < FINAL_BAR_TIME
    ):
        raise ValueError("INCOMPLETE_SESSION")
    if bar.trade_date.weekday() >= 5 or official_session(bar.trade_date) is False:
        raise ValueError("NOT_TRADING_SESSION")


def _ak_worker(conn, function: str, arguments: dict):
    """子进程为不受控的第三方 HTTP 调用设置超时，并隔离库的全局配置。"""
    try:
        import requests

        original = requests.sessions.Session.request

        def bounded_request(self, method, url, **kwargs):
            kwargs.setdefault("timeout", 12)
            # 国内行情请求不继承开发工具专属代理；需要代理时由项目独立显式开启。
            self.trust_env = os.environ.get("ETF_MARKET_ENV_PROXY", "false").lower() == "true"
            return original(self, method, url, **kwargs)

        requests.sessions.Session.request = bounded_request
        import akshare as ak

        frame = getattr(ak, function)(**arguments)
        conn.send({"records": json.loads(frame.to_json(orient="records", date_format="iso"))})
    except Exception as error:  # noqa: BLE001 — 子进程边界必须收敛第三方库异常
        # 不传出带查询串或请求头的异常消息。
        conn.send({"error": type(error).__name__})
    finally:
        conn.close()


def ak_call(function: str, arguments: dict, timeout: float = 45) -> list[dict]:
    """Windows spawn 兼容的可终止抓取；最多一个采集进程，避免资源耗尽。"""
    with FETCH_LOCK:
        context = mp.get_context("spawn")
        receiving, sending = context.Pipe(duplex=False)
        process = context.Process(target=_ak_worker, args=(sending, function, arguments))
        process.start()
        sending.close()
        try:
            if not receiving.poll(timeout):
                raise ValueError("行情数据源请求超时")
            result = receiving.recv()
            if "error" in result:
                raise ValueError("行情数据源不可用：" + result["error"])
            return result["records"]
        finally:
            receiving.close()
            process.join(timeout=1)
            if process.is_alive():
                process.terminate()
                process.join(timeout=3)


def demo_records(symbol: str, start: date, end: date) -> list[dict]:
    """确定性合成样本，只供演示和测试；工作日不代表交易所日历。"""
    rng = np.random.default_rng(int(symbol))
    dates = pd.bdate_range("2021-01-01", end)
    close = 3 * np.exp(np.cumsum(rng.normal(0.0002, 0.012, len(dates))))
    rows = []
    for i, day in enumerate(dates):
        if day.date() < start:
            continue
        opening = close[max(i - 1, 0)] * (1 + rng.normal(0, 0.003))
        volume = int(rng.integers(2_000_000, 20_000_000))
        rows.append(
            {
                "date": str(day.date()),
                "open": round(opening, 3),
                "high": round(max(opening, close[i]) * 1.006, 3),
                "low": round(min(opening, close[i]) * 0.994, 3),
                "close": round(close[i], 3),
                "volume": volume,
                "amount": round(volume * close[i], 2),
            }
        )
    return rows


def clean_records(records: list[dict], request: FetchRequest, now: datetime | None = None) -> dict:
    """保留每条原始值，拒收无效 OHLC/重复日，报告缺失与异常跳变。"""
    fetched = now or datetime.now(UTC)
    is_em = request.provider == "EASTMONEY"
    is_tx = request.provider == "TENCENT"
    mapping = {
        "trade_date": "日期" if is_em else "date",
        "open": "开盘" if is_em else "open",
        "high": "最高" if is_em else "high",
        "low": "最低" if is_em else "low",
        "close": "收盘" if is_em else "close",
        "volume": "成交量" if is_em else "volume",
        "amount": "成交额" if is_em else "amount",
    }
    nature = {"DEMO": "DEMO", "CSV": "CSV_UNVERIFIED"}.get(request.provider, "REAL")
    rows, rejected, seen = [], [], set()
    for raw in records:
        try:
            day = date.fromisoformat(str(raw[mapping["trade_date"]])[:10])
            if day < request.start or day > request.end:
                continue
            if day in seen:
                raise ValueError("DUPLICATE_DATE")
            seen.add(day)
            numbers = {}
            for key in ("open", "high", "low", "close", "volume", "amount"):
                value = raw.get(mapping[key])
                numbers[key] = None if value is None or value == "" else Decimal(str(value))
            if is_em or is_tx:
                numbers["volume"] *= 100  # 两个接口的本研究池ETF成交量均为手，统一保存份。
            if is_tx and numbers["amount"] is not None:
                numbers["amount"] *= 10000  # 腾讯日线金额单位为万元，保留原始值和十进制精度。
            source = {"EASTMONEY": "EASTMONEY_HTTP", "TENCENT": "TENCENT_HTTP"}.get(
                request.provider, "AKSHARE_SINA"
            )
            bar = Bar(
                symbol=request.symbol,
                trade_date=day,
                **numbers,
                source=source if nature == "REAL" else request.provider,
                nature=nature,
                price_basis=request.price_basis,
                fetched_at=fetched,
                raw_values=raw,
            )
            validate_final_bar(bar)
            if bar.high < max(bar.open, bar.close) or bar.low > min(bar.open, bar.close):
                raise ValueError("INVALID_OHLC_RANGE")
            if bar.volume == 0:
                bar.quality_flags.append("ZERO_VOLUME_NO_FILL")
            if bar.amount is None:
                bar.quality_flags.append("AMOUNT_MISSING")
            rows.append(bar)
        except (ValueError, TypeError, KeyError, InvalidOperation) as error:
            reason = str(error)
            rejected.append(
                {
                    "raw": raw,
                    "reason": reason
                    if reason
                    in {
                        "DUPLICATE_DATE",
                        "INVALID_OHLC_RANGE",
                        "INCOMPLETE_SESSION",
                        "NOT_TRADING_SESSION",
                    }
                    else type(error).__name__,
                }
            )
    rows.sort(key=lambda row: row.trade_date)
    for previous, current in pairwise(rows):
        if abs(current.close / previous.close - 1) > Decimal("0.22"):
            current.quality_flags.append("LARGE_PRICE_JUMP_REVIEW_REQUIRED")
    for bar in rows:
        if bar.quality_flags:
            bar.quality_status = "WARNING"
    present = {row.trade_date for row in rows}
    missing = [
        str(day)
        for day in request.expected_dates
        if request.start <= day <= request.end and day not in present
    ]
    return {
        "bars": [bar.model_dump(mode="json") for bar in rows],
        "report": {
            "provider": request.provider,
            "nature": nature,
            "count": len(rows),
            "rejected_count": len(rejected),
            "rejected": rejected,
            "missing_dates": missing,
            "calendar_verified": bool(request.expected_dates),
            "volume_unit": "SHARE",
            "amount_unit": "CNY",
            "price_basis": request.price_basis,
            "fetched_at": fetched.isoformat(),
            "latest_complete_date": str(rows[-1].trade_date) if rows else None,
            "incomplete_session_rejected": sum(
                item["reason"] == "INCOMPLETE_SESSION" for item in rejected
            ),
            "adjustment_status": "RAW_PRICE_RETURN_NOT_TOTAL_RETURN"
            if request.price_basis == "RAW"
            else "PROVIDER_ADJUSTED_NOT_EXECUTION_PRICE",
            "cross_source_status": "NOT_COMPARED",
            "warning_count": sum(bool(bar.quality_flags) for bar in rows),
            "quality_version": "daily-quality-v1.1",
        },
    }


def fetch_bars(request: FetchRequest) -> dict:
    """按显式指定的数据源获取数据；错误向上暴露，不偷偷切为 Mock。"""
    if request.start > request.end:
        raise ValueError("开始日期不能晚于结束日期")
    if request.provider == "EASTMONEY":
        # 复用 AKShare 公开接口的字段协议，仅传必要查询字段。
        # 独立适配避免修改第三方包；免费源不稳定时显式报错，由用户切换来源。
        params = {
            "secid": ("1." if request.symbol.startswith("5") else "0.") + request.symbol,
            "klt": "101",
            "fqt": {"RAW": "0", "QFQ": "1", "HFQ": "2"}[request.price_basis],
            "beg": request.start.strftime("%Y%m%d"),
            "end": request.end.strftime("%Y%m%d"),
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f116",
        }
        with FETCH_LOCK, requests.Session() as session:
            session.trust_env = os.environ.get("ETF_MARKET_ENV_PROXY", "false").lower() == "true"
            try:
                response = session.get(
                    "https://push2his.eastmoney.com/api/qt/stock/kline/get",
                    params=params,
                    timeout=15,
                )
                response.raise_for_status()
                payload = response.json().get("data") or {}
            except (requests.RequestException, ValueError) as error:
                raise ValueError("东方财富公开日线接口暂不可用") from error
        columns = [
            "日期",
            "开盘",
            "收盘",
            "最高",
            "最低",
            "成交量",
            "成交额",
            "振幅",
            "涨跌幅",
            "涨跌额",
            "换手率",
        ]
        records = [
            dict(zip(columns, row.split(","), strict=True)) for row in payload.get("klines", [])
        ]
    elif request.provider == "TENCENT":
        # 仅用于已核实的三ETF未复权日线补偿，不把股票/指数的其他量纲套入当前研究池。
        if request.price_basis != "RAW" or request.symbol not in {"510300", "510500", "159915"}:
            raise ValueError("腾讯补偿仅支持已核实三ETF的未复权日线")
        symbol = ("sh" if request.symbol.startswith("5") else "sz") + request.symbol
        with FETCH_LOCK, requests.Session() as session:
            session.trust_env = os.environ.get("ETF_MARKET_ENV_PROXY", "false").lower() == "true"
            try:
                response = session.get(
                    "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get",
                    params={"param": f"{symbol},day,{request.start},{request.end},640,"},
                    timeout=15,
                )
                response.raise_for_status()
                payload = response.json()
                # 只接收明确的RAW day，禁止用qfqday/hfqday兜底，且必须匹配请求标的。
                if payload.get("code") != 0:
                    raise ValueError("腾讯行情请求被拒绝")
                daily = payload["data"][symbol]["day"]
                records = [
                    {
                        "date": row[0],
                        "open": row[1],
                        "close": row[2],
                        "high": row[3],
                        "low": row[4],
                        "volume": row[5],
                        "amount": row[8],
                    }
                    for row in daily
                ]
            except (
                requests.RequestException,
                ValueError,
                KeyError,
                IndexError,
                TypeError,
            ) as error:
                raise ValueError("腾讯公开未复权日线接口暂不可用") from error
    elif request.provider == "SINA":
        if request.price_basis != "RAW":
            raise ValueError("新浪 ETF 接口仅提供未复权口径")
        prefix = "sh" if request.symbol.startswith("5") else "sz"
        records = ak_call("fund_etf_hist_sina", {"symbol": prefix + request.symbol})
    elif request.provider == "CSV":
        if not request.csv_content:
            raise ValueError("CSV 内容不能为空")
        records = json.loads(pd.read_csv(StringIO(request.csv_content)).to_json(orient="records"))
    else:
        records = demo_records(request.symbol, request.start, request.end)
    result = clean_records(records, request)
    if not result["bars"]:
        raise ValueError("没有可入库的有效行情")
    return result


def fetch_calendar() -> dict:
    """历史由行情源提供，2026逐日以交易所公告校正；未来未公告年度不外推。"""
    official = official_calendar()
    status = "AVAILABLE"
    try:
        rows = ak_call("tool_trade_date_hist_sina", {})
    except ValueError:
        # 年度公告是独立真实来源，网络失败不影响其已冻结范围；失败状态明确返回。
        rows = []
        status = "HISTORICAL_SOURCE_UNAVAILABLE"
    historical = {
        str(row["trade_date"])[:10]
        for row in rows
        if str(row["trade_date"])[:10] < official["official_coverage_start"]
    }
    dates = sorted(historical | set(official["dates"]))
    return {
        **official,
        "dates": dates,
        "source": "AKSHARE_SINA_AND_SSE_OFFICIAL_2026",
        "source_status": status,
        "coverage_start": dates[0] if dates else None,
        "coverage_end": dates[-1] if dates else None,
    }
