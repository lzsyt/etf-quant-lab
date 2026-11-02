"""日线假设成交模型：收盘信号在后续交易日执行，结算全程使用 Decimal。"""

import hashlib
from copy import deepcopy
from datetime import date
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from itertools import pairwise
from typing import Literal

import numpy as np
from pydantic import BaseModel, Field

from etf_quant.data import validate_final_bar
from etf_quant.features import feature_frame
from etf_quant.schemas import Bar

D = Decimal
ZERO = D("0")
CENT = D("0.01")
ENGINE_VERSION = "daily-decimal-v1.1.0"
EXECUTION_MODEL = "NEXT_OPEN_PREVIOUS_LIQUIDITY_ESTIMATE"


class RiskConfig(BaseModel):
    max_single_weight: Decimal = Field(default=D("0.20"), gt=0, le=1)
    max_total_weight: Decimal = Field(default=D("0.40"), gt=0, le=1)
    max_order_weight: Decimal = Field(default=D("0.05"), gt=0, le=1)
    max_daily_buy_weight: Decimal = Field(default=D("0.10"), gt=0, le=1)
    daily_loss_limit: Decimal = Field(default=D("0.02"), gt=0, le=1)
    drawdown_lock: Decimal = Field(default=D("0.10"), gt=0, le=1)
    commission_rate: Decimal = Field(default=D("0.0003"), ge=0, le=D("0.01"))
    minimum_commission: Decimal = Field(default=D("5.00"), ge=0)
    slippage_bps: Decimal = Field(default=D("5"), ge=0, le=1000)
    spread_bps: Decimal = Field(default=D("2"), ge=0, le=1000)
    max_volume_fraction: Decimal = Field(default=D("0.01"), gt=0, le=1)


class Instrument(BaseModel):
    symbol: str
    lot_size: int = Field(default=100, gt=0)
    settlement_days: Literal[0, 1] = 1
    limit_rate: Decimal = Field(default=D("0.10"), gt=0, le=1)
    tick_size: Decimal = Field(default=D("0.001"), gt=0)
    rule_valid_from: date = date(2021, 1, 1)


class CorporateAction(BaseModel):
    symbol: str
    ex_date: date
    pay_date: date
    cash_per_unit: Decimal = Field(default=ZERO, ge=0)
    split_factor: Decimal = Field(default=D("1"), gt=0)
    reference_price: Decimal | None = Field(default=None, gt=0)
    verified: bool = False
    source_url: str


class Signal(BaseModel):
    signal_date: date
    symbol: str
    target_weight: Decimal = Field(ge=0, le=1)


class BacktestRequest(BaseModel):
    strategy: Literal[
        "ma",
        "rsi",
        "macd",
        "momentum",
        "rotation",
        "lightgbm",
        "xgboost",
        "logistic",
        "llm",
        "fusion",
        "buy_hold",
        "cash",
    ] = "ma"
    bars: list[Bar] = Field(min_length=2, max_length=50000)
    instruments: list[Instrument]
    start: date
    end: date
    initial_capital: Decimal = Field(default=D("100000.00"), gt=0)
    risk: RiskConfig = Field(default_factory=RiskConfig)
    signals: list[Signal] = Field(default_factory=list)
    corporate_actions: list[CorporateAction] = Field(default_factory=list)
    trading_dates: list[date] = Field(default_factory=list)
    evaluation_type: Literal["HISTORICAL_RULE", "HISTORICAL_OOS", "DEMO"] = "HISTORICAL_RULE"
    news_point_in_time_verified: bool = False


class StepRequest(BaseModel):
    account_id: str
    strategy: str
    state: dict
    trade_date: date
    previous_trade_date: date
    bars: list[Bar]
    previous_bars: list[Bar]
    instruments: list[Instrument]
    signals: list[Signal]
    risk: RiskConfig = Field(default_factory=RiskConfig)
    corporate_actions: list[CorporateAction] = Field(default_factory=list)


def money(value):
    """结算精确到分，统一四舍五入，禁止二进制浮点参与现金账。"""
    return D(str(value)).quantize(CENT, rounding=ROUND_HALF_UP)


def initial_state(capital):
    """独立策略账户只包含虚拟资金及可恢复的持仓批次。"""
    amount = str(money(capital))
    return {
        "cash": amount,
        "initial_capital": amount,
        "equity": amount,
        "peak_equity": amount,
        "locked": False,
        "lots": {},
        "receivables": [],
        "last_prices": {},
        "last_trade_date": None,
        "cumulative_fees": "0.00",
        "cumulative_dividends": "0.00",
    }


