"""只使用可追溯隔离夹具验证冻结意图、T+1及日线撮合边界。"""

from copy import deepcopy
from datetime import date, datetime
from decimal import Decimal as D

import pytest

from etf_quant.backtesting import CorporateAction, Instrument, RiskConfig, initial_state
from etf_quant.forward_execution import (
    ForwardFreezeRequest,
    ForwardStepRequest,
    ForwardValuationRequest,
    freeze_orders,
    settle_forward,
    value_forward,
)
from etf_quant.schemas import Bar

SIGNAL = date(2026, 10, 23)
ENTRY = date(2026, 10, 26)
EVALUATION = date(2026, 10, 27)
GENERATED = datetime.fromisoformat("2026-10-24T20:00:00+08:00")
BOOKED = datetime.fromisoformat("2026-10-26T17:30:00+08:00")


def bar(day, price="10", **changes):
    """隔离测试行情显式标DEMO，禁止通过REAL实验入口。"""
    values = {
        "symbol": "510300",
        "trade_date": day,
        "open": D(price),
        "close": D(price),
        "high": D(price) * D("1.1"),
        "low": D(price) * D("0.9"),
        "volume": D(10000000),
        "source": "FORWARD_EXECUTION_TEST_FIXTURE",
        "nature": "DEMO",
        "fetched_at": datetime.fromisoformat(f"{day}T16:00:00+08:00"),
    }
    values.update(changes)
    return Bar(**values)


def freeze(state=None, target="0.2", **changes):
    """所有数量与预算都在周六真实冻结时刻确定，周一行情不在请求中。"""
    values = {
        "account_id": "isolated-test-account",
        "experiment_id": "isolated-test-experiment",
        "strategy_version": "forward-v2.0.0",
        "signal_date": SIGNAL,
        "entry_date": ENTRY,
        "evaluation_date": EVALUATION,
        "generated_at": GENERATED,
        "state": state or initial_state("100000"),
        "previous_bars": [bar(SIGNAL)],
        "instruments": [Instrument(symbol="510300")],
        "targets": [{"symbol": "510300", "target_weight": target}],
        "trading_dates": [SIGNAL, ENTRY, EVALUATION],
        "nature": "TEST",
        "risk": RiskConfig(slippage_bps=0, spread_bps=0),
    }
    values.update(changes)
    return freeze_orders(ForwardFreezeRequest(**values))


def settle(plan, state=None, **changes):
    """账本独立接收开盘成交时刻和日终实际记账时刻。"""
    values = {
        "plan": plan,
        "state": state or initial_state("100000"),
        "bars": [bar(ENTRY)],
        "booked_at": BOOKED,
    }
    values.update(changes)
    return settle_forward(ForwardStepRequest(**values))


def held_state(quantity="1000", acquired="2026-10-23", cash="90000.00"):
    """固定持仓批次体现取得日与成本，无真实账户写入。"""
    state = initial_state("100000")
    state["cash"] = cash
    state["lots"] = {
        "510300": [{"quantity": quantity, "cost": str(D(quantity) * 10), "acquired": acquired}]
    }
    return state


def test_weekend_and_holiday_calendar_require_confirmed_next_two_sessions():
    plan = freeze()
    assert plan["entry_date"] == "2026-10-26"
    assert plan["evaluation_date"] == "2026-10-27"
    holiday = freeze(
        signal_date=date(2026, 9, 30),
        entry_date=date(2026, 10, 8),
        evaluation_date=date(2026, 10, 9),
        previous_bars=[bar(date(2026, 9, 30))],
        generated_at=datetime.fromisoformat("2026-09-30T17:30:00+08:00"),
        trading_dates=[date(2026, 9, 30), date(2026, 10, 8), date(2026, 10, 9)],
    )
    assert holiday["entry_date"] == "2026-10-08"
    with pytest.raises(ValueError, match="CONSECUTIVE"):
        freeze(entry_date=date(2026, 10, 24))
    with pytest.raises(ValueError, match="CALENDAR"):
        freeze(trading_dates=[ENTRY, EVALUATION])


def test_late_prediction_and_not_yet_open_are_rejected():
    with pytest.raises(ValueError, match="LATE_PREDICTION"):
        freeze(generated_at=datetime.fromisoformat("2026-10-26T09:30:00+08:00"))
    with pytest.raises(ValueError, match="EXECUTION_TIME_NOT_REACHED"):
        settle(freeze(), booked_at=datetime.fromisoformat("2026-10-26T09:29:59+08:00"))


