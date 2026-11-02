"""新实验开盘净额调仓：先冻结数量及资金上限，再按固定日线规则记账。"""

import hashlib
import json
from copy import deepcopy
from datetime import date, datetime, time
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal
from zoneinfo import ZoneInfo

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from etf_quant.backtesting import (
    CorporateAction,
    Instrument,
    RiskConfig,
    money,
    quantities,
    valuation,
)
from etf_quant.data import validate_final_bar
from etf_quant.schemas import Bar

D = Decimal
ZERO = D("0")
MARKET_ZONE = ZoneInfo("Asia/Shanghai")
PROTOCOL_VERSION = "OPEN_NET_REBALANCE_V1"
COST_VERSION = "SIM_COST_V1"
EXECUTION_MODEL = "FROZEN_QUANTITY_NEXT_OPEN_DAILY_ESTIMATE"


class ForwardTarget(BaseModel):
    """缺失目标显式为空，不能被解释成清仓指令。"""

    model_config = ConfigDict(extra="forbid")
    symbol: str
    target_weight: Decimal | None = Field(default=None, ge=0, le=1)


class ForwardFreezeRequest(BaseModel):
    """冻结输入只包含生成时刻已可知的数据，不接收执行日行情。"""

    model_config = ConfigDict(extra="forbid")
    account_id: str
    experiment_id: str
    strategy_version: str
    signal_date: date
    entry_date: date
    evaluation_date: date
    generated_at: AwareDatetime
    state: dict
    previous_bars: list[Bar]
    instruments: list[Instrument]
    targets: list[ForwardTarget]
    trading_dates: list[date]
    risk: RiskConfig = Field(default_factory=RiskConfig)
    nature: Literal["REAL", "TEST", "HISTORICAL"] = "REAL"
    protocol_version: Literal["OPEN_NET_REBALANCE_V1"] = PROTOCOL_VERSION
    cost_version: Literal["SIM_COST_V1"] = COST_VERSION
    corporate_actions: list[CorporateAction] = Field(default_factory=list)
    corporate_action_coverage: Literal["UNKNOWN", "VERIFIED"] = "UNKNOWN"
    trace_id: str | None = None


class ForwardStepRequest(BaseModel):
    """执行端只能消费已冻结意图，并单独记录实际记账时刻。"""

    model_config = ConfigDict(extra="forbid")
    plan: dict
    state: dict
    bars: list[Bar]
    booked_at: AwareDatetime
    corporate_actions: list[CorporateAction] = Field(default_factory=list)
    suspended_symbols: list[str] = Field(default_factory=list)


class ForwardValuationRequest(BaseModel):
    """缺少冻结意图时仍估值和执行既有硬风控，不能补造过去订单。"""

    model_config = ConfigDict(extra="forbid")
    state: dict
    bars: list[Bar]
    booked_at: AwareDatetime
    trade_date: date
    instruments: list[Instrument]
    risk: RiskConfig = Field(default_factory=RiskConfig)
    nature: Literal["REAL", "TEST", "HISTORICAL"] = "REAL"
    corporate_actions: list[CorporateAction] = Field(default_factory=list)
    corporate_action_coverage: Literal["UNKNOWN", "VERIFIED"] = "UNKNOWN"


def _digest(value: dict) -> str:
    """规范化摘要用于检测意图和待结算账户是否被改变，持久不可变由数据库保证。"""
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(encoded.encode()).hexdigest()


def _market_open(day: date) -> datetime:
    """交易时刻固定为上海开盘09:30，跨端保存带时区时间。"""
    return datetime.combine(day, time(9, 30), MARKET_ZONE)


