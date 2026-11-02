"""基金场外数据、时间隔离、端点、新闻证据风险测试。"""

import copy
import time
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from etf_quant.fund_data import FundFetchRequest, FundObservation, normalize_nav
from etf_quant.fund_models import (
    FundEvaluateRequest,
    FundPredictRequest,
    FundTrainRequest,
    evaluate_fund,
    fund_frame,
    labels,
    predict_funds,
    purged_split,
    train_funds,
)
from etf_quant.fund_news import (
    FundNewsPrepareRequest,
    FundNewsValidateRequest,
    prepare_fund_news,
    validate_fund_news,
)


def observations(code="460300", n=900, flat=False):
    """合成数据只在隔离测试中使用，绝不导入真实库或用于交付模型证据。"""
    dates = pd.bdate_range("2020-01-01", periods=n)
    prices = np.ones(n) if flat else np.exp(np.cumsum(np.random.default_rng(7).normal(0, 0.01, n)))
    return [
        FundObservation(
            fund_code=code,
            nav_date=day.date(),
            unit_nav=str(value),
            first_observed_at=datetime(2025, 1, 1, tzinfo=UTC),
            fetched_at=datetime(2025, 1, 1, tzinfo=UTC),
            source_record_id=f"test:{index}",
            revision_hash=f"{index:064x}",
        )
        for index, (day, value) in enumerate(zip(dates, prices, strict=True))
    ]


def test_leading_zero_and_invalid_fund():
    """字符串代码保留前导0，ETF代码不能进入基金适配。"""
    assert FundFetchRequest(fund_code="007169").fund_code == "007169"
    with pytest.raises(ValueError):
        FundFetchRequest(fund_code="510300")


def test_normalization_preserves_revision_and_null_publication():
    """时间未知保持null，非法净值被拒，分红相关突变只标警告不篡改数值。"""
    raw = [
        {"FSRQ": "2026-10-20", "DWJZ": "1.00", "LJJZ": "1.3"},
        {"FSRQ": "2026-10-21", "DWJZ": "0.85", "LJJZ": "1.3"},
        {"FSRQ": "2026-10-22", "DWJZ": "0"},
    ]
    rows, rejected = normalize_nav(raw, FundFetchRequest(fund_code="007169"), datetime.now(UTC))
    assert len(rows) == 2 and len(rejected) == 1
    assert rows[0].published_at is None
    assert rows[1].quality_status == "WARNING"
    revised = copy.deepcopy(raw)
    revised[0]["DWJZ"] = "1.01"
    newer, _ = normalize_nav(revised, FundFetchRequest(fund_code="007169"), datetime.now(UTC))
    assert newer[0].revision_hash != rows[0].revision_hash


@pytest.mark.parametrize("horizon", [20, 60])
def test_labels_never_cross_validation_or_test(horizon):
    """两个边界同时清除跨界成熟标签，最终测试不参与候选选择。"""
    frame = fund_frame(observations(), "460300")
    train, validation, test = purged_split(labels(frame, horizon))
    assert train.target_index.max() < validation.session_index.min()
    assert validation.target_index.max() < test.session_index.min()
    assert train.target_date.max() < validation.nav_date.min()


def test_mode_code_and_availability_rejected():
    """真实模型拒绝演示混入、其他基金与截止时尚不可得数据。"""
    data = observations(n=70)
    with pytest.raises(ValueError, match="MISMATCH"):
        fund_frame(data, "007169")
    with pytest.raises(ValueError, match="CUTOFF"):
        fund_frame(data, "460300", datetime(2024, 1, 1, tzinfo=UTC))
    data[0].nature = "DEMO"
    with pytest.raises(ValueError, match="DEMO"):
        fund_frame(data, "460300")


