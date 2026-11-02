"""ETF收盘幅度：十进制标签、长窗口清除、真实双算法、工件隔离及未来日历缺失。"""

import copy
import json
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal

import numpy as np
import pytest
from fastapi.testclient import TestClient

from etf_quant.calendar import official_calendar, official_session
from etf_quant.close_models import (
    ClosePredictRequest,
    CloseTrainRequest,
    close_artifact_root,
    close_split,
    close_target_date,
    labeled_close_frame,
    load_close_model,
    predict_close,
    train_close_model,
)
from etf_quant.data import fetch_bars
from etf_quant.features import FEATURES
from etf_quant.forward_models import market_time
from etf_quant.schemas import FetchRequest

NOW = datetime(2026, 10, 30, 12, tzinfo=UTC)


@pytest.fixture(autouse=True)
def close_clock(monkeypatch):
    """固定模型时钟，不把未来真实日期变化当测试故障。"""

    class Clock(datetime):
        """仅影响当前模块测试。"""

        @classmethod
        def now(cls, tz=None):
            """返回固定预测时间。"""
            return NOW

    monkeypatch.setattr("etf_quant.close_models.datetime", Clock)


@pytest.fixture(scope="module")
def close_data():
    """DEMO数据只生成隔离工件，不进入真实行情库。"""
    bars = fetch_bars(
        FetchRequest(
            symbol="510300", start=date(2021, 1, 1), end=date(2026, 10, 30), provider="DEMO"
        )
    )["bars"]
    bars = [
        bar for bar in bars if official_session(date.fromisoformat(bar["trade_date"])) is not False
    ]
    for bar in bars:
        bar["fetched_at"] = market_time(
            date.fromisoformat(bar["trade_date"]), time(15, 20)
        ).isoformat()
    dates = sorted(
        {date.fromisoformat(bar["trade_date"]) for bar in bars}
        | {date.fromisoformat(day) for day in official_calendar()["dates"]}
    )
    return bars, dates


def train_request(data, algorithm="lightgbm", horizon=20, **overrides):
    """构造与Java相同的独立收盘模型请求。"""
    bars, dates = data
    return CloseTrainRequest(
        **{
            "symbol": "510300",
            "algorithm": algorithm,
            "horizon": horizon,
            "bars": bars,
            "trading_dates": dates,
            "training_cutoff_at": NOW,
            "code_version": "a" * 40,
            **overrides,
        }
    )


def predict_request(data, record, **overrides):
    """在同一收盘后的截止生成研究结果。"""
    bars, dates = data
    return ClosePredictRequest(
        **{
            "symbol": "510300",
            "algorithm": record["algorithm"],
            "horizon": record["horizon"],
            "bars": bars,
            "trading_dates": dates,
            "model_id": record["id"],
            "signal_date": date(2026, 10, 30),
            "cutoff_at": NOW,
            **overrides,
        }
    )


@pytest.fixture(scope="module")
def close_records(close_data, tmp_path_factory):
    """四个真实回归器仅用明确标记的测试样本，结果不代表基金预测效果。"""
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("ETF_MODEL_DIR", str(tmp_path_factory.mktemp("close-models")))

        # module fixture先于function时钟执行，因此工件完成时间采用隔离类固定。
        class Clock(datetime):
            """工件与推理共享固定时点。"""

            @classmethod
            def now(cls, tz=None):
                """返回固定可用时间。"""
                return NOW

        patch.setattr("etf_quant.close_models.datetime", Clock)
        records = [
            train_close_model(train_request(close_data, algorithm, horizon))
            for horizon in (20, 60)
            for algorithm in ("lightgbm", "xgboost")
        ]
        yield records


@pytest.mark.parametrize("horizon", [20, 60])
def test_decimal_close_labels_and_time_purge(close_data, horizon):
    """端点按交易日和Decimal手算，训练/验证标签均不能越过下一段。"""
    frame, sample, _ = labeled_close_frame(train_request(close_data, horizon=horizon))
    row = sample.iloc[0]
    prices = {bar["trade_date"]: Decimal(bar["close"]) for bar in close_data[0]}
    expected = prices[str(row.target_date.date())] / prices[str(row.name.date())] - 1
    assert row.actual_return == pytest.approx(float(expected))
    assert row.target_date == frame.index[frame.index.get_loc(row.name) + horizon]
    train, validation, test = close_split(sample)
    assert train.label_end_at.max() < validation.feature_at.min()
    assert validation.label_end_at.max() < test.feature_at.min()


