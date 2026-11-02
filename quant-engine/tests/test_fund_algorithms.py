"""基金双算法真实离线训练、原生工件加载、旧格式兼容和时间隔离回归。"""

import copy
import hashlib
import json
import uuid
from decimal import Decimal
from pathlib import Path

import joblib
import numpy as np
import pytest
from fastapi.testclient import TestClient
from lightgbm import LGBMRegressor
from test_funds import observations
from xgboost import XGBRegressor

from etf_quant.fund_models import (
    FEATURES,
    MODEL_FAMILIES,
    FundPredictRequest,
    FundTrainRequest,
    fund_frame,
    load_fund_model,
    new_regressor,
    predict_funds,
    train_funds,
)


@pytest.fixture(scope="module")
def trained_matrix(tmp_path_factory):
    """只在pytest临时目录真实训练四个小工件，不读取生产配置或真实市场数据。"""
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("ETF_MODEL_DIR", str(tmp_path_factory.mktemp("fund-algorithms")))
        data = observations()
        result = train_funds(
            FundTrainRequest(fund_code="460300", observations=data, code_version="test-only")
        )
        yield data, result["models"]


def copy_artifact(record, mutate):
    """在同一隔离模型目录生成可控篡改件，更新摘要以单独验证内部身份门禁。"""
    original_path = Path(record["artifact_path"])
    payload = joblib.load(original_path)
    registered = copy.deepcopy(record)
    mutate(payload, registered)
    path = original_path.with_name(f"fixture-{uuid.uuid4()}.joblib")
    joblib.dump(payload, path)
    registered["artifact_path"] = str(path)
    registered["artifact_hash"] = hashlib.sha256(path.read_bytes()).hexdigest()
    return registered


def test_default_matrix_trains_loads_and_predicts_four_independent_models(trained_matrix):
    """每个期限两算法各有真实工件，复用相同purge规则但分别计算验证与最终测试。"""
    data, records = trained_matrix
    assert {(row["horizon"], row["algorithm"]) for row in records} == {
        (20, "lightgbm"),
        (20, "xgboost"),
        (60, "lightgbm"),
        (60, "xgboost"),
    }
    assert len({row["id"] for row in records}) == 4
    assert len({row["artifact_hash"] for row in records}) == 4
    prediction = predict_funds(
        FundPredictRequest(fund_code="460300", observations=data, models=records)
    )
    assert len(prediction["forecasts"]) == 4
    latest = fund_frame(data, "460300").iloc[-1:][FEATURES]
    for record, forecast in zip(records, prediction["forecasts"], strict=True):
        payload = load_fund_model(record, "460300")
        model_type = XGBRegressor if record["algorithm"] == "xgboost" else LGBMRegressor
        assert isinstance(payload["model"], model_type)
        assert payload["model"].get_params()["n_jobs"] == 1
        assert forecast["model_version_id"] == record["id"]
        assert forecast["algorithm"] == record["algorithm"]
        assert forecast["model_family"] == MODEL_FAMILIES[record["algorithm"]]
        assert forecast["points"][0]["horizon"] == record["horizon"]
        assert float(forecast["points"][0]["point_return"]) == pytest.approx(
            float(payload["model"].predict(latest)[0])
        )
        assert forecast["points"][0]["probability_up"] is None
        split = record["split"]
        assert split["train_last_label_end"] < split["validation_start"]
        assert split["validation_last_label_end"] < split["test_start"]
        assert record["metrics"]["test"]["samples"] == split["test_count"]
        assert record["metrics"]["validation"]["samples"] == split["validation_count"]
        assert record["candidate_selected_on_validation"] == (
            float(record["metrics"]["validation"]["mae"])
            < min(
                float(record["metrics"]["validation"]["zero_return_mae"]),
                float(record["metrics"]["validation"]["training_mean_mae"]),
            )
        )
        raw_artifact = joblib.load(record["artifact_path"])
        if record["algorithm"] == "xgboost":
            assert "model" not in raw_artifact
            assert isinstance(raw_artifact["model_json"], bytes)
            native = json.loads(raw_artifact["model_json"])
            assert native["learner"]["objective"]["name"] == "reg:squarederror"
            # 使用原生JSON重新加载到全新实例，验证不依赖Python训练对象的pickle状态。
            portable = XGBRegressor(n_jobs=1, device="cpu")
            portable.load_model(bytearray(raw_artifact["model_json"]))
            np.testing.assert_array_equal(
                portable.predict(latest), payload["model"].predict(latest)
            )
    for horizon in (20, 60):
        matched = [row for row in records if row["horizon"] == horizon]
        assert matched[0]["split"] == matched[1]["split"]
        assert matched[0]["metrics"]["test"]["mae"] != matched[1]["metrics"]["test"]["mae"]


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_single_algorithm_http_train_and_predict(algorithm, trained_matrix, monkeypatch):
    """既有HTTP端点接受显式单算法，真实训练与预测响应均保留对应算法身份。"""
    from etf_quant.api import app

    data, _ = trained_matrix
    token = "isolated-fund-test-token-for-local-only"
    monkeypatch.setenv("ETF_INTERNAL_TOKEN", token)
    headers = {"X-Internal-Token": token}
    with TestClient(app) as client:
        trained = client.post(
            "/funds/models/train",
            json=FundTrainRequest(
                fund_code="460300", observations=data, algorithms=[algorithm]
            ).model_dump(mode="json"),
            headers=headers,
        )
        assert trained.status_code == 200
        records = trained.json()["models"]
        assert len(records) == 2
        assert {row["algorithm"] for row in records} == {algorithm}
        result = client.post(
            "/funds/models/predict",
            json=FundPredictRequest(
                fund_code="460300", observations=data, models=records
            ).model_dump(mode="json"),
            headers=headers,
        )
    assert result.status_code == 200
    assert len(result.json()["forecasts"]) == 2
    assert {row["algorithm"] for row in result.json()["forecasts"]} == {algorithm}


