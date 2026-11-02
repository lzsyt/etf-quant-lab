"""5有效净值观测分类的真实训练、时间清除、概率评价与旧回归隔离。"""

import copy
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from itertools import pairwise

import numpy as np
import pytest
from lightgbm import LGBMClassifier
from test_fund_algorithms import copy_artifact
from test_funds import observations
from xgboost import XGBClassifier

from etf_quant.fund_models import (
    SHORT_MODEL_FAMILIES,
    FundEvaluateRequest,
    FundPredictRequest,
    FundTrainRequest,
    evaluate_fund,
    fund_frame,
    labels,
    load_fund_model,
    predict_funds,
    probability_split,
    short_probability,
    train_funds,
)


@pytest.fixture(scope="module")
def short_models(tmp_path_factory):
    """合成净值仅验证模型软件行为，不当作真实基金研究结果。"""
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("ETF_MODEL_DIR", str(tmp_path_factory.mktemp("fund-short")))
        data = observations()
        records = train_funds(
            FundTrainRequest(fund_code="460300", observations=data, horizons=[5])
        )["models"]
        yield data, records


def test_short_labels_and_all_split_boundaries_use_five_observations():
    """短期不是自然日，四段相邻边界均清除标签交叠。"""
    data = observations()
    frame = fund_frame(data, "460300")
    labeled = labels(frame, 5)
    row = labeled.iloc[0]
    start = int(row.session_index)
    assert row.target_date == str(data[start + 5].nav_date)
    assert row.target_up == float(data[start + 5].unit_nav > data[start].unit_nav)
    parts = probability_split(labeled)
    for left, right in pairwise(parts):
        assert left.target_index.max() < right.session_index.min()
        assert left.target_date.max() < right.nav_date.min()


def test_short_dual_algorithm_real_models_and_probability_only(short_models):
    """两种真实分类器独立保存和加载，收益字段保持空，不能以正收益冒充概率。"""
    data, records = short_models
    assert {record["algorithm"] for record in records} == {"lightgbm", "xgboost"}
    forecasts = predict_funds(
        FundPredictRequest(fund_code="460300", observations=data, models=records)
    )["forecasts"]
    for record, forecast in zip(records, forecasts, strict=True):
        payload = load_fund_model(record, "460300")
        expected = XGBClassifier if record["algorithm"] == "xgboost" else LGBMClassifier
        assert isinstance(payload["model"], expected)
        assert forecast["capability"] == "PROBABILITY"
        assert forecast["probability_validation_status"] == "HISTORICAL_ONLY"
        assert forecast["target_version"] == "fund-nav-up-5-v1"
        assert forecast["model_family"] == SHORT_MODEL_FAMILIES[record["algorithm"]]
        point = forecast["points"][0]
        assert point["point_return"] is None
        assert Decimal(0) <= Decimal(point["probability_up"]) <= Decimal(1)
        computed = short_probability(
            payload["model"], fund_frame(data, "460300").iloc[-1:], payload["calibrator"]
        )[0]
        assert float(point["probability_up"]) == pytest.approx(computed)
        metrics = record["metrics"]
        assert metrics["selection_frozen_before_test"] is True
        assert metrics["test"]["samples"] == record["split"]["test_count"]
        assert len(metrics["test"]["reliability_curve"]) == 10
        assert metrics["test"]["observations_are_independent"] is False


def test_short_single_class_reports_failure_per_algorithm(tmp_path, monkeypatch):
    """单类拟合不能伪造0.5成功概率，两个算法分别保留缺口。"""
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    result = train_funds(
        FundTrainRequest(fund_code="460300", observations=observations(flat=True), horizons=[5])
    )
    assert len(result["models"]) == 2
    assert all(record["status"] == "INVALID_DATA" for record in result["models"])
    assert all("SINGLE_CLASS_FIT" in record["reason_codes"] for record in result["models"])
    assert not list(tmp_path.rglob("*.joblib"))


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_short_final_holdout_cannot_change_fit_or_calibration(short_models, algorithm):
    """修改最终测试尾部不会改变拟合、校准、验证候选或固定输入上的预测。"""
    data, records = short_models
    baseline = next(record for record in records if record["algorithm"] == algorithm)
    changed = [row.model_copy(deep=True) for row in data]
    for row in changed[-50:]:
        row.unit_nav *= Decimal("1.2")
    trained = train_funds(
        FundTrainRequest(
            fund_code="460300", observations=changed, horizons=[5], algorithms=[algorithm]
        )
    )["models"][0]
    assert (
        trained["metrics"]["calibration_candidates"]
        == baseline["metrics"]["calibration_candidates"]
    )
    assert trained["calibration_scheme"] == baseline["calibration_scheme"]
    assert trained["metrics"]["test"] != baseline["metrics"]["test"]
    first = load_fund_model(baseline, "460300")
    second = load_fund_model(trained, "460300")
    fixed = fund_frame(data, "460300").iloc[-50:]
    np.testing.assert_array_equal(
        short_probability(first["model"], fixed, first["calibrator"]),
        short_probability(second["model"], fixed, second["calibrator"]),
    )


