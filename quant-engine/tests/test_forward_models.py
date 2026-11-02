"""新开盘标签的独立手算、真实时间边界及不可变工件回归；全部DEMO隔离样本。"""

import json
from copy import deepcopy
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal

import pandas as pd
import pytest
from pydantic import ValidationError

from etf_quant.calendar import official_calendar, official_session
from etf_quant.data import fetch_bars
from etf_quant.forward_models import (
    ForwardPredictRequest,
    ForwardTrainRequest,
    labeled_frame,
    market_time,
    predict_forward,
    probability_diagnostics,
    purge_before_time,
    trading_window,
    train_forward_model,
)
from etf_quant.schemas import FetchRequest

TEST_NOW = datetime(2026, 10, 24, 12, tzinfo=UTC)


@pytest.fixture(autouse=True)
def isolated_model_clock(monkeypatch):
    """固定回归测试时钟，不因未来真实日期越过样本开盘而改变测试结果。"""

    class TestClock(datetime):
        """仅替换本模块模型边界使用的时钟，不改变系统或生产时间。"""

        @classmethod
        def now(cls, tz=None):
            """返回隔离测试中已声明的真实生成节点。"""
            return TEST_NOW

    monkeypatch.setattr("etf_quant.forward_models.datetime", TestClock)


@pytest.fixture(scope="module")
def frozen_data():
    """明确DEMO性质；固定合成序列只进入pytest临时工件目录。"""
    bars = fetch_bars(
        FetchRequest(
            symbol="510300", start=date(2021, 1, 1), end=date(2026, 10, 23), provider="DEMO"
        )
    )["bars"]
    bars = [
        bar for bar in bars if official_session(date.fromisoformat(bar["trade_date"])) is not False
    ]
    for bar in bars:
        bar["fetched_at"] = market_time(
            date.fromisoformat(bar["trade_date"]), time(15, 20)
        ).isoformat()
    calendar = [date.fromisoformat(bar["trade_date"]) for bar in bars]
    calendar.extend(date.fromisoformat(day) for day in official_calendar()["dates"])
    return bars, sorted(set(calendar))


def request_for(frozen_data, algorithm="lightgbm", **overrides):
    """固定输入身份，测试不修改既有模型目录或真实库。"""
    bars, calendar = frozen_data
    return ForwardTrainRequest(
        **{
            "symbol": "510300",
            "algorithm": algorithm,
            "bars": bars,
            "trading_dates": calendar,
            "training_cutoff_at": TEST_NOW,
            "code_version": "a" * 40,
            **overrides,
        }
    )


def test_calendar_weekend_holiday_and_missing_coverage():
    calendar = [date.fromisoformat(day) for day in official_calendar()["dates"]]
    assert trading_window(date(2026, 10, 23), calendar) == (date(2026, 10, 26), date(2026, 10, 27))
    assert trading_window(date(2026, 9, 30), calendar) == (date(2026, 10, 8), date(2026, 10, 9))
    with pytest.raises(ValueError, match="UNCONFIRMED"):
        trading_window(date(2026, 12, 31), calendar)
    with pytest.raises(ValueError, match="NOT_IN_CONFIRMED"):
        trading_window(date(2026, 10, 24), calendar)


def test_open_label_is_hand_calculated_and_not_close_label(frozen_data):
    bars, calendar = frozen_data
    changed = deepcopy(bars)
    indexes = {bar["trade_date"]: index for index, bar in enumerate(changed)}
    for day, open_value, close_value in (
        ("2026-10-21", "10", "10"),
        ("2026-10-22", "10", "11"),
        ("2026-10-23", "9", "12"),
    ):
        changed[indexes[day]].update(open=open_value, close=close_value, high="13", low="8")
    frame, _, _ = labeled_frame(request_for((changed, calendar)))
    row = frame.loc["2026-10-21"]
    assert row.actual_return == pytest.approx(-0.1)
    assert row.target == 0
    assert row.entry_date.date() == date(2026, 10, 22)
    assert row.target_date.date() == date(2026, 10, 23)
    assert row.label_end_at == pd.Timestamp("2026-10-23T01:30:00Z")
    assert pd.isna(frame.loc["2026-10-22"].target)