def test_missing_session_does_not_compress_or_cross_label_window(close_data):
    """任何窗口内缺失收盘会排除受影响标签，下一可用记录不冒充下一交易日。"""
    bars, calendar = close_data
    gap = "2026-09-01"
    changed = [bar for bar in bars if bar["trade_date"] != gap]
    frame, sample, _ = labeled_close_frame(train_request((changed, calendar)))
    assert frame.loc[gap, "close"] != frame.loc[gap, "close"]
    for _, row in sample.iterrows():
        assert not row.name.date() <= date.fromisoformat(gap) <= row.target_date.date()


def test_future_and_late_fetched_prices_not_used(close_data):
    """未收盘或截止后取得的尾部行情不能进入训练标签和特征。"""
    cutoff = market_time(date(2026, 10, 30), time(14, 59))
    frame, sample, evidence = labeled_close_frame(
        train_request(close_data, training_cutoff_at=cutoff)
    )
    assert frame.index[-1].date() == date(2026, 10, 29)
    assert sample.label_available_at.max() <= cutoff
    assert evidence["unavailable_input_bars"] == 1
    bars = copy.deepcopy(close_data[0])
    bars[-2]["fetched_at"] = NOW.isoformat()
    _, sample, _ = labeled_close_frame(
        train_request((bars, close_data[1]), training_cutoff_at=cutoff)
    )
    assert date(2026, 10, 29) not in set(sample.target_date.dt.date)


def test_dual_regression_models_metadata_and_current_inference(close_data, close_records):
    """四组合有独立真实工件，预测只填涨跌幅，不生成虚假概率。"""
    assert len({record["id"] for record in close_records}) == 4
    for record in close_records:
        assert record["model_role"] == "CLOSE_RETURN_RESEARCH"
        assert record["label_version"] == "CLOSE_TO_CLOSE_H20_H60_V1"
        assert record["metrics"]["selection_frozen_before_test"] is True
        assert record["metrics"]["test"]["samples"] == record["training_range"]["test"]["samples"]
        forecast = predict_close(predict_request(close_data, record))
        assert forecast["model_id"] == record["id"]
        assert Decimal(forecast["point_return"]).is_finite()
        assert forecast["probability"] is None
        assert forecast["anchor_value"] == close_data[0][-1]["close"]
        assert forecast["calendar_status"] == (
            "CONFIRMED" if record["horizon"] == 20 else "UNCONFIRMED"
        )
        assert (forecast["target_date"] is None) == (record["horizon"] == 60)
        if record["horizon"] == 60:
            assert "FUTURE_CALENDAR_UNAVAILABLE" in forecast["reason_codes"]
        else:
            target = close_target_date(date(2026, 10, 30), 20, close_data[1])
            assert forecast["target_date"] == str(target)
        _, model = load_close_model(record["id"])
        assert model.get_params()["n_jobs"] == 1
        # 同一请求严格复用原有工件，不覆盖原字节或重新拟合。
        again = train_close_model(train_request(close_data, record["algorithm"], record["horizon"]))
        assert again == record


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_close_final_holdout_does_not_change_fit_or_validation(
    close_data, close_records, algorithm
):
    """只改变测试尾部价格时，固定输入预测及验证选型保持完全一致。"""
    baseline = next(
        record
        for record in close_records
        if record["algorithm"] == algorithm and record["horizon"] == 20
    )
    bars = copy.deepcopy(close_data[0])
    for bar in bars[-40:]:
        for key in ("open", "high", "low", "close"):
            bar[key] = str(Decimal(bar[key]) * Decimal("1.005"))
    changed = train_close_model(train_request((bars, close_data[1]), algorithm))
    assert changed["metrics"]["validation"] == baseline["metrics"]["validation"]
    assert (
        changed["candidate_selected_on_validation"] == baseline["candidate_selected_on_validation"]
    )
    assert changed["metrics"]["test"] != baseline["metrics"]["test"]
    frame, _, _ = labeled_close_frame(train_request(close_data))
    fixed = frame.dropna(subset=FEATURES).iloc[-50:][FEATURES]
    _, first = load_close_model(baseline["id"])
    _, second = load_close_model(changed["id"])
    np.testing.assert_array_equal(first.predict(fixed), second.predict(fixed))


