"""V1.1审计反例：独立手算口径、未来数据扰动、非交易日和冻结工件边界。"""

from datetime import UTC, date, datetime
from decimal import Decimal as D

import pytest
from test_backtesting import bar, step

from etf_quant.backtesting import performance
from etf_quant.calendar import official_calendar, official_session
from etf_quant.data import clean_records, fetch_calendar
from etf_quant.schemas import FetchRequest


def test_performance_matches_hand_calculated_all_metrics():
    """逐个断言指标独立手算值，避免只断言存在字段或重抄实现。"""
    curve = [
        {"equity": "1000", "position_value": "0", "opening_position_value": "0"},
        {"equity": "1100", "position_value": "500", "opening_position_value": "0"},
        {"equity": "990", "position_value": "0", "opening_position_value": "300"},
        {"equity": "1050", "position_value": "250", "opening_position_value": "0"},
    ]
    fills = [
        {
            "realized_pnl": str(pnl),
            "fee": "5",
            "gross": "100",
            "slippage_cost": "0.10",
            "spread_cost": "0.02",
        }
        for pnl in (10, -20, -10, 0, 30, -5)
    ]
    result = performance(curve, fills, D(1000))
    assert result["trade_count"] == result["closed_trades"] == 6
    assert result["win_rate"] == pytest.approx(2 / 6)
    assert result["avg_win_amount"] == "20.00"
    assert result["avg_loss_amount"] == "-11.67"
    assert result["profit_loss_ratio"] == pytest.approx(12 / 7)
    assert result["total_return"] == pytest.approx(0.05)
    assert result["annualized_return"] == pytest.approx(1.05**63 - 1)
    assert result["max_drawdown"] == pytest.approx(0.1)
    assert result["holding_sessions"] == 3
    assert result["holding_ratio"] == 0.75
    assert result["capital_utilization"] == pytest.approx((5 / 11 + 5 / 21) / 4)
    assert result["turnover"] == pytest.approx(600 / 1028)
    assert result["fees"] == "30.00"
    assert result["slippage_cost"] == "0.60"
    assert result["spread_cost"] == "0.12"
    assert result["max_consecutive_losses"] == 2


def test_no_trade_is_not_presented_as_risk_control_or_perfect_win_rate():
    """纯现金无成交时胜率和盈亏比未定义，零收益不冒充高胜率。"""
    result = performance([{"equity": "100000", "position_value": "0"}], [], D(100000))
    assert result["activity_status"] == "NO_TRADES"
    assert result["win_rate"] is None and result["profit_loss_ratio"] is None
    assert result["avg_win_amount"] is None and result["avg_loss_amount"] is None
    assert result["max_consecutive_losses"] is None
    assert result["holding_sessions"] == 0 and result["capital_utilization"] == 0
    assert result["total_return"] == result["max_drawdown"] == 0


def test_drawdown_includes_initial_fee_loss_and_annualization_is_trading_days():
    """短期手续费损失年化放大但最大回撤很小，在低暴露策略中数学上可以同时成立。"""
    result = performance(
        [{"equity": "99995", "position_value": "4000"}],
        [{"realized_pnl": None, "gross": "4000", "fee": "5"}],
        D(100000),
    )
    assert result["max_drawdown"] == pytest.approx(0.00005)
    assert result["annualized_return"] == pytest.approx(0.99995**252 - 1)
    assert result["activity_status"] == "LOW_ACTIVITY"
    assert result["win_rate"] is None and result["slippage_cost"] is None