def _lot_floor(quantity: Decimal, lot_size: int) -> Decimal:
    """按品种申报单位向下取整，不能为制造成交扩充数量。"""
    return max(ZERO, quantity // lot_size * lot_size)


def _fee(gross: Decimal, risk: RiskConfig) -> Decimal:
    """双边佣金使用同一个冻结费率和最低佣金假设。"""
    return money(max(gross * risk.commission_rate, risk.minimum_commission))


def _price(reference: Decimal, side: str, instrument: Instrument, risk: RiskConfig) -> Decimal:
    """只由开盘价和预先固定滑点价差估计成交价，不参考日内价格范围。"""
    adjustment = (risk.slippage_bps + risk.spread_bps / 2) / D("10000")
    value = reference * (1 + adjustment if side == "BUY" else 1 - adjustment)
    ticks = (value / instrument.tick_size).quantize(D("1"), rounding=ROUND_HALF_UP)
    return ticks * instrument.tick_size


def _affordable(quantity: Decimal, price: Decimal, budget: Decimal, instrument, risk) -> Decimal:
    """预定缩量规则只可减小冻结份额，并保证含费不超过资金上限。"""
    quantity = min(quantity, _lot_floor(max(ZERO, budget) / price, instrument.lot_size))
    while quantity > 0 and money(quantity * price) + _fee(money(quantity * price), risk) > budget:
        quantity -= instrument.lot_size
    return max(ZERO, quantity)


def _check_dates(request: ForwardFreezeRequest):
    """从已确认日历取得连续两期开盘，晚生成的预测绝不回到过去成交。"""
    days = sorted(set(request.trading_dates))
    if request.signal_date not in days:
        raise ValueError("CONFIRMED_CALENDAR_MISSING_SIGNAL_DATE")
    position = days.index(request.signal_date)
    if len(days) <= position + 2 or days[position + 1 : position + 3] != [
        request.entry_date,
        request.evaluation_date,
    ]:
        raise ValueError("ENTRY_EVALUATION_NOT_CONSECUTIVE_CONFIRMED_SESSIONS")
    signal_close = datetime.combine(request.signal_date, time(15, 15), MARKET_ZONE)
    if request.generated_at < signal_close:
        raise ValueError("SIGNAL_SESSION_NOT_COMPLETE")
    if request.generated_at >= _market_open(request.entry_date):
        raise ValueError("LATE_PREDICTION_CANNOT_ENTER_PAST_OPEN")


def freeze_orders(request: ForwardFreezeRequest) -> dict:
    """按信号日已知权益、现金、价格和容量冻结净额订单，保留无目标持仓。"""
    _check_dates(request)
    bars = {bar.symbol: bar for bar in request.previous_bars}
    instruments = {item.symbol: item for item in request.instruments}
    targets = {target.symbol: target.target_weight for target in request.targets}
    if len(bars) != len(request.previous_bars) or len(instruments) != len(request.instruments):
        raise ValueError("DUPLICATE_BAR_OR_INSTRUMENT")
    if len(targets) != len(request.targets) or set(targets) - set(instruments):
        raise ValueError("DUPLICATE_OR_UNKNOWN_TARGET_SYMBOL")
    if set(bars) != set(instruments):
        raise ValueError("INCOMPLETE_FREEZE_MARKET")
    for bar in bars.values():
        if bar.trade_date != request.signal_date or bar.price_basis != "RAW":
            raise ValueError("FREEZE_REQUIRES_SIGNAL_DATE_RAW_BARS")
        if request.nature == "REAL" and bar.nature != "REAL":
            raise ValueError("TEST_DATA_CANNOT_ENTER_REAL_EXPERIMENT")
        validate_final_bar(bar)
        if bar.fetched_at > request.generated_at:
            raise ValueError("FREEZE_MARKET_NOT_AVAILABLE_AT_GENERATION")
    risk, state = request.risk, request.state
    prices = {symbol: bar.close for symbol, bar in bars.items()}
    equity, position_value = valuation(state, prices)
    if equity <= 0:
        raise ValueError("NONPOSITIVE_FREEZE_EQUITY")
    held = quantities(state)
    # 模型缺失标的继续保留已有持仓，其资金先占用组合上限，不为其他买入腾空。
    retained_weight = sum(
        (
            held.get(symbol, ZERO) * prices[symbol] / equity
            for symbol in instruments
            if targets.get(symbol) is None
        ),
        ZERO,
    )
    known_weight = sum((value for value in targets.values() if value is not None), ZERO)
    if any(value is not None and value > risk.max_single_weight for value in targets.values()):
        raise ValueError("TARGET_EXCEEDS_SINGLE_WEIGHT")
    if known_weight + retained_weight > risk.max_total_weight + D("0.00000001"):
        # 被动价格上涨可能使保留持仓已超过上限，仍允许明确减仓和无目标保持。
        new_buys = any(
            value is not None and value * equity > held.get(symbol, ZERO) * prices[symbol]
            for symbol, value in targets.items()
        )
        if new_buys:
            raise ValueError("TARGET_EXCEEDS_TOTAL_WEIGHT_WITH_RETAINED_POSITIONS")
    available_cash = max(ZERO, D(state["cash"]))
    daily_budget = min(available_cash, equity * risk.max_daily_buy_weight)
    total_space = max(ZERO, equity * risk.max_total_weight - position_value)
    quarantined = {
        action.symbol
        for action in request.corporate_actions
        if action.ex_date == request.entry_date
    }
    intents, diagnostics = [], []
    for symbol, instrument in sorted(instruments.items()):
        target = targets.get(symbol)
        reference = prices[symbol]
        quantity_held = held.get(symbol, ZERO)
        current_value = quantity_held * reference
        item = {
            "symbol": symbol,
            "target_weight": None if target is None else str(target),
            "current_weight": str(current_value / equity),
            "reference_price": str(reference),
            "liquidity_reference_date": str(request.signal_date),
        }
        reason = None
        delta = ZERO if target is None else target * equity - current_value
        if target is None:
            reason = "MISSING_TARGET_HOLD"
        elif symbol in quarantined:
            reason = "CORPORATE_ACTION_QUARANTINED"
        elif request.entry_date < instrument.rule_valid_from:
            reason = "TRADING_RULE_NOT_VERIFIED_FOR_DATE"
        elif bars[symbol].volume <= 0:
            reason = "PREVIOUS_LIQUIDITY_UNVERIFIED"
        elif "LARGE_PRICE_JUMP_REVIEW_REQUIRED" in bars[symbol].quality_flags:
            reason = "DATA_ANOMALY"
        elif abs(delta) < reference * instrument.lot_size and not (
            target == 0 and quantity_held > 0
        ):
            reason = "TARGET_POSITION_UNCHANGED"
        if reason:
            diagnostics.append({**item, "status": "NO_ORDER", "reason": reason})
            continue
        side = "BUY" if delta > 0 else "SELL"
        liquidity = _lot_floor(bars[symbol].volume * risk.max_volume_fraction, instrument.lot_size)
        desired = _lot_floor(abs(delta) / reference, instrument.lot_size)
        budget = ZERO
        if side == "BUY":
            budget = max(
                ZERO,
                min(
                    delta,
                    equity * risk.max_order_weight,
                    daily_budget,
                    total_space,
                    equity * risk.max_single_weight - current_value,
                    available_cash,
                ),
            )
            quantity = _affordable(
                min(desired, liquidity),
                _price(reference, side, instrument, risk),
                budget,
                instrument,
                risk,
            )
            if state.get("locked"):
                reason = "DRAWDOWN_LOCK"
            elif quantity <= 0:
                reason = "INSUFFICIENT_CASH" if budget <= 0 else "BELOW_TRADING_UNIT"
            if not reason:
                available_cash -= budget
                daily_budget -= budget
                total_space -= budget
        else:
            # 保留当日买入批次，直到实际成交日才判断是否已解锁，不能假定任意持仓均可卖。
            quantity = min(desired, liquidity, quantity_held)
            if target == 0:
                quantity = min(liquidity, quantity_held)
            if quantity <= 0:
                reason = "BELOW_TRADING_UNIT"
        if reason:
            diagnostics.append({**item, "status": "NO_ORDER", "reason": reason})
            continue
        key = ":".join(
            [
                request.experiment_id,
                request.strategy_version,
                request.account_id,
                str(request.entry_date),
                symbol,
                side,
            ]
        )
        intents.append(
            {
                **item,
                "business_key": key,
                "side": side,
                "requested_quantity": str(quantity),
                "cash_cap": str(money(budget)),
                "liquidity_cap": str(liquidity),
                "status": "FROZEN",
            }
        )
        diagnostics.append({**item, "status": "FROZEN", "reason": "WAITING_EXECUTION_TIME"})
    plan = {
        "account_id": request.account_id,
        "experiment_id": request.experiment_id,
        "strategy_version": request.strategy_version,
        "protocol_version": request.protocol_version,
        "cost_version": request.cost_version,
        "nature": request.nature,
        "evaluation_type": "FORWARD_PAPER" if request.nature == "REAL" else request.nature,
        "signal_date": str(request.signal_date),
        "entry_date": str(request.entry_date),
        "evaluation_date": str(request.evaluation_date),
        "generated_at": request.generated_at.isoformat(),
        "market_executed_at": _market_open(request.entry_date).isoformat(),
        "state_hash": _digest(state),
        "market_snapshot_hash": _digest(
            {symbol: bar.model_dump(mode="json") for symbol, bar in sorted(bars.items())}
        ),
        "reference_bars": [bar.model_dump(mode="json") for _, bar in sorted(bars.items())],
        "frozen_equity": str(equity),
        "frozen_cash": str(money(state["cash"])),
        "risk": risk.model_dump(mode="json"),
        "instruments": [item.model_dump(mode="json") for item in request.instruments],
        "corporate_actions": [item.model_dump(mode="json") for item in request.corporate_actions],
        "corporate_action_coverage": request.corporate_action_coverage,
        "intents": intents,
        "diagnostics": diagnostics,
        "trace_id": request.trace_id,
        "execution_model": EXECUTION_MODEL,
        "simulation_notice": "日线近似模拟；缺少开盘盘口，成交为预定假设，不保证真实市场可成交",
        "fee_basis": "UNVERIFIED_BROKER_FEE_SIMULATION_ASSUMPTION",
    }
    plan["plan_hash"] = _digest(plan)
    return plan


def _apply_actions(state: dict, actions: list[CorporateAction], day: date):
    """已核实公司行动单独入账；不把现金分红或份额变动混作价格预测收益。"""
    affected = set()
    for action in actions:
        if action.ex_date != day:
            continue
        if action.symbol in affected:
            raise ValueError("DUPLICATE_CORPORATE_ACTION_REQUIRES_REVIEW")
        affected.add(action.symbol)
        if not action.verified or action.pay_date < action.ex_date:
            raise ValueError("UNVERIFIED_CORPORATE_ACTION_BLOCKS_SETTLEMENT")
        held = quantities(state).get(action.symbol, ZERO)
        if held:
            amount = money(held * action.cash_per_unit)
            if amount:
                state["receivables"].append(
                    {
                        "symbol": action.symbol,
                        "amount": str(amount),
                        "pay_date": str(action.pay_date),
                        "source_url": action.source_url,
                    }
                )
            for lot in state["lots"].get(action.symbol, []):
                lot["quantity"] = str(D(lot["quantity"]) * action.split_factor)
    unpaid = []
    for receivable in state["receivables"]:
        if date.fromisoformat(receivable["pay_date"]) <= day:
            amount = D(receivable["amount"])
            state["cash"] = str(money(D(state["cash"]) + amount))
            state["cumulative_dividends"] = str(money(D(state["cumulative_dividends"]) + amount))
        else:
            unpaid.append(receivable)
    state["receivables"] = unpaid
    return affected


def value_forward(request: ForwardValuationRequest) -> dict:
    """没有当日计划也独立结算估值，真实空状态不误标成零仓位目标。"""
    day = request.trade_date
    if request.booked_at < _market_open(day):
        raise ValueError("EXECUTION_TIME_NOT_REACHED")
    state = deepcopy(request.state)
    if state.get("last_trade_date") and day <= date.fromisoformat(state["last_trade_date"]):
        return {
            "status": "ALREADY_PROCESSED",
            "state": state,
            "orders": [],
            "fills": [],
            "risk_events": [],
            "diagnostics": [],
            "already_processed": True,
        }
    bars = {bar.symbol: bar for bar in request.bars}
    symbols = {item.symbol for item in request.instruments}
    if len(bars) != len(request.bars) or len(symbols) != len(request.instruments):
        raise ValueError("DUPLICATE_BAR_OR_INSTRUMENT")
    if set(bars) != symbols:
        return {
            "status": "WAITING_COMPLETE_MARKET",
            "state": state,
            "orders": [],
            "fills": [],
            "risk_events": [{"rule": "MISSING_MARKET_NO_ORDERS", "trade_date": str(day)}],
            "diagnostics": [],
            "already_processed": False,
        }
    for bar in bars.values():
        if bar.trade_date != day or bar.price_basis != "RAW":
            raise ValueError("VALUATION_REQUIRES_TRADE_DATE_RAW_BARS")
        if request.nature == "REAL" and bar.nature != "REAL":
            raise ValueError("TEST_DATA_CANNOT_ENTER_REAL_EXPERIMENT")
        if bar.fetched_at > request.booked_at:
            raise ValueError("VALUATION_MARKET_NOT_AVAILABLE_AT_BOOKING")
        validate_final_bar(bar)
    prior_equity = D(state["equity"])
    _apply_actions(state, request.corporate_actions, day)
    opens = {symbol: bar.open for symbol, bar in bars.items()}
    opening_equity, opening_position = valuation(state, opens)
    risks = []
    if opening_equity <= D(state["peak_equity"]) * (1 - request.risk.drawdown_lock):
        state["locked"] = True
        risks.append({"rule": "DRAWDOWN_LOCK", "trade_date": str(day)})
    if opening_equity <= prior_equity * (1 - request.risk.daily_loss_limit):
        risks.append({"rule": "DAILY_LOSS_LIMIT", "trade_date": str(day)})
    closes = {symbol: bar.close for symbol, bar in bars.items()}
    state["last_prices"].update({symbol: str(value) for symbol, value in closes.items()})
    equity, position_value = valuation(state, closes)
    peak = max(D(state["peak_equity"]), equity)
    state["locked"] = bool(state["locked"] or equity <= peak * (1 - request.risk.drawdown_lock))
    state.update(equity=str(equity), peak_equity=str(peak), last_trade_date=str(day))
    return {
        "status": "VALUED_NO_FROZEN_PLAN",
        "state": state,
        "orders": [],
        "fills": [],
        "risk_events": risks,
        "diagnostics": [
            {
                "symbol": symbol,
                "status": "NO_ORDER",
                "target_weight": None,
                "reason": "MISSING_FROZEN_PLAN_HOLD",
            }
            for symbol in sorted(symbols)
        ],
        "already_processed": False,
        "snapshot": {
            "trade_date": str(day),
            "equity": str(equity),
            "cash": state["cash"],
            "position_value": str(position_value),
            "opening_position_value": str(opening_position),
            "daily_pnl": str(money(equity - prior_equity)),
            "drawdown": float(1 - equity / peak),
            "stale_valuation": False,
            "booked_at": request.booked_at.isoformat(),
            "nature": request.nature,
            "evaluation_type": "FORWARD_PAPER" if request.nature == "REAL" else request.nature,
            "validation_limitation": "CORPORATE_ACTION_COVERAGE_UNVERIFIED"
            if request.corporate_action_coverage != "VERIFIED"
            else None,
            "corporate_actions": [
                item.model_dump(mode="json")
                for item in request.corporate_actions
                if item.ex_date == day
            ],
        },
    }


def _sell(state: dict, symbol: str, quantity: Decimal, day: date, instrument: Instrument):
    """按取得日期FIFO分摊成本，任何T+1锁定批次均不进入可卖数量。"""
    remaining, cost = quantity, ZERO
    lots = state["lots"].get(symbol, [])
    for lot in sorted(lots, key=lambda item: item["acquired"]):
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
    return cost


def settle_forward(request: ForwardStepRequest) -> dict:
    """只撮合冻结方向/数量，开盘信息仅用于固定价格与硬风控，日终再估值。"""
    plan = deepcopy(request.plan)
    plan_hash = plan.pop("plan_hash", None)
    if plan_hash != _digest(plan):
        raise ValueError("FROZEN_PLAN_CHANGED")
    if plan.get("protocol_version") != PROTOCOL_VERSION or plan.get("cost_version") != COST_VERSION:
        raise ValueError("EXECUTION_PROTOCOL_VERSION_MISMATCH")
    day = date.fromisoformat(plan["entry_date"])
    generated = datetime.fromisoformat(plan["generated_at"])
    market_time = _market_open(day)
    if generated >= market_time:
        raise ValueError("LATE_PREDICTION_CANNOT_ENTER_PAST_OPEN")
    if request.booked_at < market_time:
        raise ValueError("EXECUTION_TIME_NOT_REACHED")
    state = deepcopy(request.state)
    if state.get("last_trade_date") and day <= date.fromisoformat(state["last_trade_date"]):
        return {
            "status": "ALREADY_PROCESSED",
            "state": state,
            "orders": [],
            "fills": [],
            "risk_events": [],
            "diagnostics": [],
            "already_processed": True,
        }
    if plan["state_hash"] != _digest(state):
        raise ValueError("ACCOUNT_CHANGED_AFTER_ORDER_FREEZE")
    risk = RiskConfig(**plan["risk"])
    instruments = {item["symbol"]: Instrument(**item) for item in plan["instruments"]}
    bars = {bar.symbol: bar for bar in request.bars}
    if len(bars) != len(request.bars):
        raise ValueError("DUPLICATE_EXECUTION_BAR")
    if set(bars) != set(instruments):
        return {
            "status": "WAITING_COMPLETE_MARKET",
            "state": state,
            "orders": [],
            "fills": [],
            "risk_events": [{"rule": "MISSING_MARKET_NO_ORDERS", "trade_date": str(day)}],
            "diagnostics": plan["diagnostics"],
            "already_processed": False,
        }
    for bar in bars.values():
        if bar.trade_date != day or bar.price_basis != "RAW":
            raise ValueError("EXECUTION_REQUIRES_ENTRY_DATE_RAW_BARS")
        if plan["nature"] == "REAL" and bar.nature != "REAL":
            raise ValueError("TEST_DATA_CANNOT_ENTER_REAL_EXPERIMENT")
        if bar.fetched_at > request.booked_at:
            raise ValueError("EXECUTION_MARKET_NOT_AVAILABLE_AT_BOOKING")
        validate_final_bar(bar)
    prior_equity = D(state["equity"])
    # 发现新增公司行动时只允许隔离成交，不能借修订事件重选标的或修改冻结订单。
    actions = [CorporateAction(**value) for value in plan.get("corporate_actions", [])]
    frozen_action_keys = {(item.symbol, item.ex_date) for item in actions}
    actions.extend(
        item
        for item in request.corporate_actions
        if (item.symbol, item.ex_date) not in frozen_action_keys
    )
    affected = _apply_actions(state, actions, day)
    opens = {symbol: bar.open for symbol, bar in bars.items()}
    opening_equity, opening_position = valuation(state, opens)
    locked = opening_equity <= D(state["peak_equity"]) * (1 - risk.drawdown_lock)
    state["locked"] = bool(state.get("locked") or locked)
    daily_loss = opening_equity <= prior_equity * (1 - risk.daily_loss_limit)
    orders, fills, risks, diagnostics = [], [], [], deepcopy(plan["diagnostics"])
    if locked:
        risks.append({"rule": "DRAWDOWN_LOCK", "trade_date": str(day)})
    if daily_loss:
        risks.append({"rule": "DAILY_LOSS_LIMIT", "trade_date": str(day)})
    bought_today = ZERO
    for intent in sorted(plan["intents"], key=lambda item: (item["side"] == "BUY", item["symbol"])):
        symbol, side = intent["symbol"], intent["side"]
        instrument, bar = instruments[symbol], bars[symbol]
        order = {
            **intent,
            "status": "REJECTED",
            "reason": None,
            "trade_date": str(day),
            "signal_date": plan["signal_date"],
            "signal_generated_at": plan["generated_at"],
            "market_executed_at": market_time.isoformat(),
            "booked_at": request.booked_at.isoformat(),
            "experiment_id": plan["experiment_id"],
            "strategy_version": plan["strategy_version"],
            "protocol_version": PROTOCOL_VERSION,
            "cost_version": COST_VERSION,
            "execution_model": EXECUTION_MODEL,
            "nature": plan["nature"],
            "plan_hash": plan_hash,
            "trace_id": plan.get("trace_id"),
        }
        orders.append(order)
        reason = None
        reference = D(intent["reference_price"])
        upper = _price(
            reference * (1 + instrument.limit_rate),
            "BUY",
            instrument,
            RiskConfig(slippage_bps=0, spread_bps=0),
        )
        lower = _price(
            reference * (1 - instrument.limit_rate),
            "SELL",
            instrument,
            RiskConfig(slippage_bps=0, spread_bps=0),
        )
        if symbol in affected:
            reason = "CORPORATE_ACTION_QUARANTINED"
        elif symbol in request.suspended_symbols:
            reason = "SUSPENDED_NO_MATCH"
        elif side == "BUY" and state["locked"]:
            reason = "DRAWDOWN_LOCK"
        elif side == "BUY" and daily_loss:
            reason = "DAILY_LOSS_LIMIT"
        elif (side == "BUY" and bar.open >= upper) or (side == "SELL" and bar.open <= lower):
            reason = "PRICE_LIMIT_NO_FILL"
        price = _price(bar.open, side, instrument, risk)
        quantity = D(intent["requested_quantity"])
        realized = None
        if not reason and side == "BUY":
            equity, held_value = valuation(state, opens)
            held_symbol = quantities(state).get(symbol, ZERO) * bar.open
            # 开盘硬风控只能缩量或拒绝，不能增加原冻结预算、改变资格或买卖方向。
            budget = max(
                ZERO,
                min(
                    D(intent["cash_cap"]),
                    D(state["cash"]),
                    D(plan["frozen_equity"]) * risk.max_daily_buy_weight - bought_today,
                    equity * risk.max_single_weight - held_symbol,
                    equity * risk.max_total_weight - held_value,
                ),
            )
            quantity = _affordable(quantity, price, budget, instrument, risk)
            if quantity <= 0:
                reason = "INSUFFICIENT_CASH" if budget <= 0 else "BELOW_TRADING_UNIT"
        elif not reason:
            available = sum(
                (
                    D(lot["quantity"])
                    for lot in state["lots"].get(symbol, [])
                    if instrument.settlement_days == 0 or date.fromisoformat(lot["acquired"]) < day
                ),
                ZERO,
            )
            quantity = min(quantity, available)
            if D(intent["target_weight"]) != 0:
                quantity = _lot_floor(quantity, instrument.lot_size)
            if quantity <= 0:
                reason = "T_PLUS_ONE_LOCKED"
        if reason:
            order["reason"] = reason
            risks.append({"rule": reason, "symbol": symbol, "trade_date": str(day)})
            continue
        gross = money(quantity * price)
        fee = _fee(gross, risk)
        if side == "BUY":
            state["cash"] = str(money(D(state["cash"]) - gross - fee))
            state["lots"].setdefault(symbol, []).append(
                {"quantity": str(quantity), "cost": str(gross + fee), "acquired": str(day)}
            )
            bought_today += gross + fee
        else:
            if D(state["cash"]) + gross < fee:
                order["reason"] = "INSUFFICIENT_CASH_FOR_FEE"
                continue
            cost = _sell(state, symbol, quantity, day, instrument)
            state["cash"] = str(money(D(state["cash"]) + gross - fee))
            realized = str(money(gross - fee - cost))
        order.update(status="FILLED", quantity=str(quantity), price=str(price), fee=str(fee))
        fills.append(
            {
                **order,
                "gross": str(gross),
                "realized_pnl": realized,
                "reference_open": str(bar.open),
                "slippage_cost": str(money(abs(price - bar.open) * quantity)),
                "spread_cost": str(money(bar.open * quantity * risk.spread_bps / D("20000"))),
            }
        )
        state["cumulative_fees"] = str(money(D(state["cumulative_fees"]) + fee))
    closes = {symbol: bar.close for symbol, bar in bars.items()}
    state["last_prices"].update({symbol: str(value) for symbol, value in closes.items()})
    equity, position_value = valuation(state, closes)
    peak = max(D(state["peak_equity"]), equity)
    state["locked"] = bool(state["locked"] or equity <= peak * (1 - risk.drawdown_lock))
    state.update(equity=str(equity), peak_equity=str(peak), last_trade_date=str(day))
    by_symbol = {order["symbol"]: order for order in orders}
    for item in diagnostics:
        if item["symbol"] in by_symbol:
            order = by_symbol[item["symbol"]]
            item.update(
                status=order["status"], reason=order["reason"] or "FILLED_DAILY_APPROXIMATION"
            )
    limitation = (
        "CORPORATE_ACTION_COVERAGE_UNVERIFIED"
        if plan["corporate_action_coverage"] != "VERIFIED"
        else None
    )
    return {
        "status": "SETTLED",
        "state": state,
        "orders": orders,
        "fills": fills,
        "risk_events": risks,
        "diagnostics": diagnostics,
        "already_processed": False,
        "snapshot": {
            "trade_date": str(day),
            "equity": str(equity),
            "cash": state["cash"],
            "position_value": str(position_value),
            "opening_position_value": str(opening_position),
            "daily_pnl": str(money(equity - prior_equity)),
            "drawdown": float(1 - equity / peak),
            "stale_valuation": False,
            "booked_at": request.booked_at.isoformat(),
            "nature": plan["nature"],
            "evaluation_type": plan["evaluation_type"],
            "validation_limitation": limitation,
            "corporate_actions": [
                item.model_dump(mode="json") for item in actions if item.ex_date == day
            ],
        },
    }
