"""所有指标只使用当前及历史观察值，不用向后填充或居中窗口。"""

import numpy as np
import pandas as pd

FEATURE_VERSION = "ohlcv-v2"
V1_FEATURES = [
    "return_1",
    "return_5",
    "return_20",
    "ma20_ratio",
    "ma60_ratio",
    "volume_change",
    "volatility20",
    "rsi14",
    "macd",
    "macd_signal",
    "momentum60",
    "relative_strength20",
]
FEATURES = V1_FEATURES + [
    "intraday_range",
    "close_location",
    "atr14_ratio",
    "trend20_60",
    "volume20_60",
    "volatility5",
    "volume_price_correlation20",
    "relative_strength5",
]
SUPPORTED_FEATURES = {"ohlcv-v1": V1_FEATURES, FEATURE_VERSION: FEATURES}


def feature_frame(
    bars: list[dict], benchmark: pd.Series | None = None, calendar: list | None = None
) -> pd.DataFrame:
    """只计算同一品种有序行情；相对强弱对齐历史可见的基准，不补未来值。"""
    frame = pd.DataFrame(bars).copy()
    frame["trade_date"] = pd.to_datetime(frame["trade_date"])
    frame = frame.sort_values("trade_date").set_index("trade_date")
    if frame.index.has_duplicates:
        raise ValueError("特征行情包含重复交易日")
    if calendar:
        dates = pd.DatetimeIndex(pd.to_datetime(calendar)).drop_duplicates().sort_values()
        dates = dates[(dates >= frame.index[0]) & (dates <= frame.index[-1])]
        frame = frame.reindex(dates)
        frame.index.name = "trade_date"
    for column in ("open", "high", "low", "close", "volume"):
        frame[column] = pd.to_numeric(frame[column])
    close = frame["close"]
    for window in (1, 5, 20):
        frame[f"return_{window}"] = close.pct_change(window, fill_method=None)
    frame["ma20"] = close.rolling(20).mean()
    frame["ma60"] = close.rolling(60).mean()
    frame["ma20_ratio"] = close / frame["ma20"] - 1
    frame["ma60_ratio"] = close / frame["ma60"] - 1
    frame["volume_change"] = frame["volume"] / frame["volume"].rolling(20).mean() - 1
    frame["volatility20"] = frame["return_1"].rolling(20).std() * np.sqrt(252)
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
    frame["rsi14"] = 100 - 100 / (1 + gain / loss.replace(0, np.nan))
    frame.loc[(loss == 0) & (gain > 0), "rsi14"] = 100
    frame.loc[(loss == 0) & (gain == 0), "rsi14"] = 50
    frame["macd"] = (
        close.ewm(span=12, adjust=False).mean() - close.ewm(span=26, adjust=False).mean()
    )
    frame["macd_signal"] = frame["macd"].ewm(span=9, adjust=False).mean()
    frame["momentum60"] = close.pct_change(60, fill_method=None)
    # 单 ETF 输入时以自身 60 日动量作为明确标注的相对时间强弱，跨 ETF 轮动另算排名。
    frame["relative_strength20"] = frame["return_20"] - (
        benchmark.reindex(frame.index).pct_change(20, fill_method=None)
        if benchmark is not None
        else close.pct_change(60, fill_method=None) / 3
    )
    # V1.1预先固定扩展集，保留旧特征列以读取旧冻结工件；不依据最终测试收益筛选。
    frame["intraday_range"] = (frame["high"] - frame["low"]) / close
    frame["close_location"] = (close - frame["low"]) / (frame["high"] - frame["low"]).replace(
        0, np.nan
    )
    frame.loc[frame["high"] == frame["low"], "close_location"] = 0.5
    true_range = pd.concat(
        [
            frame["high"] - frame["low"],
            (frame["high"] - close.shift(1)).abs(),
            (frame["low"] - close.shift(1)).abs(),
        ],
        axis=1,
    ).max(axis=1, skipna=False)
    frame["atr14_ratio"] = true_range.rolling(14).mean() / close
    frame["trend20_60"] = frame["ma20"] / frame["ma60"] - 1
    frame["volume20_60"] = (
        frame["volume"].rolling(20).mean() / (frame["volume"].rolling(60).mean().replace(0, np.nan))
        - 1
    )
    frame["volatility5"] = frame["return_1"].rolling(5).std() * np.sqrt(252)
    frame["volume_price_correlation20"] = (
        frame["return_1"].rolling(20).corr(frame["volume"].pct_change(fill_method=None))
    )
    frame["relative_strength5"] = frame["return_5"] - (
        benchmark.reindex(frame.index).pct_change(5, fill_method=None)
        if benchmark is not None
        else frame["return_20"] / 4
    )
    return frame.replace([np.inf, -np.inf], np.nan)


def indicator_rows(bars: list[dict]) -> list[dict]:
    """给管理台输出均线、波动率和指标，预热不足部分保持 null。"""
    frame = feature_frame(bars).reset_index()
    frame["trade_date"] = frame["trade_date"].dt.strftime("%Y-%m-%d")
    import json

    return json.loads(
        frame[
            ["trade_date", "ma20", "ma60", "rsi14", "macd", "macd_signal", "volatility20"]
        ].to_json(orient="records")
    )