def test_immutable_model_endpoint_and_hash(tmp_path, monkeypatch):
    """重训另存工件；端点没有伪每日路径；hash被改时拒绝加载。"""
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    data = observations()
    trained = train_funds(FundTrainRequest(fund_code="460300", observations=data, horizons=[20]))
    model = trained["models"][0]
    assert model["adjustment_eligible"] is False
    request = FundPredictRequest(fund_code="460300", observations=data, models=[model])
    frozen = predict_funds(request)["forecasts"][0]
    assert frozen["capability"] == "RETURN_POINT" and frozen["daily_path"] is None
    assert frozen["points"][0]["probability_up"] is None
    assert frozen["points"][0]["target_date"] is None
    original = copy.deepcopy(frozen)
    trained_again = train_funds(
        FundTrainRequest(fund_code="460300", observations=data, horizons=[20])
    )
    assert trained_again["models"][0]["id"] != model["id"] and original == frozen
    request.models[0]["artifact_hash"] = "0" * 64
    with pytest.raises(ValueError, match="HASH"):
        predict_funds(request)


def test_evaluation_not_due_waiting_and_actual():
    """到期缺值不能当0，追加实际不修改冻结记录。"""
    frozen = {
        "id": "f1",
        "fund_code": "460300",
        "anchor_value": "1.0",
        "points": [{"horizon": 20, "target_date": "2026-10-20", "point_return": "0.10"}],
    }
    request = FundEvaluateRequest(
        forecast=frozen, observations=[], evaluated_at=datetime(2026, 10, 19, tzinfo=UTC)
    )
    assert evaluate_fund(request)["evaluation_status"] == "NOT_DUE"
    request.evaluated_at = datetime(2026, 10, 21, tzinfo=UTC)
    assert evaluate_fund(request)["evaluation_status"] == "WAITING_DATA"
    row = observations(n=1)[0]
    row.nav_date, row.unit_nav = date(2026, 10, 20), Decimal("1.3")
    request.observations = [row]
    result = evaluate_fund(request)
    assert result["actual_return"] == "0.3" and result["absolute_error"] == "0.20"
    assert "actual_return" not in frozen["points"][0]


@pytest.mark.parametrize("horizon", [20, 60])
def test_evaluate_uses_observed_nav_without_rewriting_frozen_prediction(horizon):
    """观察窗口计数去重，等待足够真实净值后追加终点并披露完整性限制。"""
    rows = observations(n=horizon + 1)
    anchor = rows[0].nav_date
    frozen = {
        "id": "observed",
        "fund_code": "460300",
        "anchor_date": str(anchor),
        "anchor_value": str(rows[0].unit_nav),
        "horizon_unit": "FUND_NAV_OBSERVATIONS",
        "points": [{"horizon": horizon, "target_date": None, "point_return": "0.10"}],
    }
    original = copy.deepcopy(frozen)
    now = datetime(2025, 1, 2, tzinfo=UTC)
    request = FundEvaluateRequest(forecast=frozen, observations=rows[:-1], evaluated_at=now)
    waiting = evaluate_fund(request)
    assert waiting["evaluation_status"] == "WAITING_OBSERVATIONS"
    assert waiting["observations_after_anchor"] == horizon - 1
    assert waiting["actual_value"] is None
    revised = rows[-1].model_copy(
        update={
            "unit_nav": Decimal(2),
            "revision_hash": "f" * 64,
            "first_observed_at": datetime(2025, 1, 1, 12, tzinfo=UTC),
            "fetched_at": datetime(2025, 1, 1, 12, tzinfo=UTC),
        }
    )
    request.observations = [*rows, revised]
    result = evaluate_fund(request)
    assert result["evaluation_status"] == "EVALUATED"
    assert result["actual_target_date"] == str(rows[-1].nav_date)
    assert result["target_date_origin"] == "OBSERVED_FUND_NAV"
    assert result["calendar_warning"] == "NAV_OBSERVATION_COMPLETENESS_UNVERIFIED"
    assert result["actual_data_version"] == revised.revision_hash
    assert frozen == original and frozen["points"][0]["target_date"] is None