def test_close_identity_and_artifact_tampering_rejected(close_data, close_records):
    """错算法、期限、字段或修改工件摘要不能读成另一个模型。"""
    baseline = close_records[0]
    for changes in ({"algorithm": "xgboost"}, {"horizon": 60}, {"symbol": "510500"}):
        with pytest.raises(ValueError, match="MODEL_IDENTITY"):
            predict_close(predict_request(close_data, baseline, **changes))
    with pytest.raises(ValueError):
        predict_request(close_data, baseline, label_version="OPEN_TO_OPEN_H1_V1")
    path = close_artifact_root() / f"{baseline['id']}.json"
    original = path.read_text()
    metadata = json.loads(original)
    metadata["label_version"] = "OPEN_TO_OPEN_H1_V1"
    try:
        path.write_text(json.dumps(metadata))
        with pytest.raises(ValueError, match="MODEL_IDENTITY"):
            load_close_model(baseline["id"])
    finally:
        path.write_text(original)
    metadata = json.loads(original)
    metadata["artifact_sha256"][f"{baseline['id']}.joblib"] = "0" * 64
    try:
        path.write_text(json.dumps(metadata))
        with pytest.raises(ValueError, match="DIGEST"):
            load_close_model(baseline["id"])
    finally:
        path.write_text(original)


@pytest.mark.parametrize(
    "field,value",
    [
        ("data_cutoff", "2026-10-31"),
        ("fit_data_cutoff", "2026-10-31"),
        ("training_version", "forged-version"),
        ("source_code_sha256", {}),
        ("hyperparameters", {}),
    ],
)
def test_close_outer_metadata_cannot_bypass_time_or_policy(close_records, field, value):
    """工件摘要不变时，修改JSON的新鲜度、训练规则或代码来源仍必须拒绝。"""
    record = close_records[0]
    path = close_artifact_root() / f"{record['id']}.json"
    original = path.read_text()
    changed = json.loads(original)
    changed[field] = value
    try:
        path.write_text(json.dumps(changed))
        with pytest.raises(ValueError, match="MODEL_METADATA_MISMATCH"):
            load_close_model(record["id"])
    finally:
        path.write_text(original)


def test_close_inference_rejects_wrong_cutoff_and_known_corporate_action(close_data, close_records):
    """拟合后可得时间不可倒置，窗口内公司行动不能当普通价格趋势。"""
    record = close_records[0]
    with pytest.raises(ValueError, match="MODEL_NOT_AVAILABLE"):
        predict_close(predict_request(close_data, record, cutoff_at=NOW - timedelta(seconds=1)))
    with pytest.raises(ValueError, match="CUTOFF_CANNOT"):
        predict_close(predict_request(close_data, record, cutoff_at=NOW + timedelta(seconds=1)))
    with pytest.raises(ValueError, match="CORPORATE_ACTION"):
        predict_close(
            predict_request(close_data, record, corporate_action_dates=[date(2026, 10, 30)])
        )


@pytest.mark.parametrize("value", [-1.01, float("nan"), float("inf")])
def test_close_invalid_regression_output_is_not_frozen(
    close_data, close_records, monkeypatch, value
):
    """模型异常或超出价格收益下界时拒绝，不能冻结小于负100%的伪价格收益。"""
    record = close_records[0]

    class InvalidModel:
        """仅替换最终模型输出，前置身份和输入仍使用真实隔离工件。"""

        def predict(self, frame):
            """返回待测试的非法输出。"""
            return np.full(len(frame), value)

    monkeypatch.setattr(
        "etf_quant.close_models.load_close_model", lambda _: (record, InvalidModel())
    )
    with pytest.raises(ValueError, match="INVALID_MODEL_OUTPUT"):
        predict_close(predict_request(close_data, record))


def test_close_http_contract_and_auth(close_data, close_records, monkeypatch):
    """内部HTTP端点保留令牌门禁，训练和推理使用同一正式请求合同。"""
    from etf_quant.api import app

    monkeypatch.setenv("ETF_INTERNAL_TOKEN", "isolated-local-close-model-fixture-token")
    headers = {"X-Internal-Token": "isolated-local-close-model-fixture-token"}
    with TestClient(app) as client:
        request = train_request(close_data).model_dump(mode="json")
        assert client.post("/close/models/train", json=request).status_code == 401
        result = client.post("/close/models/train", json=request, headers=headers)
        assert result.status_code == 200
        result = client.post(
            "/close/models/predict",
            json=predict_request(close_data, close_records[0]).model_dump(mode="json"),
            headers=headers,
        )
        assert result.status_code == 200
        assert result.json()["capability"] == "RETURN_POINT"