def test_observed_v1_xgboost_numbers_reconcile_to_real_four_fills():
    """冻结原验收记录重算：72元损失、20元佣金、77交易日，并非强风控证据。"""
    import json
    from pathlib import Path

    frozen = json.loads((Path(__file__).parent / "fixtures/v1-xgboost-20260701.json").read_text())
    result = performance(frozen["curve"], frozen["fills"], D(100000))
    assert result["sessions"] == 77
    assert result["final_equity"] == "99928.00"
    assert result["trade_count"] == 4 and result["closed_trades"] == 2
    assert result["fees"] == "20.00"
    assert result["annualized_return"] == pytest.approx(-0.0023544362913467953)
    assert result["max_drawdown"] == pytest.approx(0.00072)
    assert result["capital_utilization"] == pytest.approx(0.0012027009669480489)
    assert result["holding_sessions"] == 4 and result["closing_holding_sessions"] == 2
    assert result["win_rate"] == 0
    assert result["activity_status"] == "LOW_ACTIVITY"


def test_zero_equity_returns_remain_finite_and_deposit_is_not_return():
    """极端归零后不产生NaN，无现金流调整记录时不把再入金算作盈利。"""
    result = performance([{"equity": "0"}, {"equity": "0"}], [], D(1000))
    assert result["total_return"] == -1 and result["max_drawdown"] == 1
    with pytest.raises(ValueError, match="现金流"):
        performance([{"equity": "0"}, {"equity": "1000"}], [], D(1000))


def test_today_high_low_and_volume_do_not_change_open_order():
    """同一开盘及历史输入，改变开盘后最高最低和全日量，不得改变开盘决策。"""
    baseline = step()
    modified = step(bars=[bar(date(2024, 1, 4), volume="1", high=D(20), low=D(1))])
    assert baseline["fills"] == modified["fills"]
    assert baseline["fills"][0]["liquidity_reference_date"] == "2024-01-03"


def test_slippage_and_spread_are_measured_from_fill_without_double_counting():
    """10元开盘，5bp滑点和半个2bp价差，经0.001价位取整，400份价格成本2.40元。"""
    from etf_quant.backtesting import RiskConfig

    result = step(risk=RiskConfig())
    fill = result["fills"][0]
    assert fill["price"] == "10.006" and fill["quantity"] == "400"
    assert fill["slippage_cost"] == "2.40" and fill["spread_cost"] == "0.40"
    assert result["state"]["cash"] == "95992.60"


def test_excess_return_uses_same_window_and_constrained_benchmark():
    """两执行日基准赚190元，现金超额必须是-0.19%，不能比较不同窗口。"""
    from etf_quant.backtesting import BacktestRequest, Instrument, RiskConfig, run_backtest

    result = run_backtest(
        BacktestRequest(
            strategy="cash",
            instruments=[Instrument(symbol="510300")],
            bars=[
                bar(date(2024, 1, 2)),
                bar(date(2024, 1, 3)),
                bar(date(2024, 1, 4), price="10.5"),
            ],
            start=date(2024, 1, 3),
            end=date(2024, 1, 4),
            risk=RiskConfig(slippage_bps=0, spread_bps=0),
        )
    )
    assert result["benchmark"]["total_return"] == pytest.approx(0.0019)
    assert result["metrics"]["excess_return_vs_buy_hold"] == pytest.approx(-0.0019)
    assert [row["trade_date"] for row in result["curve"]] == [
        row["trade_date"] for row in result["benchmark"]["curve"]
    ]
    assert result["benchmark"]["risk"]["max_total_weight"] == "0.40"


def test_today_close_quality_cannot_retroactively_reject_open_order():
    """当天收盘异常标记不可以反过来选择性删除当天开盘亏损交易。"""
    today_flag = step(
        bars=[bar(date(2024, 1, 4), quality_flags=["LARGE_PRICE_JUMP_REVIEW_REQUIRED"])]
    )
    previous_flag = step(
        previous_bars=[bar(date(2024, 1, 3), quality_flags=["LARGE_PRICE_JUMP_REVIEW_REQUIRED"])]
    )
    assert today_flag["fills"]
    assert not previous_flag["fills"]


