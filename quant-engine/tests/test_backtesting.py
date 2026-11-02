from datetime import UTC, date, datetime, timedelta
from decimal import Decimal as D

import pytest

from etf_quant.backtesting import (
    BacktestRequest,
    CorporateAction,
    Instrument,
    RiskConfig,
    Signal,
    StepRequest,
    daily_step,
    initial_state,
    run_backtest,
)
from etf_quant.data import fetch_bars
from etf_quant.schemas import Bar, FetchRequest


def bar(day, price="10", volume="10000000", **kwargs):
    p = D(price)
    values = {
        "symbol": "510300",
        "trade_date": day,
        "open": p,
        "high": p * D("1.01"),
        "low": p * D(".99"),
        "close": p,
        "volume": D(volume),
        "source": "DETERMINISTIC_TEST",
        "nature": "DEMO",
        "fetched_at": datetime(2026, 1, 1, tzinfo=UTC),
    }
    values.update(kwargs)
    return Bar(**values)


def step(state=None, target=".2", day=date(2024, 1, 4), price="10", **kwargs):
    config = {
        "account_id": "test",
        "strategy": "ma",
        "state": state or initial_state("100000"),
        "trade_date": day,
        "previous_trade_date": day - timedelta(days=1),
        "bars": [bar(day, price)],
        "previous_bars": [bar(day - timedelta(days=1))],
        "instruments": [Instrument(symbol="510300")],
        "signals": [
            Signal(symbol="510300", signal_date=day - timedelta(days=1), target_weight=D(target))
        ],
        "risk": RiskConfig(slippage_bps=0, spread_bps=0),
    }
    config.update(kwargs)
    return daily_step(StepRequest(**config))


def test_exact_cash_commission_and_t_plus_one_next_day():
    first = step()
    assert first["fills"][0]["quantity"] == "400"
    assert first["state"]["cash"] == "95995.00"
    second = step(first["state"], target="0", day=date(2024, 1, 5))
    assert second["state"]["cash"] == "99990.00"
    assert second["fills"][0]["realized_pnl"] == "-10.00"


def test_t_plus_one_blocks_same_day_lot_sale():
    state = initial_state("100000")
    state["cash"] = "96000.00"
    state["lots"] = {"510300": [{"quantity": "400", "cost": "4000.00", "acquired": "2024-01-04"}]}
    result = step(state, target="0")
    assert not result["fills"]
    assert result["orders"][0]["reason"] == "T_PLUS_ONE_OR_VOLUME_LIMIT"


def test_idempotent_same_day_has_no_second_fill():
    first = step()
    repeat = step(first["state"])
    assert repeat["already_processed"]
    assert repeat["state"] == first["state"] and not repeat["orders"]


def test_no_same_close_or_stale_signal():
    for signal_day in (date(2024, 1, 4), date(2024, 1, 2)):
        with pytest.raises(ValueError, match="过期"):
            step(signals=[Signal(symbol="510300", signal_date=signal_day, target_weight=D(".2"))])


@pytest.mark.parametrize(
    "case", ["limit", "volume", "suspended", "missing", "drawdown", "daily_loss"]
)
def test_risk_and_liquidity_blocks(case):
    kwargs = {}
    if case == "limit":
        kwargs["price"] = "11"
    if case == "volume":
        kwargs["previous_bars"] = [bar(date(2024, 1, 3), volume="100")]
    if case == "suspended":
        kwargs["bars"] = [bar(date(2024, 1, 4), volume="0")]
    if case == "missing":
        kwargs["bars"] = []
    if case == "drawdown":
        state = initial_state("100000")
        state["peak_equity"] = "120000.00"
        kwargs["state"] = state
    if case == "daily_loss":
        state = initial_state("100000")
        state["cash"] = "98000.00"
        kwargs["state"] = state
    result = step(**kwargs)
    assert not result["fills"]


def test_dividend_receivable_and_later_cash_payment():
    state = initial_state("2000")
    state["cash"] = "1000.00"
    state["lots"] = {"510300": [{"quantity": "100", "cost": "1000.00", "acquired": "2024-01-02"}]}
    action = CorporateAction(
        symbol="510300",
        ex_date=date(2024, 1, 4),
        pay_date=date(2024, 1, 5),
        cash_per_unit=D(".10"),
        reference_price=D("9.9"),
        verified=True,
        source_url="https://example.test/action",
    )
    first = step(state=state, price="9.9", signals=[], corporate_actions=[action])
    assert first["state"]["equity"] == "2000.00" and first["state"]["cash"] == "1000.00"
    second = step(
        state=first["state"],
        day=date(2024, 1, 5),
        price="9.9",
        signals=[],
        corporate_actions=[action],
    )
    assert second["state"]["cash"] == "1010.00" and not second["state"]["receivables"]


def test_split_preserves_equity_and_fractional_lots():
    state = initial_state("1000")
    state["cash"] = "0.00"
    state["lots"] = {"510300": [{"quantity": "100", "cost": "1000.00", "acquired": "2024-01-02"}]}
    action = CorporateAction(
        symbol="510300",
        ex_date=date(2024, 1, 4),
        pay_date=date(2024, 1, 4),
        split_factor=D(2),
        reference_price=D(5),
        verified=True,
        source_url="https://example.test/action",
    )
    result = step(state=state, price="5", signals=[], corporate_actions=[action])
    assert result["state"]["equity"] == "1000.00"
    assert result["state"]["lots"]["510300"][0]["quantity"] == "200"