def test_evaluation_excludes_other_fund_demo_rejected_and_future_observations():
    """其他基金、演示、拒收和评价时尚不可得的数据不能计入实际观测数。"""
    rows = observations(n=22)
    anchor = rows[0].nav_date
    frozen = {
        "id": "observed-filter",
        "fund_code": "460300",
        "anchor_date": str(anchor),
        "anchor_value": str(rows[0].unit_nav),
        "horizon_unit": "FUND_NAV_OBSERVATIONS",
        "points": [{"horizon": 20, "target_date": None, "point_return": "0"}],
    }
    rows[1].fund_code = "007169"
    rows[2].nature = "DEMO"
    rows[3].quality_status = "REJECTED"
    rows[4].first_observed_at = datetime(2025, 1, 3, tzinfo=UTC)
    rows[4].fetched_at = rows[4].first_observed_at
    result = evaluate_fund(
        FundEvaluateRequest(
            forecast=frozen, observations=rows, evaluated_at=datetime(2025, 1, 2, tzinfo=UTC)
        )
    )
    assert result["evaluation_status"] == "WAITING_OBSERVATIONS"
    assert result["observations_after_anchor"] == 17
    assert "actual_target_date" not in result


def news_request():
    """隔离资料验证用例，不是线上新闻。"""
    now = datetime.now(UTC)
    return FundNewsPrepareRequest(
        fund_code="008701",
        horizon=20,
        cutoff=now,
        documents=[
            {
                "id": "n1",
                "title": "黄金需求资料",
                "summary": "黄金储备有所增加。",
                "source_name": "测试",
                "source_url": "https://example.org/news",
                "fetched_at": now - timedelta(hours=1),
            }
        ],
    )


def test_news_java_shape_and_safe_url():
    """已有Java summary/fetched_at可直接接线，未来与恶意链接明确排除。"""
    request = news_request()
    prepared = prepare_fund_news(request)
    assert prepared["analysis_status"] == "READY" and prepared["sentiment_score"] is None
    assert prepared["input_snapshot_hash"] == prepared["input_snapshot_id"]
    request.documents[0].source_url = "javascript:alert(1)"
    assert prepare_fund_news(request)["analysis_status"] == "INSUFFICIENT_INPUT"


def test_news_unknown_source_quote_and_risk():
    """未知引用、拼接引文被拒，有风险不通过降级成无新闻绕过。"""
    request = news_request()
    prepared = prepare_fund_news(request)
    result = {
        "fund_code": "008701",
        "horizon": 20,
        "input_snapshot_id": prepared["input_snapshot_id"],
        "schema_version": prepared["schema_version"],
        "analysis_status": "VALID",
        "stance": "NEGATIVE",
        "sentiment_score": "-0.2",
        "facts": [{"source_ids": ["n1"], "quote": "黄金储备有所增加。"}],
        "inferences": [{"text": "可能已经计价", "basis_fact_indices": [0]}],
        "uncertainties": ["无法确认是否已定价"],
        "risk_flags": ["HIGH_RISK"],
    }
    validation = FundNewsValidateRequest(**request.model_dump(), analysis=result)
    assert validate_fund_news(validation)["adjustment_blocked_by_risk"] is True
    validation.analysis["facts"][0]["source_ids"] = ["not-input"]
    with pytest.raises(ValueError, match="SOURCE_ID"):
        validate_fund_news(validation)


def test_news_prepare_preserves_and_rejects_other_fund_owner():
    """免费准备接口与付费分析使用同一归属规则，黄金季报不能因利率一词进入债基。"""
    request = news_request()
    request.fund_code = "007169"
    request.documents[0].title = "华夏黄金基金季度报告"
    request.documents[0].excerpt = "黄金受到利率变化影响。"
    request.documents[0].metadata = {"fund_code": "008701"}
    prepared = prepare_fund_news(request)
    assert prepared["analysis_status"] == "INSUFFICIENT_INPUT"
    assert "FUND_SOURCE_SUBJECT_MISMATCH" in prepared["excluded"][0]["reason_codes"]
    assert prepared["fund_identity"]["asset_class"] == "BOND"
    request.documents[0].metadata = {}
    assert prepare_fund_news(request)["analysis_status"] == "INSUFFICIENT_INPUT"


