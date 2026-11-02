from datetime import date

import pytest

from etf_quant.data import fetch_bars
from etf_quant.models import PredictRequest, TrainRequest, predict, train_model
from etf_quant.schemas import FetchRequest


@pytest.fixture(scope="module")
def bars():
    return fetch_bars(
        FetchRequest(
            symbol="510300", start=date(2021, 1, 1), end=date(2023, 12, 31), provider="DEMO"
        )
    )["bars"]


@pytest.mark.parametrize(
    "algorithm,horizon", [("lightgbm", 1), ("lightgbm", 5), ("xgboost", 1), ("xgboost", 5)]
)
def test_training_purge_calibration_and_oos(algorithm, horizon, bars, tmp_path, monkeypatch):
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    request = TrainRequest(symbol="510300", algorithm=algorithm, horizon=horizon, bars=bars)
    result = train_model(request)
    import json

    json.dumps(result, allow_nan=False)
    parts = result["training_range"]
    for left, right in (
        ("train", "calibration"),
        ("calibration", "validation"),
        ("validation", "test"),
    ):
        assert parts[left]["last_label_date"] < parts[right]["start"]
    for fold in result["metrics"]["walk_forward"]:
        assert fold["train_label_end"] < fold["calibration_start"]
        assert fold["calibration_label_end"] < fold["test_start"]
    assert 0 <= result["metrics"]["test"]["brier_score"] <= 1
    assert all(row["target_date"] > row["signal_date"] for row in result["oos_predictions"])
    online = predict(PredictRequest(model_id=result["id"], bars=bars))
    assert 0 <= online["probability"] <= 1
    production = result["production_training_range"]
    assert production["fit"]["last_label_date"] < production["calibration"]["start"]
    assert production["calibration"]["last_label_date"] == result["data_cutoff"]
    assert production["fit"]["last_label_date"] > parts["test"]["start"]
    assert online["fit_data_cutoff"] == production["fit"]["last_label_date"]
    assert online["model_role"] == "FORWARD_PRODUCTION"
    assert online["model_parameter_version"] == result["model_parameter_version"]
    assert 0 <= online["raw_score"] <= 1
    assert result["hyperparameters"]["estimator"]["n_jobs"] == 1
    assert train_model(request)["id"] == result["id"]
    if algorithm == "xgboost":
        # Windows无法读取部分Linux内存快照；删除测试副本后仍应通过官方JSON工件预测。
        (tmp_path / (result["id"] + ".joblib")).unlink()
        portable = predict(PredictRequest(model_id=result["id"], bars=bars))
        assert portable["probability"] == pytest.approx(online["probability"], abs=1e-12)
    with pytest.raises(ValueError, match="回填"):
        predict(PredictRequest(model_id=result["id"], bars=bars[:-10]))


def test_latest_bar_changes_cannot_change_earlier_oos_predictions(bars, tmp_path, monkeypatch):
    """生产拟合可以更新，严格留出测试中早先预测不能被最后一天的标签或价格改写。"""
    from copy import deepcopy

    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    before = train_model(TrainRequest(symbol="510300", algorithm="logistic", bars=bars))
    changed = deepcopy(bars)
    changed[-1]["close"] = str(float(changed[-1]["close"]) * 1.001)
    after = train_model(TrainRequest(symbol="510300", algorithm="logistic", bars=changed))
    assert before["id"] != after["id"]
    assert [row["probability"] for row in before["oos_predictions"]] == [
        row["probability"] for row in after["oos_predictions"]
    ]
    assert (
        before["oos_predictions"][-1]["actual_return"]
        != after["oos_predictions"][-1]["actual_return"]
    )


def test_old_feature_version_remains_readable(bars, tmp_path, monkeypatch):
    """旧12特征工件继续可复现，升级新20特征不能使历史冻结版本失效。"""
    from etf_quant.features import V1_FEATURES

    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    with monkeypatch.context() as context:
        context.setattr("etf_quant.models.FEATURE_VERSION", "ohlcv-v1")
        context.setattr("etf_quant.models.FEATURES", V1_FEATURES)
        legacy = train_model(TrainRequest(symbol="510300", algorithm="logistic", bars=bars))
    result = predict(PredictRequest(model_id=legacy["id"], bars=bars))
    assert result["feature_version"] == "ohlcv-v1"
    assert 0 <= result["probability"] <= 1


def test_effective_parameter_and_ohlc_change_create_new_model_identity(bars, tmp_path, monkeypatch):
    """模型版本同时覆盖配置、完整OHLC输入，旧版本元数据不能被新实验覆写。"""
    from copy import deepcopy

    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    request = TrainRequest(symbol="510300", algorithm="logistic", bars=bars)
    baseline = train_model(request)
    monkeypatch.setattr("etf_quant.models.TRAINING_VERSION", "identity-test-different-prescription")
    different_parameters = train_model(request)
    assert baseline["id"] != different_parameters["id"]
    changed = deepcopy(bars)
    changed[-1]["high"] = str(float(changed[-1]["high"]) * 1.001)
    different_ohlc = train_model(TrainRequest(symbol="510300", algorithm="logistic", bars=changed))
    assert different_parameters["id"] != different_ohlc["id"]