def test_short_cannot_relabel_classifier_as_old_regression(short_models):
    """内外元数据同时改成旧回归身份，真实分类器仍被拒绝。"""
    _, records = short_models
    record = records[0]

    def relabel(payload, registered):
        """保留模型真实字节，仅篡改研究身份。"""
        for metadata in (payload["metadata"], registered):
            metadata.update(
                horizon=20,
                target_version="fund-nav-change-20-60-v1",
                model_family="FUND_NAV_LGBM_REGRESSOR",
                capability="RETURN_POINT",
            )

    with pytest.raises(ValueError, match="MODEL_ALGORITHM_MISMATCH"):
        load_fund_model(copy_artifact(record, relabel), "460300")


@pytest.mark.parametrize(
    "field,value",
    [
        ("fit_end_at", "2020-01-01T00:00:00+00:00"),
        ("code_version", "forged-version"),
        ("selection_policy", "forged-policy"),
        ("split", {}),
    ],
)
def test_short_outer_metadata_cannot_change_time_or_policy(short_models, field, value):
    """即便模型摘要正确，外层时间、代码或训练规则的单独改写也必须拒绝。"""
    _, records = short_models
    changed = copy.deepcopy(records[0])
    changed[field] = value
    with pytest.raises(ValueError, match="MODEL_METADATA_MISMATCH"):
        load_fund_model(changed, "460300")


def test_fund_cutoff_rejects_later_fetched_revision_with_old_first_observed():
    """截止前首次记录但截止后取得的修订不能参与当时特征，保留旧可见净值。"""
    rows = observations(n=70)
    cutoff = datetime(2025, 1, 2, tzinfo=UTC)
    revised = rows[-1].model_copy(
        update={
            "fetched_at": cutoff + timedelta(seconds=1),
            "unit_nav": Decimal(9),
            "revision_hash": "f" * 64,
        }
    )
    frame = fund_frame([*rows, revised], "460300", cutoff)
    assert frame.iloc[-1]["unit_nav"] == str(rows[-1].unit_nav)
    assert frame.iloc[-1]["revision_hash"] == rows[-1].revision_hash
    with pytest.raises(ValueError, match="NAV_NOT_AVAILABLE_AT_CUTOFF"):
        fund_frame([revised], "460300", cutoff)


@pytest.mark.parametrize("explicit_date", [False, True])
def test_short_evaluation_waits_for_actual_revision_fetch(explicit_date):
    """首次记录早于评价时点也不能提前使用尚未抓取的终点修订。"""
    rows = observations(n=6)
    now = datetime(2025, 1, 2, tzinfo=UTC)
    rows[-1].fetched_at = now + timedelta(seconds=1)
    forecast = {
        "id": "late-target-revision",
        "fund_code": "460300",
        "anchor_date": str(rows[0].nav_date),
        "anchor_value": str(rows[0].unit_nav),
        "horizon_unit": "FUND_NAV_OBSERVATIONS",
        "points": [
            {
                "horizon": 5,
                "target_date": str(rows[-1].nav_date) if explicit_date else None,
                "point_return": None,
                "probability_up": "0.8",
            }
        ],
    }
    result = evaluate_fund(
        FundEvaluateRequest(forecast=forecast, observations=rows, evaluated_at=now)
    )
    assert result["evaluation_status"] == (
        "WAITING_DATA" if explicit_date else "WAITING_OBSERVATIONS"
    )
    assert result["actual_return"] is None


def test_short_observed_evaluation_uses_brier_and_preserves_forecast():
    """五条后续净值到齐才评价，方向平盘归不上涨，不回写冻结预测。"""
    data = observations(n=6, flat=True)
    forecast = {
        "id": "short-frozen",
        "fund_code": "460300",
        "anchor_date": str(data[0].nav_date),
        "anchor_value": "1",
        "horizon_unit": "FUND_NAV_OBSERVATIONS",
        "points": [
            {"horizon": 5, "target_date": None, "point_return": None, "probability_up": "0.8"}
        ],
    }
    original = copy.deepcopy(forecast)
    request = FundEvaluateRequest(
        forecast=forecast, observations=data[:-1], evaluated_at=datetime(2025, 1, 2, tzinfo=UTC)
    )
    assert evaluate_fund(request)["evaluation_status"] == "WAITING_OBSERVATIONS"
    request.observations = data
    result = evaluate_fund(request)
    assert result["evaluation_status"] == "EVALUATED"
    assert result["actual_up"] is False
    assert Decimal(result["brier_score"]) == Decimal("0.64")
    assert "absolute_error" not in result
    assert forecast == original