def test_fractional_holding_can_be_fully_liquidated():
    """拆分后的不足一手持仓在全退出时可卖；不得被整手阈值提前跳过。"""
    from etf_quant.backtesting import initial_state

    state = initial_state("1000")
    state["cash"] = "500.00"
    state["lots"] = {"510300": [{"quantity": "50", "cost": "500.00", "acquired": "2024-01-02"}]}
    result = step(state=state, target="0")
    assert result["fills"][0]["quantity"] == "50"
    assert result["state"]["cash"] == "995.00"
    assert result["state"]["lots"]["510300"] == []


def test_duplicate_daily_market_rejected():
    with pytest.raises(ValueError, match="重复行情"):
        step(bars=[bar(date(2024, 1, 4)), bar(date(2024, 1, 4))])


def test_paused_or_missing_prediction_values_existing_holdings_without_orders():
    """Java暂停/预测不全时传空信号：继续估值与风险观察，但不能新增订单或强平。"""
    from etf_quant.backtesting import initial_state

    state = initial_state("1000")
    state["cash"] = "500.00"
    state["lots"] = {"510300": [{"quantity": "50", "cost": "500.00", "acquired": "2024-01-02"}]}
    result = step(state=state, signals=[], price="8")
    assert not result["orders"] and not result["fills"]
    assert result["state"]["cash"] == "500.00"
    assert result["state"]["equity"] == "900.00" and result["state"]["locked"]


@pytest.mark.parametrize(
    "day,expected",
    [
        ("2026-10-24", False),
        ("2026-10-26", True),
        ("2026-10-01", False),
        ("2026-10-08", True),
        ("2026-10-10", False),
        ("2026-02-23", False),
        ("2026-02-24", True),
        ("2027-01-04", None),
    ],
)
def test_official_sessions_weekend_holiday_reopening_and_unknown(day, expected):
    assert official_session(date.fromisoformat(day)) is expected


def test_calendar_rejects_provider_holiday_and_no_silent_future_extrapolation(monkeypatch):
    monkeypatch.setattr(
        "etf_quant.data.ak_call",
        lambda *_: [
            {"trade_date": "2025-12-31"},
            {"trade_date": "2026-10-01"},
            {"trade_date": "2027-01-04"},
        ],
    )
    result = fetch_calendar()
    assert "2025-12-31" in result["dates"] and "2026-10-08" in result["dates"]
    assert "2026-10-01" not in result["dates"] and "2027-01-04" not in result["dates"]
    assert "2026-10-01" in result["closed_dates"]
    assert result["coverage_end"] == "2026-12-31"


def test_calendar_api_failure_retains_only_official_coverage(monkeypatch):
    def fail(*_):
        raise ValueError("timeout")

    monkeypatch.setattr("etf_quant.data.ak_call", fail)
    result = fetch_calendar()
    assert result["source_status"] == "HISTORICAL_SOURCE_UNAVAILABLE"
    assert result["dates"] == official_calendar()["dates"]


@pytest.mark.parametrize(
    "now,accepted",
    [
        (datetime(2026, 10, 23, 6, 59, tzinfo=UTC), False),
        (datetime(2026, 10, 23, 7, 14, tzinfo=UTC), False),
        (datetime(2026, 10, 23, 7, 15, tzinfo=UTC), True),
        (datetime(2026, 10, 24, 2, 0, tzinfo=UTC), True),
    ],
)
def test_incomplete_real_day_rejected_until_close_grace(now, accepted):
    request = FetchRequest(
        symbol="510300", start=date(2026, 10, 23), end=date(2026, 10, 23), provider="SINA"
    )
    rows = [
        {
            "date": "2026-10-23",
            "open": "4",
            "high": "4.1",
            "low": "3.9",
            "close": "4",
            "volume": "100000",
        }
    ]
    result = clean_records(rows, request, now=now)
    assert bool(result["bars"]) is accepted
    assert result["report"]["incomplete_session_rejected"] == int(not accepted)