def test_cash_baseline_and_rule_deterministic():
    bars = fetch_bars(
        FetchRequest(
            symbol="510300", start=date(2021, 1, 1), end=date(2023, 12, 31), provider="DEMO"
        )
    )["bars"]
    request = BacktestRequest(
        strategy="cash",
        bars=bars,
        instruments=[Instrument(symbol="510300")],
        start=date(2022, 1, 1),
        end=date(2023, 12, 31),
    )
    cash = run_backtest(request)
    assert cash["metrics"]["final_equity"] == "100000.00" and not cash["fills"]
    request.strategy = "ma"
    first = run_backtest(request)
    second = run_backtest(request)
    assert first == second
    assert all(fill["signal_date"] < fill["trade_date"] for fill in first["fills"])
    assert all(D(row["cash"]) >= 0 for row in first["curve"])


def test_llm_history_without_point_in_time_news_rejected():
    request = BacktestRequest(
        strategy="llm",
        bars=[bar(date(2024, 1, 3)), bar(date(2024, 1, 4))],
        instruments=[Instrument(symbol="510300")],
        start=date(2024, 1, 3),
        end=date(2024, 1, 4),
    )
    with pytest.raises(ValueError, match="前瞻"):
        run_backtest(request)


def test_buy_and_hold_stops_rebalancing_after_entry_window():
    bars = fetch_bars(
        FetchRequest(
            symbol="510300", start=date(2021, 1, 1), end=date(2023, 12, 31), provider="DEMO"
        )
    )["bars"]
    request = BacktestRequest(
        strategy="buy_hold",
        bars=bars,
        instruments=[Instrument(symbol="510300")],
        start=date(2022, 1, 3),
        end=date(2023, 12, 31),
    )
    result = run_backtest(request)
    fifth_day = result["curve"][4]["trade_date"]
    assert all(
        fill["trade_date"] <= fifth_day and fill["side"] == "BUY" for fill in result["fills"]
    )
    assert result["state"]["buy_hold_entry_sessions"] == 5


def test_zero_volume_rejects_the_whole_experiment_before_signal_generation(monkeypatch):
    """现金及仅交易另一ETF的策略也拒绝区间内零量；区间外零量不按当日事后信息筛单。"""
    previous, day, later = date(2024, 1, 3), date(2024, 1, 4), date(2024, 1, 5)
    bars = [
        bar(previous, nature="REAL"),
        bar(previous, symbol="159915", nature="REAL"),
        bar(day, nature="REAL"),
        bar(day, symbol="159915", nature="REAL"),
    ]
    request = BacktestRequest(
        strategy="cash",
        bars=bars,
        instruments=[Instrument(symbol="510300"), Instrument(symbol="159915")],
        start=day,
        end=day,
        trading_dates=[previous, day, later],
        evaluation_type="HISTORICAL_OOS",
    )
    outside = [
        item.model_copy(update={"volume": D(0)}) if item.trade_date < day else item for item in bars
    ]
    outside.append(bar(later, volume="0", nature="REAL"))
    assert run_backtest(request.model_copy(update={"bars": outside}))["metrics"]["trade_count"] == 0

    def unexpected_signals(_request):
        """数据不合格时绝不能先计算策略或进入逐日撮合。"""
        raise AssertionError("不合格回测不应调用策略生成")

    monkeypatch.setattr("etf_quant.backtesting.strategy_signals", unexpected_signals)
    for strategy in ("cash", "xgboost"):
        for high, low in ((D("10.1"), D("9.9")), (D(10), D(10))):
            invalid = bars[:-1] + [
                bars[-1].model_copy(update={"volume": D(0), "high": high, "low": low})
            ]
            signals = (
                []
                if strategy == "cash"
                else [Signal(symbol="510300", signal_date=previous, target_weight=D("0.2"))]
            )
            with pytest.raises(
                ValueError, match="SOURCE_ZERO_VOLUME_UNVERIFIED: 159915 2024-01-04"
            ):
                run_backtest(
                    request.model_copy(
                        update={"strategy": strategy, "bars": invalid, "signals": signals}
                    )
                )


def test_positive_current_volume_never_changes_historical_open_decisions():
    """已知前日量与信号固定，当日正量跨多个数量级变化不影响开盘订单、成交、权益及指标。"""
    previous, day = date(2024, 1, 3), date(2024, 1, 4)
    request = BacktestRequest(
        strategy="xgboost",
        bars=[bar(previous, nature="REAL"), bar(day, nature="REAL")],
        instruments=[Instrument(symbol="510300")],
        start=day,
        end=day,
        trading_dates=[previous, day],
        signals=[Signal(symbol="510300", signal_date=previous, target_weight=D("0.2"))],
        evaluation_type="HISTORICAL_OOS",
    )
    expected = run_backtest(request)
    assert expected["fills"]
    for volume in ("1", "100", "10000000", "1000000000000"):
        result = run_backtest(
            request.model_copy(
                update={"bars": [request.bars[0], bar(day, volume=volume, nature="REAL")]}
            )
        )
        for field in ("orders", "fills", "curve", "metrics"):
            assert result[field] == expected[field]