def quantities(state):
    """从持仓批次计算份额，保留取得日期以校验 T+1。"""
    return {
        symbol: sum((D(lot["quantity"]) for lot in lots), ZERO)
        for symbol, lots in state["lots"].items()
    }


def valuation(state, prices):
    """权益包括应收分红；缺失价格仅作陈旧估值，调用方会禁止当日下单。"""
    positions = sum(
        (
            quantity * prices.get(symbol, D(state["last_prices"].get(symbol, "0")))
            for symbol, quantity in quantities(state).items()
        ),
        ZERO,
    )
    receivables = sum((D(item["amount"]) for item in state["receivables"]), ZERO)
    return money(D(state["cash"]) + positions + receivables), money(positions)


def daily_step(request: StepRequest) -> dict:
    """回测和上线模拟共用单日账本转移；重试同一天只返回已处理标记。"""
    state = deepcopy(request.state)
    day = request.trade_date
    if state.get("last_trade_date") and day <= date.fromisoformat(state["last_trade_date"]):
        return {
            "state": state,
            "orders": [],
            "fills": [],
            "risk_events": [],
            "already_processed": True,
        }
    if request.previous_trade_date >= day:
        raise ValueError("前一交易日必须早于成交日")
    if any(signal.signal_date != request.previous_trade_date for signal in request.signals):
        raise ValueError("禁止使用当日收盘信号或过期预测成交")
    if any(bar.price_basis != "RAW" for bar in request.bars):
        raise ValueError("成交必须使用未复权原始价格")
    if any(bar.trade_date != day for bar in request.bars):
        raise ValueError("成交行情日期不匹配")
    if any(bar.trade_date != request.previous_trade_date for bar in request.previous_bars):
        raise ValueError("前一交易日行情日期不匹配")
    for bar in request.bars + request.previous_bars:
        validate_final_bar(bar)
    if len({signal.symbol for signal in request.signals}) != len(request.signals):
        raise ValueError("同一品种不能出现重复信号")
    if any(
        len({bar.symbol for bar in bars}) != len(bars)
        for bars in (request.bars, request.previous_bars)
    ):
        raise ValueError("同一成交日不能出现重复行情")
    bars = {bar.symbol: bar for bar in request.bars}
    previous = {bar.symbol: bar for bar in request.previous_bars}
    instruments = {item.symbol: item for item in request.instruments}
    orders, fills, risks = [], [], []
    risk = request.risk
    prior_equity = D(state["equity"])
    actions = {
        action.symbol: action for action in request.corporate_actions if action.ex_date == day
    }
    for action in actions.values():
        if not action.verified or action.pay_date < action.ex_date:
            raise ValueError("分红拆分记录必须有已核实来源和有效到账日")
        held = quantities(state).get(action.symbol, ZERO)
        if held:
            amount = money(held * action.cash_per_unit)
            if amount:
                state["receivables"].append(
                    {"amount": str(amount), "pay_date": str(action.pay_date)}
                )
            for lot in state["lots"].get(action.symbol, []):
                lot["quantity"] = str(D(lot["quantity"]) * action.split_factor)
    unpaid = []
    for receivable in state["receivables"]:
        if date.fromisoformat(receivable["pay_date"]) <= day:
            state["cash"] = str(money(D(state["cash"]) + D(receivable["amount"])))
            state["cumulative_dividends"] = str(
                money(D(state["cumulative_dividends"]) + D(receivable["amount"]))
            )
        else:
            unpaid.append(receivable)
    state["receivables"] = unpaid
    opens = {symbol: bar.open for symbol, bar in bars.items()}
    opening_equity, opening_position_value = valuation(state, opens)
    if opening_equity <= D(state["peak_equity"]) * (1 - risk.drawdown_lock):
        state["locked"] = True
        risks.append({"rule": "DRAWDOWN_LOCK", "trade_date": str(day)})
    daily_loss = opening_equity <= prior_equity * (1 - risk.daily_loss_limit)
    missing = (set(instruments) - set(bars)) | (set(instruments) - set(previous))
    if missing:
        risks.append(
            {"rule": "MISSING_MARKET_NO_ORDERS", "symbols": sorted(missing), "trade_date": str(day)}
        )
    targets = {signal.symbol: signal.target_weight for signal in request.signals}
    if request.strategy == "buy_hold" and targets and not missing:
        # 基准仅在最初5个可执行交易日按同样风控建仓，此后保持份额，不日常再平衡。
        sessions = int(state.get("buy_hold_entry_sessions", 0))
        if sessions >= 5:
            targets = {}
        else:
            state["buy_hold_entry_sessions"] = sessions + 1
    bought_today = ZERO
    # 先卖再买，顺序固定，确保同样输入的现金约束与成交结果可复现。
    desired = []
    for symbol, target in sorted(targets.items()):
        if symbol not in bars or symbol not in instruments:
            continue
        current = quantities(state).get(symbol, ZERO) * bars[symbol].open
        desired.append((target * opening_equity - current, symbol, target))
    desired.sort(key=lambda item: (item[0] >= 0, item[1]))
    for delta, symbol, target in desired:
        fractional_exit = target == 0 and quantities(state).get(symbol, ZERO) > 0
        if missing or (
            abs(delta) < bars[symbol].open * instruments[symbol].lot_size and not fractional_exit
        ):
            continue
        instrument, bar = instruments[symbol], bars[symbol]
        side = "BUY" if delta > 0 else "SELL"
        if request.strategy == "buy_hold" and side == "SELL":
            continue
        key = f"{request.account_id}:{day}:{symbol}:{side}"
        order = {
            "business_key": key,
            "trade_date": str(day),
            "signal_date": str(request.previous_trade_date),
            "symbol": symbol,
            "side": side,
            "status": "REJECTED",
            "reason": None,
            "execution_model": EXECUTION_MODEL,
        }
        orders.append(order)
        reason = None
        if day < instrument.rule_valid_from:
            reason = "TRADING_RULE_NOT_VERIFIED_FOR_DATE"
        elif bar.volume <= 0:
            # 来源报告零量不等于已核实停牌；保守不成交，真实状态等待交叉核验。
            reason = "SOURCE_ZERO_VOLUME_UNVERIFIED"
        elif "LARGE_PRICE_JUMP_REVIEW_REQUIRED" in previous[symbol].quality_flags:
            reason = "ABNORMAL_PRICE"
        elif side == "BUY" and state["locked"]:
            reason = "DRAWDOWN_LOCK"
        elif side == "BUY" and daily_loss:
            reason = "DAILY_LOSS_LIMIT"
        elif symbol in actions and actions[symbol].reference_price is None:
            reason = "EX_DATE_REFERENCE_PRICE_MISSING"
        reference = (
            actions[symbol].reference_price
            if symbol in actions
            else (previous[symbol].close if symbol in previous else None)
        )
        if reference is None:
            reason = reason or "PREVIOUS_PRICE_MISSING"
        else:
            upper = (reference * (1 + instrument.limit_rate)).quantize(
                instrument.tick_size, rounding=ROUND_HALF_UP
            )
            lower = (reference * (1 - instrument.limit_rate)).quantize(
                instrument.tick_size, rounding=ROUND_HALF_UP
            )
            if (side == "BUY" and bar.open >= upper) or (side == "SELL" and bar.open <= lower):
                reason = reason or "PRICE_LIMIT_NO_FILL"
        adjustment = (risk.slippage_bps + risk.spread_bps / 2) / D("10000")
        price = (bar.open * (1 + adjustment if side == "BUY" else 1 - adjustment)).quantize(
            instrument.tick_size, rounding=ROUND_HALF_UP
        )
        # 不能用当天收盘后才知道的最高/最低价筛选开盘成交。
        # 价格含明确的假设滑点成本，不代表观察到真实盘口成交。
        if reason:
            order["reason"] = reason
            risks.append({"rule": reason, "symbol": symbol, "trade_date": str(day)})
            continue
        volume_cap = (
            previous[symbol].volume * risk.max_volume_fraction / instrument.lot_size
        ).to_integral_value(rounding=ROUND_DOWN) * instrument.lot_size
        if side == "BUY":
            equity, held_value = valuation(state, opens)
            held_symbol = quantities(state).get(symbol, ZERO) * bar.open
            budget = min(
                delta,
                opening_equity * risk.max_order_weight,
                opening_equity * risk.max_daily_buy_weight - bought_today,
                equity * risk.max_single_weight - held_symbol,
                equity * risk.max_total_weight - held_value,
                D(state["cash"]),
            )
            quantity = min(
                volume_cap,
                max(ZERO, budget / price).to_integral_value(rounding=ROUND_DOWN)
                // instrument.lot_size
                * instrument.lot_size,
            )
            while quantity > 0:
                gross = money(quantity * price)
                fee = money(max(gross * risk.commission_rate, risk.minimum_commission))
                if gross + fee <= budget:
                    break
                quantity -= instrument.lot_size
            if quantity <= 0:
                order["reason"] = "POSITION_CASH_DAILY_BUY_OR_VOLUME_LIMIT"
                continue
            state["cash"] = str(money(D(state["cash"]) - gross - fee))
            state["lots"].setdefault(symbol, []).append(
                {"quantity": str(quantity), "cost": str(gross + fee), "acquired": str(day)}
            )
            bought_today += gross + fee
            realized = None
        else:
            lots = state["lots"].get(symbol, [])
            available = sum(
                (
                    D(lot["quantity"])
                    for lot in lots
                    if instrument.settlement_days == 0 or date.fromisoformat(lot["acquired"]) < day
                ),
                ZERO,
            )
            wanted = (-delta / price).to_integral_value(rounding=ROUND_DOWN)
            quantity = min(
                volume_cap, available, wanted // instrument.lot_size * instrument.lot_size
            )
            # 完全退出允许一次性卖出拆分产生的不足一手余额。
            if target == 0 and available <= volume_cap:
                quantity = available
            if quantity <= 0:
                order["reason"] = "T_PLUS_ONE_OR_VOLUME_LIMIT"
                continue
            gross = money(quantity * price)
            fee = money(max(gross * risk.commission_rate, risk.minimum_commission))
            if D(state["cash"]) + gross < fee:
                order["reason"] = "FEE_EXCEEDS_CASH"
                continue
            remaining, cost = quantity, ZERO
            for lot in lots:
                if instrument.settlement_days and date.fromisoformat(lot["acquired"]) >= day:
                    continue
                take = min(remaining, D(lot["quantity"]))
                if take <= 0:
                    continue
                allocated = money(D(lot["cost"]) * take / D(lot["quantity"]))
                lot["quantity"] = str(D(lot["quantity"]) - take)
                lot["cost"] = str(money(D(lot["cost"]) - allocated))
                remaining -= take
                cost += allocated
            state["lots"][symbol] = [lot for lot in lots if D(lot["quantity"]) > 0]
            state["cash"] = str(money(D(state["cash"]) + gross - fee))
            realized = str(money(gross - fee - cost))
        order.update(status="FILLED", quantity=str(quantity), price=str(price), fee=str(fee))
        fills.append(
            {
                **order,
                "gross": str(gross),
                "realized_pnl": realized,
                "reference_open": str(bar.open),
                "liquidity_reference_date": str(request.previous_trade_date),
                "slippage_cost": str(money(abs(price - bar.open) * quantity)),
                "spread_cost": str(money(bar.open * quantity * risk.spread_bps / D("20000"))),
            }
        )
        state["cumulative_fees"] = str(money(D(state["cumulative_fees"]) + fee))
    closes = {symbol: bar.close for symbol, bar in bars.items()}
    state["last_prices"].update({symbol: str(price) for symbol, price in closes.items()})
    equity, position_value = valuation(state, closes)
    peak = max(D(state["peak_equity"]), equity)
    if equity <= peak * (1 - risk.drawdown_lock):
        state["locked"] = True
    state.update(equity=str(equity), peak_equity=str(peak), last_trade_date=str(day))
    return {
        "state": state,
        "orders": orders,
        "fills": fills,
        "risk_events": risks,
        "already_processed": False,
        "snapshot": {
            "trade_date": str(day),
            "equity": str(equity),
            "cash": state["cash"],
            "position_value": str(position_value),
            "opening_position_value": str(opening_position_value),
            "daily_pnl": str(money(equity - prior_equity)),
            "drawdown": float(1 - equity / peak),
            "stale_valuation": bool(missing),
        },
    }