def test_freeze_only_uses_available_input_and_allows_no_real_mock_mix():
    with pytest.raises(ValueError, match="NOT_AVAILABLE_AT_GENERATION"):
        freeze(previous_bars=[bar(SIGNAL, fetched_at=BOOKED)])
    with pytest.raises(ValueError, match="TEST_DATA_CANNOT"):
        freeze(nature="REAL")
    real_plan = freeze(nature="REAL", previous_bars=[bar(SIGNAL, nature="REAL")])
    with pytest.raises(ValueError, match="TEST_DATA_CANNOT"):
        settle(real_plan)
    historical = freeze(nature="HISTORICAL")
    assert historical["evaluation_type"] == "HISTORICAL"
    assert settle(freeze())["snapshot"]["evaluation_type"] == "TEST"


def test_quantity_budget_and_three_clocks_are_frozen():
    plan = freeze()
    intent = plan["intents"][0]
    assert intent["requested_quantity"] == "400"
    assert intent["cash_cap"] == "5000.00"
    assert plan["reference_bars"] == [bar(SIGNAL).model_dump(mode="json")]
    result = settle(plan)
    fill = result["fills"][0]
    assert result["state"]["cash"] == "95995.00"
    assert fill["signal_generated_at"] == GENERATED.isoformat()
    assert fill["market_executed_at"] == "2026-10-26T09:30:00+08:00"
    assert fill["booked_at"] == BOOKED.isoformat()
    assert fill["business_key"].startswith("isolated-test-experiment:forward-v2.0.0:")
    assert result["snapshot"]["validation_limitation"] == "CORPORATE_ACTION_COVERAGE_UNVERIFIED"


def test_no_order_uses_entry_close_high_low_or_daily_volume():
    plan = freeze()
    baseline = settle(plan)
    changed = settle(plan, bars=[bar(ENTRY, close=D(15), high=D(18), low=D(1), volume=D(0))])
    assert baseline["orders"] == changed["orders"]
    assert baseline["fills"] == changed["fills"]
    assert baseline["state"]["equity"] != changed["state"]["equity"]


def test_open_gap_can_only_reduce_frozen_quantity_and_never_increase_it():
    plan = freeze()
    cheaper = settle(plan, bars=[bar(ENTRY, price="9.5")])
    assert D(cheaper["fills"][0]["quantity"]) == D(plan["intents"][0]["requested_quantity"])
    small_budget_plan = freeze(previous_bars=[bar(SIGNAL, price="1")])
    result = settle(small_budget_plan, bars=[bar(ENTRY, price="1.09")])
    assert D(result["fills"][0]["quantity"]) < D(
        small_budget_plan["intents"][0]["requested_quantity"]
    )
    assert D(result["fills"][0]["gross"]) + D(result["fills"][0]["fee"]) <= D(5000)


def test_target_extension_is_net_rebalance_and_missing_target_is_hold():
    state = held_state(quantity="2000", cash="80000.00")
    unchanged = freeze(state=state)
    assert unchanged["intents"] == []
    assert unchanged["diagnostics"][0]["reason"] == "TARGET_POSITION_UNCHANGED"
    missing = freeze(state=state, target=None)
    assert missing["intents"] == []
    assert missing["diagnostics"][0]["reason"] == "MISSING_TARGET_HOLD"
    result = settle(missing, state=state)
    assert result["fills"] == [] and result["state"]["lots"] == state["lots"]


def test_t_plus_one_locks_today_batch_and_sells_older_batch():
    state = held_state(acquired=str(ENTRY))
    plan = freeze(state=state, target="0")
    result = settle(plan, state=state)
    assert result["orders"][0]["reason"] == "T_PLUS_ONE_LOCKED"
    assert result["state"]["lots"] == state["lots"]
    state["lots"]["510300"].append(
        {
            "quantity": "200",
            "cost": "2000",
            "acquired": str(SIGNAL),
        }
    )
    mixed = settle(freeze(state=state, target="0"), state=state)
    assert mixed["fills"][0]["quantity"] == "200"
    assert mixed["state"]["lots"]["510300"][0]["quantity"] == "1000"


def test_same_plan_retry_and_serialized_restart_cannot_double_book():
    plan = freeze()
    result = settle(plan)
    repeated = settle(deepcopy(plan), state=deepcopy(result["state"]))
    assert repeated["already_processed"] is True
    assert repeated["fills"] == [] and repeated["orders"] == []
    assert repeated["state"] == result["state"]