@pytest.mark.parametrize("algorithms", [[], ["unknown"], ["lightgbm", "lightgbm"]])
def test_training_rejects_invalid_algorithm_list(algorithms):
    """空列表、未知算法、重复算法不能静默回退或重复训练LightGBM。"""
    with pytest.raises(ValueError):
        FundTrainRequest(fund_code="460300", observations=[], algorithms=algorithms)
    with pytest.raises(ValueError, match="UNSUPPORTED_FUND_ALGORITHM"):
        new_regressor("unknown")


def test_insufficient_training_data_reports_each_requested_algorithm(tmp_path, monkeypatch):
    """短历史保留四个明确缺数据结果，不把任一算法的失败标成成功工件。"""
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    result = train_funds(FundTrainRequest(fund_code="460300", observations=observations(n=100)))
    assert len(result["models"]) == 4
    assert {row["status"] for row in result["models"]} == {"INVALID_DATA"}
    assert {row["algorithm"] for row in result["models"]} == {"lightgbm", "xgboost"}
    assert not list(tmp_path.rglob("*.joblib"))


def test_legacy_lightgbm_without_algorithm_is_compatible(trained_matrix):
    """历史工件和登记都无algorithm时仅识别为原LightGBM，不改写原文件。"""
    data, records = trained_matrix
    record = next(row for row in records if row["algorithm"] == "lightgbm")

    def remove_algorithm(payload, registered):
        """精确模拟旧版工件的缺失字段，模型本身仍为原LightGBM。"""
        payload["metadata"].pop("algorithm")
        registered.pop("algorithm")

    legacy = copy_artifact(record, remove_algorithm)
    before = Path(legacy["artifact_path"]).read_bytes()
    forecast = predict_funds(
        FundPredictRequest(fund_code="460300", observations=data, models=[legacy])
    )["forecasts"][0]
    assert forecast["algorithm"] == "lightgbm"
    assert forecast["model_family"] == "FUND_NAV_LGBM_REGRESSOR"
    assert Path(legacy["artifact_path"]).read_bytes() == before


@pytest.mark.parametrize(
    ("algorithm", "field", "value"),
    [
        ("lightgbm", "algorithm", "xgboost"),
        ("xgboost", "algorithm", "lightgbm"),
        ("xgboost", "algorithm", "unknown"),
        ("xgboost", "algorithm", None),
        ("xgboost", "model_family", "FUND_NAV_LGBM_REGRESSOR"),
        ("lightgbm", "model_family", "FUND_NAV_XGB_REGRESSOR"),
        ("xgboost", "horizon", 60),
        ("xgboost", "fund_code", "007169"),
    ],
)
def test_registered_algorithm_and_identity_tampering_is_rejected(
    trained_matrix, algorithm, field, value
):
    """登记的算法、工件族、资产和期限不能串用现有工件。"""
    _, records = trained_matrix
    record = copy.deepcopy(
        next(row for row in records if row["algorithm"] == algorithm and row["horizon"] == 20)
    )
    record[field] = value
    with pytest.raises(ValueError, match="ALGORITHM|IDENTITY"):
        load_fund_model(record, "460300")


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_consistently_relabelled_wrong_model_is_rejected(trained_matrix, algorithm):
    """即使内外元数据被一起改名并重算摘要，也不能把真实模型对象冒充另一算法。"""
    _, records = trained_matrix
    record = next(row for row in records if row["algorithm"] == algorithm)
    other = "xgboost" if algorithm == "lightgbm" else "lightgbm"

    def relabel(payload, registered):
        """保留真实模型字节，仅伪造内外一致的算法声明。"""
        for metadata in (payload["metadata"], registered):
            metadata["algorithm"] = other
            metadata["model_family"] = MODEL_FAMILIES[other]

    forged = copy_artifact(record, relabel)
    with pytest.raises(ValueError, match="MODEL_ALGORITHM_MISMATCH"):
        load_fund_model(forged, "460300")


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_final_holdout_change_cannot_influence_fitting_or_candidate_selection(
    trained_matrix, algorithm
):
    """只改最终测试尾部净值时，两算法训练预测和验证选型均不变，测试成绩单独变化。"""
    data, records = trained_matrix
    baseline = next(
        row for row in records if row["algorithm"] == algorithm and row["horizon"] == 20
    )
    changed = [row.model_copy(deep=True) for row in data]
    for row in changed[-50:]:
        row.unit_nav *= Decimal("1.2")
    trained = train_funds(
        FundTrainRequest(
            fund_code="460300", observations=changed, horizons=[20], algorithms=[algorithm]
        )
    )["models"][0]
    assert trained["split"] == baseline["split"]
    assert trained["metrics"]["validation"] == baseline["metrics"]["validation"]
    assert (
        trained["candidate_selected_on_validation"] == baseline["candidate_selected_on_validation"]
    )
    assert trained["metrics"]["test"] != baseline["metrics"]["test"]
    fixed_inputs = fund_frame(data, "460300").iloc[-50:][FEATURES]
    first_model = load_fund_model(baseline, "460300")["model"]
    changed_model = load_fund_model(trained, "460300")["model"]
    np.testing.assert_array_equal(
        first_model.predict(fixed_inputs), changed_model.predict(fixed_inputs)
    )