def strategy_signals(request: BacktestRequest) -> dict:
    """规则信号仅使用当日收盘及历史值，机器学习仅接受外部冻结的样本外概率信号。"""
    if request.strategy in ("lightgbm", "xgboost", "logistic", "llm", "fusion"):
        if request.strategy in ("llm", "fusion") and not request.news_point_in_time_verified:
            raise ValueError("缺少当时可得的历史新闻证据，只允许前瞻模拟，不进行LLM历史回测")
        if (
            request.strategy in ("lightgbm", "xgboost", "logistic")
            and request.evaluation_type != "HISTORICAL_OOS"
        ):
            raise ValueError("机器学习历史回测必须使用独立样本外预测")
        if not request.signals:
            raise ValueError("没有可用的冻结策略信号")
        result = {}
        for signal in request.signals:
            result.setdefault(signal.signal_date, []).append(signal)
        return result
    grouped = {instrument.symbol: [] for instrument in request.instruments}
    for bar in request.bars:
        grouped[bar.symbol].append(bar.model_dump(mode="json"))
    frames = {
        symbol: feature_frame(bars, calendar=request.trading_dates)
        for symbol, bars in grouped.items()
        if bars
    }
    days = sorted({day.date() for frame in frames.values() for day in frame.index})
    results = {}
    rsi_holding = {symbol: False for symbol in frames}
    for day in days:
        scores = {}
        for symbol, frame in frames.items():
            matching = frame.loc[frame.index.date == day]
            if matching.empty:
                continue
            row = matching.iloc[0]
            score = 0.0
            if request.strategy == "buy_hold":
                score = 1.0
            elif request.strategy == "ma" and row.ma20 > row.ma60:
                score = float(row.return_20) if row.return_20 > 0 else 0.001
            elif request.strategy == "macd" and row.macd > row.macd_signal:
                score = 1.0
            elif request.strategy in ("momentum", "rotation") and row.return_20 > 0:
                score = float(row.return_20)
            elif request.strategy == "rsi":
                if row.rsi14 < 30:
                    rsi_holding[symbol] = True
                elif row.rsi14 > 70:
                    rsi_holding[symbol] = False
                score = 1.0 if rsi_holding[symbol] else 0.0
            scores[symbol] = score
        winners = [
            symbol
            for symbol, score in sorted(scores.items(), key=lambda x: (-x[1], x[0]))
            if score > 0
        ]
        remaining = request.risk.max_total_weight
        weights = {}
        for symbol in winners:
            weights[symbol] = (
                min(remaining, request.risk.max_single_weight)
                if request.strategy == "rotation"
                else min(
                    request.risk.max_total_weight / len(winners), request.risk.max_single_weight
                )
            )
            remaining -= weights[symbol]
        results[day] = [
            Signal(signal_date=day, symbol=symbol, target_weight=weights.get(symbol, ZERO))
            for symbol in sorted(frames)
        ]
    return results