def test_news_validate_rejects_correct_code_with_gold_mechanism():
    """免费校验入口也拒绝代码、引用、时间均正确但投资机制错误的输出。"""
    request = news_request()
    request.fund_code = "007169"
    request.documents[0].title = "债券市场利率观察"
    request.documents[0].excerpt = "利率下降可能影响债券价格。"
    prepared = prepare_fund_news(request)
    result = {
        "fund_code": "007169",
        "horizon": 20,
        "input_snapshot_id": prepared["input_snapshot_id"],
        "schema_version": prepared["schema_version"],
        "analysis_status": "VALID",
        "stance": "NEUTRAL",
        "sentiment_score": "0",
        "facts": [{"source_ids": ["n1"], "quote": "利率下降可能影响债券价格。"}],
        "inferences": [
            {"text": "本基金跟踪Au99.99，净值主要由金价决定。", "basis_fact_indices": [0]}
        ],
        "uncertainties": ["存在不确定性"],
        "risk_flags": [],
    }
    validation = FundNewsValidateRequest(**request.model_dump(), analysis=result)
    with pytest.raises(ValueError, match="FUND_MECHANISM_MISMATCH"):
        validate_fund_news(validation)
    validation.analysis["inferences"][0]["text"] = "本基金持有国开行债券，利率和金价可能同时变化。"
    assert validate_fund_news(validation)["analysis_status"] == "VALID"
    validation.analysis["schema_version"] = "fund-news-evidence-v1"
    with pytest.raises(ValueError, match="IDENTITY_MISMATCH"):
        validate_fund_news(validation)


def test_internal_fund_api_requires_auth(monkeypatch):
    """新增昂贵操作与既有内部API使用同一认证门禁。"""
    from etf_quant.api import app

    monkeypatch.setenv("ETF_INTERNAL_TOKEN", "t" * 40)
    with TestClient(app) as client:
        result = client.post(
            "/funds/models/train", json={"fund_code": "460300", "observations": []}
        )
    assert result.status_code == 401


def test_static_nav_script_identity_and_no_execution():
    """仅解析供应商特定JSON，代码身份错误或可执行语句不能成为净值。"""
    from etf_quant.fund_data import parse_nav_script

    request = FundFetchRequest(fund_code="007169")
    script = 'var fS_code="007169";var Data_netWorthTrend=[{"x":1609430400000,"y":1.1}];var Data_ACWorthTrend=[[1609430400000,1.2]];'
    assert parse_nav_script(script, request)[0]["FSRQ"] == "2021-01-01"
    with pytest.raises(ValueError, match="IDENTITY"):
        parse_nav_script(script.replace('"007169"', '"008701"'), request)
    with pytest.raises(ValueError):
        parse_nav_script(script.replace("1.1", "run_arbitrary_code()"), request)


def sleeping_fund_worker(connection, request):
    """可控故障模拟DNS无限等待，不创建HTTP或读取任何生产配置。"""
    time.sleep(30)


def test_fund_fetch_has_total_wall_clock_timeout(monkeypatch):
    """真正启动并终止隔离进程，证明网络卡死后调用有界结束且不造数据。"""
    from etf_quant import fund_data

    monkeypatch.setenv("ETF_FUND_FETCH_TIMEOUT_SECONDS", "1")
    monkeypatch.setattr(fund_data, "_fund_fetch_worker", sleeping_fund_worker)
    started = time.monotonic()
    with pytest.raises(ValueError, match="NAV_PROVIDER_TIMEOUT"):
        fund_data.fetch_fund_nav(FundFetchRequest(fund_code="007169"))
    assert time.monotonic() - started < 5


def test_retired_comparison_api_is_unavailable(monkeypatch):
    """已认证的内部调用也无法再执行定投比较。"""
    from etf_quant.api import app

    monkeypatch.setenv("ETF_INTERNAL_TOKEN", "t" * 40)
    with TestClient(app) as client:
        response = client.post(
            "/funds/comparisons/run", json={}, headers={"X-Internal-Token": "t" * 40}
        )
    assert response.status_code == 404