def test_frozen_plan_and_account_changes_are_detected():
    plan = freeze()
    plan["intents"][0]["requested_quantity"] = "500"
    with pytest.raises(ValueError, match="FROZEN_PLAN_CHANGED"):
        settle(plan)
    state = initial_state("100000")
    state["cash"] = "99999"
    with pytest.raises(ValueError, match="ACCOUNT_CHANGED"):
        settle(freeze(), state=state)


@pytest.mark.parametrize("limit,expected", [(".10", False), (".20", True)])
def test_price_limit_reads_instrument_rule(limit, expected):
    plan = freeze(instruments=[Instrument(symbol="510300", limit_rate=D(limit))])
    result = settle(plan, bars=[bar(ENTRY, price="11")])
    assert bool(result["fills"]) is expected
    if not expected:
        assert result["orders"][0]["reason"] == "PRICE_LIMIT_NO_FILL"


def test_missing_market_does_not_advance_account_and_suspension_is_explicit():
    result = settle(freeze(), bars=[])
    assert result["status"] == "WAITING_COMPLETE_MARKET"
    assert result["state"]["last_trade_date"] is None
    stopped = settle(freeze(), suspended_symbols=["510300"])
    assert stopped["orders"][0]["reason"] == "SUSPENDED_NO_MATCH"
    assert not stopped["fills"]


def test_company_action_is_separate_and_affected_order_quarantined():
    state = held_state()
    action = CorporateAction(
        symbol="510300",
        ex_date=ENTRY,
        pay_date=ENTRY,
        cash_per_unit=D(".1"),
        split_factor=D(2),
        reference_price=D("4.95"),
        verified=True,
        source_url="https://example.test/verified-action",
    )
    plan = freeze(state=state, target="0")
    result = settle(plan, state=state, corporate_actions=[action], bars=[bar(ENTRY, price="4.95")])
    assert result["orders"][0]["reason"] == "CORPORATE_ACTION_QUARANTINED"
    assert result["state"]["lots"]["510300"][0]["quantity"] == "2000"
    assert result["state"]["cash"] == "90100.00"
    assert result["state"]["equity"] == "100000.00"
    assert result["snapshot"]["corporate_actions"]
    with pytest.raises(ValueError, match="UNVERIFIED_CORPORATE_ACTION"):
        settle(plan, state=state, corporate_actions=[action.model_copy(update={"verified": False})])


def test_hard_drawdown_risk_still_applies_to_missing_target():
    state = held_state()
    state["peak_equity"] = "120000.00"
    result = settle(freeze(state=state, target=None), state=state)
    assert result["state"]["locked"] is True
    assert not result["fills"]
    assert any(item["rule"] == "DRAWDOWN_LOCK" for item in result["risk_events"])


def test_missing_target_position_reserves_total_budget():
    state = held_state(quantity="3000", cash="70000")
    with pytest.raises(ValueError, match="RETAINED_POSITIONS"):
        freeze(
            state=state,
            instruments=[Instrument(symbol="510300"), Instrument(symbol="510500")],
            previous_bars=[bar(SIGNAL), bar(SIGNAL, symbol="510500")],
            targets=[
                {"symbol": "510300", "target_weight": None},
                {"symbol": "510500", "target_weight": ".2"},
            ],
        )


def test_missing_frozen_plan_values_without_orders_and_keeps_hard_risk():
    """无计划不应伪造迟到计划；正常估值、锁定和重试仍可运行。"""
    state = held_state()
    state["peak_equity"] = "120000.00"
    request = ForwardValuationRequest(
        state=state,
        bars=[bar(ENTRY)],
        booked_at=BOOKED,
        trade_date=ENTRY,
        instruments=[Instrument(symbol="510300")],
        nature="TEST",
    )
    result = value_forward(request)
    assert result["status"] == "VALUED_NO_FROZEN_PLAN"
    assert not result["orders"] and not result["fills"]
    assert result["state"]["locked"]
    assert result["state"]["lots"] == state["lots"]
    assert result["diagnostics"][0]["target_weight"] is None
    assert value_forward(request.model_copy(update={"state": result["state"]}))["already_processed"]
    with pytest.raises(ValueError, match="TEST_DATA_CANNOT"):
        value_forward(request.model_copy(update={"nature": "REAL"}))
    missing = value_forward(request.model_copy(update={"bars": []}))
    assert missing["status"] == "WAITING_COMPLETE_MARKET"
    assert missing["state"]["last_trade_date"] is None