def performance(curve, fills, initial):
    """基于真实权益和成交统计；卖出净损益含买卖费用，未平仓不计胜率。

    持仓日按开盘或收盘有仓计数，利用率为逐日收盘市值/权益均值。
    一笔卖出成交（含部分平仓）为一次已实现交易；不把买入算成亏损样本。
    年化采用252个交易日，最大回撤包含初始本金，避免漏掉第一笔费用。
    """
    if not curve:
        raise ValueError("绩效计算需要至少一个真实权益快照")
    values = np.array([float(initial)] + [float(row["equity"]) for row in curve])
    if not np.isfinite(values).all() or values[0] <= 0 or np.any(values < 0):
        raise ValueError("权益必须有限且非负，初始本金必须大于零")
    if any(previous == 0 and current > 0 for previous, current in pairwise(values)):
        raise ValueError("零权益后增加资金属于现金流，不能直接当作策略收益")
    returns = np.divide(
        values[1:] - values[:-1], values[:-1], out=np.zeros(len(curve)), where=values[:-1] > 0
    )
    total = float(values[-1] / values[0] - 1)
    volatility = float(np.std(returns, ddof=1)) if len(returns) > 1 else 0.0
    downside = float(np.sqrt(np.mean(np.minimum(returns, 0) ** 2))) if len(returns) else 0.0
    pnl = [float(fill["realized_pnl"]) for fill in fills if fill["realized_pnl"] is not None]
    wins, losses = [v for v in pnl if v > 0], [v for v in pnl if v < 0]
    fees = sum((D(fill["fee"]) for fill in fills), ZERO)
    holding_sessions = sum(
        D(str(row.get("position_value", "0"))) > 0
        or D(
            str(
                row.get(
                    "opening_position_value",
                    curve[index - 1].get("position_value", "0") if index else "0",
                )
            )
        )
        > 0
        for index, row in enumerate(curve)
    )
    utilization_known = all("position_value" in row for row in curve)
    utilization = (
        float(
            np.mean(
                [
                    float(D(str(row["position_value"])) / D(str(row["equity"])))
                    if D(str(row["equity"])) > 0
                    else 0.0
                    for row in curve
                ]
            )
        )
        if utilization_known
        else None
    )
    consecutive, maximum = 0, 0
    for value in pnl:
        consecutive = consecutive + 1 if value < 0 else 0
        maximum = max(maximum, consecutive)
    holding_ratio = holding_sessions / len(curve) if utilization_known else None
    activity = (
        "NO_TRADES"
        if not fills
        else "LOW_ACTIVITY"
        if len(fills) < 5 or (holding_ratio is not None and holding_ratio < 0.1)
        else "ACTIVE"
    )
    slippage_known = all("slippage_cost" in fill for fill in fills)
    spread_known = all("spread_cost" in fill for fill in fills)
    return {
        "initial_capital": str(money(initial)),
        "final_equity": curve[-1]["equity"],
        "total_return": total,
        "annualized_return": float((1 + total) ** (252 / len(returns)) - 1) if len(returns) else 0,
        "annualized_volatility": volatility * np.sqrt(252),
        "sharpe": float(np.mean(returns) / volatility * np.sqrt(252)) if volatility else None,
        "sortino": float(np.mean(returns) / downside * np.sqrt(252)) if downside else None,
        "max_drawdown": float(np.max(1 - values / np.maximum.accumulate(values))),
        "trade_count": len(fills),
        "closed_trades": len(pnl),
        "win_rate": len(wins) / len(pnl) if pnl else None,
        "profit_loss_ratio": float(np.mean(wins) / -np.mean(losses)) if wins and losses else None,
        "avg_win_amount": str(money(np.mean(wins))) if wins else None,
        "avg_loss_amount": str(money(np.mean(losses))) if losses else None,
        "turnover": float(sum(D(fill["gross"]) for fill in fills) / D(str(np.mean(values)))),
        "fees": str(money(fees)),
        "fee_return_impact": float(fees / D(str(initial))),
        "slippage_cost": str(money(sum((D(fill["slippage_cost"]) for fill in fills), ZERO)))
        if slippage_known
        else None,
        "spread_cost": str(money(sum((D(fill["spread_cost"]) for fill in fills), ZERO)))
        if spread_known
        else None,
        "capital_utilization": utilization,
        "holding_sessions": holding_sessions if utilization_known else None,
        "closing_holding_sessions": sum(D(str(row["position_value"])) > 0 for row in curve)
        if utilization_known
        else None,
        "holding_ratio": holding_ratio,
        "max_consecutive_losses": maximum if pnl else None,
        "activity_status": activity,
        "activity_note": {
            "NO_TRADES": "没有实际成交；收益与回撤不能用于宣称模型风控有效",
            "LOW_ACTIVITY": "成交少于5笔或持仓日占比不足10%；低回撤可能主要来自低资金暴露",
            "ACTIVE": "已有实际成交；收益仍须结合持仓、资金利用率和样本量判断",
        }[activity],
        "win_rate_basis": "REALIZED_SELL_FILLS_NET_OF_ALLOCATED_BUY_AND_SELL_FEES",
        "turnover_basis": "TWO_SIDED_GROSS_NOTIONAL_DIVIDED_BY_MEAN_EQUITY",
        "capital_utilization_basis": "MEAN_DAILY_CLOSING_POSITION_VALUE_OVER_EQUITY",
        "excess_return_vs_buy_hold": None,
        "sessions": len(returns),
        "risk_free_rate": 0,
    }