def test_unmatured_and_late_fetched_labels_never_enter_training(frozen_data):
    cutoff = market_time(date(2026, 10, 23), time(9, 29))
    frame, sample, evidence = labeled_frame(request_for(frozen_data, training_cutoff_at=cutoff))
    assert frame.index[-1].date() == date(2026, 10, 22)
    assert sample.label_end_at.max() < cutoff
    assert sample.index[-1].date() <= date(2026, 10, 20)
    assert evidence["unavailable_input_bars"] == 1
    bars, calendar = frozen_data
    late = deepcopy(bars)
    late[-2]["fetched_at"] = TEST_NOW.isoformat()
    _, sample, _ = labeled_frame(request_for((late, calendar), training_cutoff_at=cutoff))
    assert date(2026, 10, 22) not in set(sample.target_date.dt.date)


def test_missing_session_not_compressed_and_action_quarantined(frozen_data):
    bars, calendar = frozen_data
    missing = [bar for bar in bars if bar["trade_date"] != "2026-10-22"]
    frame, sample, _ = labeled_frame(request_for((missing, calendar)))
    assert pd.isna(frame.loc["2026-10-21"].target)
    assert pd.Timestamp("2026-10-21") not in sample.index
    _, isolated, evidence = labeled_frame(
        request_for(frozen_data, corporate_action_dates=[date(2026, 10, 22)])
    )
    assert pd.Timestamp("2026-10-21") not in isolated.index
    assert evidence["corporate_action_quarantined_rows"] > 0


def test_purge_uses_market_timestamp_not_old_horizon_gap(frozen_data):
    _, sample, _ = labeled_frame(request_for(frozen_data))
    boundary = market_time(date(2026, 10, 22), time(15))
    purged = purge_before_time(sample, boundary)
    assert purged.label_end_at.max() == pd.Timestamp("2026-10-22T01:30:00Z")
    assert purged.index[-1].date() == date(2026, 10, 20)
    assert purged.label_end_at.max() < boundary


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_full_training_calibration_identity_and_prediction(
    frozen_data, algorithm, tmp_path, monkeypatch
):
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    request = request_for(frozen_data, algorithm)
    result = train_forward_model(request)
    assert result["label_version"] == "OPEN_TO_OPEN_H1_V1"
    assert result["target_semantics"] == "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
    assert result["hyperparameters"]["estimator"]["n_jobs"] == 1
    assert result["metrics"]["selection_frozen_before_test"]
    assert set(result["metrics"]["calibration_candidates"]) == {
        "RAW",
        "PLATT_20",
        "PLATT_60",
        "PLATT_120",
    }
    parts = result["training_range"]
    for left, right in (
        ("train", "calibration"),
        ("calibration", "validation"),
        ("validation", "test"),
    ):
        assert parts[left]["last_label_end_at"] < parts[right]["first_feature_at"]
    for fold in result["metrics"]["walk_forward"]:
        assert fold["test"]["last_feature_at"] < parts["test"]["first_feature_at"]
        assert fold["test"]["last_label_end_at"] < parts["test"]["first_feature_at"]
        assert fold["fit"]["last_label_end_at"] < fold["calibration_pool"]["first_feature_at"]
        assert (
            fold["calibration_pool"]["last_label_end_at"] < fold["validation"]["first_feature_at"]
        )
        assert fold["validation"]["last_label_end_at"] < fold["test"]["first_feature_at"]
    assert (
        sum(bin["count"] for bin in result["metrics"]["test"]["reliability_curve"])
        == parts["test"]["samples"]
    )
    assert result["artifact_sha256"]
    original_hashes = {path.name: path.read_bytes() for path in tmp_path.iterdir()}
    assert train_forward_model(request) == result
    assert {path.name: path.read_bytes() for path in tmp_path.iterdir()} == original_hashes
    bars, calendar = frozen_data
    prediction_request = ForwardPredictRequest(
        model_id=result["id"],
        symbol="510300",
        algorithm=algorithm,
        bars=bars,
        trading_dates=calendar,
        signal_date=date(2026, 10, 23),
        cutoff_at=TEST_NOW,
    )
    predicted = predict_forward(prediction_request)
    assert predicted["entry_date"] == "2026-10-26" and predicted["evaluation_date"] == "2026-10-27"
    assert 0 <= predicted["probability"] <= 1 and 0 <= predicted["raw_probability"] <= 1
    assert predicted["nature"] == "DEMO"
    assert predicted["model_role"] == "FORWARD_PRODUCTION"
    assert len(predicted["feature_values"]) == 20
    assert len(predicted["input_sha256"]) == 64
    assert predict_forward(prediction_request)["input_sha256"] == predicted["input_sha256"]
    # 工件训练时没有事件，新登记的执行日事件也必须阻止推理，不能等到再次训练。
    assert result["corporate_action_dates"] == []
    with pytest.raises(ValueError, match="CORPORATE_ACTION_AFFECTED"):
        predict_forward(
            prediction_request.model_copy(update={"corporate_action_dates": [date(2026, 10, 26)]})
        )
    future_event = predict_forward(
        prediction_request.model_copy(update={"corporate_action_dates": [date(2026, 11, 2)]})
    )
    assert future_event["probability"] == predicted["probability"]
    assert future_event["input_sha256"] != predicted["input_sha256"]
    assert future_event["corporate_action_dates"] == ["2026-11-02"]
    assert "CORPORATE_ACTION_COVERAGE_UNVERIFIED" in predicted["limitations"]
    json.dumps(result, allow_nan=False)
    with pytest.raises(ValueError, match="IDENTITY_MISMATCH"):
        predict_forward(prediction_request.model_copy(update={"symbol": "510500"}))
    with pytest.raises(ValueError, match="NOT_AVAILABLE"):
        predict_forward(
            prediction_request.model_copy(
                update={"cutoff_at": request.training_cutoff_at - timedelta(days=1)}
            )
        )
    with pytest.raises(ValueError, match="LATE_PREDICTION"):
        predict_forward(
            prediction_request.model_copy(
                update={"cutoff_at": market_time(date(2026, 10, 26), time(9, 30))}
            )
        )
    with monkeypatch.context() as context:
        times = iter([TEST_NOW, market_time(date(2026, 10, 26), time(9, 30))])

        class ClockCrossingOpen(datetime):
            """模拟推理开始于开盘前，但计算结束已经越过开盘。"""

            @classmethod
            def now(cls, tz=None):
                """返回两个真实流程节点对应的测试时刻。"""
                return next(times)

        context.setattr("etf_quant.forward_models.datetime", ClockCrossingOpen)
        with pytest.raises(ValueError, match="LATE_PREDICTION"):
            predict_forward(prediction_request)
    with pytest.raises(ValueError, match="DIGEST_MISMATCH"):
        (tmp_path / (result["id"] + ".joblib")).write_bytes(b"isolated corruption test")
        predict_forward(prediction_request)


def test_final_test_label_change_does_not_change_selected_scheme_or_probabilities(
    frozen_data, tmp_path, monkeypatch
):
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    request = request_for(frozen_data)
    before = train_forward_model(request)
    modified = deepcopy(request)
    modified.bars[-1].open *= Decimal("1.001")
    after = train_forward_model(modified)
    assert before["id"] != after["id"]
    assert before["calibration_scheme"] == after["calibration_scheme"]
    assert [row["probability"] for row in before["oos_predictions"]] == [
        row["probability"] for row in after["oos_predictions"]
    ]
    assert (
        before["oos_predictions"][-1]["actual_return"]
        != after["oos_predictions"][-1]["actual_return"]
    )


def test_short_calibration_candidates_skipped_and_old_label_rejected(
    frozen_data, tmp_path, monkeypatch
):
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    bars, calendar = frozen_data
    result = train_forward_model(request_for((bars[:400], calendar)))
    for name in ("PLATT_60", "PLATT_120"):
        candidate = result["metrics"]["calibration_candidates"][name]
        assert candidate["status"] == "SKIPPED"
        assert candidate["reason"] == "INSUFFICIENT_CALIBRATION_SAMPLES"
    with pytest.raises(ValidationError):
        request_for(frozen_data, label_version="CLOSE_TO_CLOSE")
    with pytest.raises(ValidationError):
        request_for(frozen_data, horizon=5)
    with pytest.raises(ValueError, match="MIXED"):
        mixed = deepcopy(bars)
        mixed[0]["nature"] = "REAL"
        labeled_frame(request_for((mixed, calendar)))


def test_reliability_counts_zero_one_and_empty_bins():
    result = probability_diagnostics([0, 1, 1, 0], [0, 1, 0.55, 0.55], [0, 1, 0.5, 0.5])
    assert sum(row["count"] for row in result["reliability_curve"]) == 4
    assert result["reliability_curve"][0]["count"] == 1
    assert result["reliability_curve"][-1]["count"] == 1
    assert result["reliability_curve"][1]["mean_probability"] is None
    assert result["raw_calibrated_difference"]["mean_absolute"] == pytest.approx(0.025)