def run_backtest(request: BacktestRequest, _include_benchmark=True) -> dict:
    """按完整交易日历推进，不回填缺失日，记录所有成交假设与数据限制。"""
    if request.start > request.end:
        raise ValueError("回测起止日期错误")
    if len({bar.nature for bar in request.bars}) != 1 or any(
        bar.price_basis != "RAW" for bar in request.bars
    ):
        raise ValueError("回测只允许同一数据性质的原始可成交价格")
    if request.bars[0].nature == "REAL" and not request.trading_dates:
        raise ValueError("真实行情回测必须提供可信交易日历，不能按现存K线跳过缺失日")
    for bar in request.bars:
        validate_final_bar(bar)
        # 数据资格必须先于策略和订单判断：不能只在策略想交易时，利用收盘后已知的零量删单。
        # 当前没有已核验停牌字段，价平零量也不能证明停牌；交易区间内任一ETF零量即拒绝整个实验。
        # 区间外历史量只在下一交易日作为当时已知的流动性输入，不适用当日事后择单的问题。
        if request.start <= bar.trade_date <= request.end and bar.volume == ZERO:
            raise ValueError(
                f"SOURCE_ZERO_VOLUME_UNVERIFIED: {bar.symbol} {bar.trade_date} "
                "回测区间存在未核验零成交量，须先交叉核验数据，不能生成策略收益"
            )
    if any(not action.verified for action in request.corporate_actions):
        raise ValueError("未核实分红不能计入收益")
    days = sorted(set(request.trading_dates or [bar.trade_date for bar in request.bars]))
    daily = {}
    for bar in request.bars:
        if bar.symbol in daily.setdefault(bar.trade_date, {}):
            raise ValueError("行情存在重复品种日期")
        daily[bar.trade_date][bar.symbol] = bar
    signals = strategy_signals(request)
    state = initial_state(request.initial_capital)
    curve, orders, fills, risk_events = [], [], [], []
    for index, day in enumerate(days):
        if not request.start <= day <= request.end or index == 0:
            continue
        previous = days[index - 1]
        result = daily_step(
            StepRequest(
                account_id=request.strategy,
                strategy=request.strategy,
                state=state,
                trade_date=day,
                previous_trade_date=previous,
                bars=list(daily.get(day, {}).values()),
                previous_bars=list(daily.get(previous, {}).values()),
                instruments=request.instruments,
                signals=signals.get(previous, []),
                risk=request.risk,
                corporate_actions=request.corporate_actions,
            )
        )
        state = result["state"]
        curve.append(result["snapshot"])
        orders.extend(result["orders"])
        fills.extend(result["fills"])
        risk_events.extend(result["risk_events"])
    if not curve:
        raise ValueError("回测区间没有可处理的后续交易日")
    digest = hashlib.sha256(request.model_dump_json().encode()).hexdigest()
    result = {
        "engine_version": ENGINE_VERSION,
        "strategy": request.strategy,
        "state": state,
        "metrics": performance(curve, fills, request.initial_capital),
        "curve": curve,
        "orders": orders,
        "fills": fills,
        "risk_events": risk_events,
        "input_sha256": digest,
        "nature": request.bars[0].nature,
        "evaluation_type": request.evaluation_type if request.bars[0].nature == "REAL" else "DEMO",
        "execution_model": EXECUTION_MODEL,
        "dividend_basis": "VERIFIED_ACTIONS_ONLY"
        if request.corporate_actions
        else "PRICE_RETURN_ONLY",
        "assumptions": [
            "收盘信号最早下一交易日开盘估计成交，并非真实盘中成交",
            "使用上一交易日成交量限制参与比例；当日高低价及全日成交量不参与开盘定价和数量决策",
            "交易区间任一ETF存在未核验零量则整体拒绝回测，不按策略信号选择性删单；开盘成交不是盘口证明",
            "每日仅在估计成交点及收盘核验风险，不能模拟盘中实时止损",
            "固定当前存续ETF研究池存在选择偏差；原始行情不代表点时完整数据版本",
        ],
    }
    if _include_benchmark:
        benchmark = (
            result
            if request.strategy == "buy_hold"
            else run_backtest(
                request.model_copy(update={"strategy": "buy_hold", "signals": []}), False
            )
        )
        result["metrics"]["excess_return_vs_buy_hold"] = (
            result["metrics"]["total_return"] - benchmark["metrics"]["total_return"]
        )
        result["benchmark"] = {
            "strategy": "buy_hold",
            "definition": "同区间、初始资金、费用及风险约束；前5个有效交易日建仓后持有，不是全仓买入持有",
            "total_return": benchmark["metrics"]["total_return"],
            "annualized_return": benchmark["metrics"]["annualized_return"],
            "max_drawdown": benchmark["metrics"]["max_drawdown"],
            "risk": request.risk.model_dump(mode="json"),
            "curve": benchmark["curve"],
        }
    return result
