"""黄金模型时间边界与价格语义回归；合成序列只在隔离测试目录存在。"""

import hashlib
import io
import json
import shutil
import subprocess
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import httpx
import joblib
import numpy as np
import pytest
import xgboost as xgb
from lightgbm import LGBMClassifier, LGBMRegressor
from openpyxl import Workbook
from pydantic import ValidationError

from etf_quant.gold_data import (
    BENCHMARK,
    GoldObservation,
    GoldSourceFailure,
    GoldSyncRequest,
    asof_observation,
    estimated_target,
    fetch_sge,
    make_observation,
    parse_fred,
    parse_sge,
    parse_world_bank,
    source_health,
    sync_gold,
)
from etf_quant.gold_models import (
    FEATURES,
    GoldPredictRequest,
    GoldTrainRequest,
    benchmark_frame,
    build_evaluation,
    fit_bundle,
    infer,
    labeled_frame,
    predict_gold,
    restore_bundle,
    temporal_split,
    train_gold,
    validate_input_times,
)


@pytest.fixture(scope="module")
def synthetic_observations():
    """固定种子制造涨跌阶段，仅供时序分割测试，不进入生产真实数据集。"""
    rng = np.random.default_rng(410)
    prices = 300 * np.exp(np.cumsum(rng.normal(0.00015, 0.012, 2300)))
    days, day = [], date(2015, 1, 1)
    while len(days) < len(prices):
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    now = datetime(2026, 10, 26, 12, tzinfo=UTC)
    return [
        GoldObservation.model_validate(
            make_observation(
                BENCHMARK, day, str(round(price, 2)), now, {"synthetic_test_only": True}
            )
        )
        for day, price in zip(days, prices)
    ]


@pytest.fixture(scope="module")
def trained_gold_models(tmp_path_factory, synthetic_observations):
    """仅在测试目录用真实双算法训练四个工件，复用昂贵准备而不触达业务目录。"""
    directory = tmp_path_factory.mktemp("gold-dual-algorithms")
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("ETF_MODEL_DIR", str(directory))
        outcome = train_gold(
            GoldTrainRequest(
                observations=synthetic_observations,
                fit_at=datetime(2026, 10, 26, 12, tzinfo=UTC),
                code_version="SYNTHETIC_TEST_ONLY",
            )
        )
    assert outcome["status"] == "SUCCEEDED"
    return directory, outcome["models"]


@pytest.fixture
def live_test_observations(synthetic_observations):
    """合成预测输入截止昨天，避免测试运行在日盘收盘前产生时区不确定性。"""
    now = datetime.now(UTC)
    delta = now.date() - timedelta(days=1) - synthetic_observations[-1].observation_date
    return [
        row.model_copy(
            update={
                "observation_date": row.observation_date + delta,
                "fetched_at": now - timedelta(seconds=1),
            }
        )
        for row in synthetic_observations
    ]


def test_official_sge_field_is_close_and_anomaly_preserved():
    """官方K线位置按其源码固定；矛盾OHLC保留原收盘和异常，不能悄悄改均价。"""
    rows = parse_sge(
        {
            "time": [
                ["2026-10-23", 874.5, 864.0, 856.01, 883.98],
                ["2017-02-10", 276.2, 272.83, 272.95, 276.69],
            ]
        },
        datetime(2026, 10, 26, tzinfo=UTC),
    )
    assert rows[-1]["value"] == "864.0"
    assert rows[-1]["unit"] == "CNY/g"
    assert rows[0]["value"] == "272.83"
    assert rows[0]["details"]["ohlc_consistency"] == "INCONSISTENT_SOURCE_ROW"
    assert rows[0]["available_at"] is None


@pytest.mark.parametrize(
    "row",
    [
        ["2026-10-27", 1, 1, 1, 1],
        ["2026-10-23", 1, 0, 1, 1],
        ["2026-10-23", 1, "NaN", 1, 1],
        ["2026-10-23", 1, 1],
    ],
)
def test_source_rejects_invalid_future_and_empty_values(row):
    """未来、空值和格式变更不能伪装有效数据。"""
    with pytest.raises(ValueError):
        parse_sge({"time": [row]}, datetime(2026, 10, 26, tzinfo=UTC))


def test_fred_negative_real_yield_valid_and_fx_direction():
    """实际利率允许负值，人民币汇率按CNY/USD且无统计月提前生效。"""
    now = datetime(2026, 10, 26, tzinfo=UTC)
    rows = parse_fred(
        "observation_date,DFII10\n2020-08-01,-1.0\n2020-08-02,.\n", "FRED_DFII10", now
    )
    assert len(rows) == 1 and rows[0]["value"] == "-1.0"
    assert rows[0]["available_at"] is None
    fx = parse_fred("observation_date,DEXCHUS\n2026-10-22,6.7\n", "FRED_DEXCHUS", now)[0]
    assert fx["unit"] == "CNY/USD"
    assert fx["details"]["historical_model_eligible"] is False


def test_world_bank_monthly_units_and_release_not_backdated():
    """隔离Excel样本验证月均口径、期末观察日及历史公开时间未知。"""
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "Monthly Prices"
    sheet.append(["World Bank Commodity Price Data (The Pink Sheet)"])
    sheet.append(["monthly prices in nominal US dollars"])
    sheet.append(["test fixture, never imported"])
    sheet.append(["Updated on October 02, 2026"])
    sheet.append([None, "Gold"])
    sheet.append([None, "($/troy oz)"])
    sheet.append(["2026M09", 4000])
    notes = workbook.create_sheet("Description")
    notes.append(["Gold, spot average of daily rates, from June 2025", "TEST ONLY"])
    stream = io.BytesIO()
    workbook.save(stream)
    rows = parse_world_bank(
        stream.getvalue(),
        datetime(2026, 10, 26, tzinfo=UTC),
        "https://thedocs.worldbank.org/test.xlsx",
    )
    assert rows[0]["observation_date"] == "2026-09-30"
    assert rows[0]["unit"] == "USD/troy_oz"
    assert rows[0]["available_at"] is None
    assert rows[0]["details"]["frequency"] == "MONTHLY"
    assert rows[0]["details"]["historical_model_eligible"] is False


def test_macro_effective_time_and_dst_never_backfills():
    """美国夏令时16点为UTC20点，国内当日收盘前不能看到同日美国数据。"""
    row = make_observation(
        "FRED_DFII10", date(2026, 10, 22), "2.64", datetime(2026, 10, 23, 1, tzinfo=UTC)
    )
    row["available_at"] = "2026-10-22T16:00:00-04:00"
    assert (
        asof_observation([row], "FRED_DFII10", datetime(2026, 10, 22, 7, 30, tzinfo=UTC), 7) is None
    )
    assert (
        asof_observation([row], "FRED_DFII10", datetime(2026, 10, 23, 2, tzinfo=UTC), 7) is not None
    )
    row["available_at"] = None
    assert asof_observation([row], "FRED_DFII10", datetime(2026, 10, 22, 23, tzinfo=UTC), 7) is None
    assert asof_observation([row], "FRED_DFII10", datetime(2026, 11, 1, tzinfo=UTC), 7) is None


def test_source_states_separate_failure_empty_stale_partial():
    """来源状态不能把失败或过期改写为今日可用。"""
    now = datetime(2026, 10, 26, tzinfo=UTC)
    assert source_health(BENCHMARK, [], now)["status"] == "EMPTY"
    assert source_health(BENCHMARK, [], now, "Timeout")["status"] == "FAILED"
    rows = [make_observation(BENCHMARK, date(2026, 10, 1), 900, now)]
    assert source_health(BENCHMARK, rows, now)["status"] == "STALE"
    rows = [make_observation(BENCHMARK, date(2026, 10, 26), 900, now)]
    assert source_health(BENCHMARK, rows, now)["status"] == "PARTIAL_COVERAGE"


@pytest.mark.parametrize("horizon", [63, 126])
def test_labels_use_trading_sessions_and_purge_both_boundaries(synthetic_observations, horizon):
    """训练结束标签严格早于校准首日，校准结束标签严格早于拟合时点。"""
    frame = benchmark_frame(synthetic_observations)
    labeled = labeled_frame(frame, horizon)
    train, calibration = temporal_split(labeled, 1800)
    assert train["target_index"].max() < calibration["session_index"].min()
    assert calibration["target_index"].max() < 1800
    first = labeled.iloc[0]
    target = frame.iloc[int(first["session_index"]) + horizon]
    assert first["target_date"] == target["date"]
    assert first["target_return"] == pytest.approx(target["close"] / first["close"] - 1)
    assert (first["target_date"] - first["date"]).days > horizon


def test_future_price_does_not_change_historical_features(synthetic_observations):
    """未来异常价格不能反向污染任何已算特征。"""
    original = benchmark_frame(synthetic_observations)
    changed = list(synthetic_observations)
    changed[-1] = changed[-1].model_copy(update={"value": Decimal(99999)})
    revised = benchmark_frame(changed)
    assert np.array_equal(
        original.iloc[126:-1][FEATURES].to_numpy(), revised.iloc[126:-1][FEATURES].to_numpy()
    )


def test_csv_unverified_and_macro_excluded(synthetic_observations):
    """没有人工核验的CSV与宏观观察不能扩大真实黄金训练集。"""
    row = synthetic_observations[0].model_copy(update={"quality": "CSV_UNVERIFIED"})
    macro = synthetic_observations[1].model_copy(update={"series_id": "FRED_DEXCHUS"})
    assert benchmark_frame([row, macro]).empty


def test_final_holdout_and_independent_window_report(synthetic_observations):
    """最终留出与滚动段不重叠，非重叠样本不能被日标签数夸大。"""
    metrics, replay = build_evaluation(benchmark_frame(synthetic_observations), 126)
    assert len(metrics["rolling"]) >= 2
    final = metrics["final_test"]
    assert final["test_samples"] == 504
    assert final["nonoverlap"]["samples"] == 4
    assert all(fold["test_end"] < final["test_start"] for fold in metrics["rolling"])
    assert all(row["nature"] == "HISTORICAL_REPLAY_CURRENT_VINTAGE" for row in replay)
    assert metrics["pit_status"] == "CURRENT_VINTAGE_REPLAY_NOT_STRICT_PIT"


def test_insufficient_training_and_stale_prediction_blocked(synthetic_observations):
    """不满足门槛时无虚构模型或今日预测。"""
    assert train_gold(GoldTrainRequest(observations=synthetic_observations[:50]))["models"] == []
    outcome = predict_gold(GoldPredictRequest(observations=synthetic_observations))
    assert outcome["status"] == "BLOCKED" and outcome["predictions"] == []


def test_future_target_explicitly_estimated_not_natural_days():
    """未来只给工作日估计值，实际日期须由后续上金所会话成熟确认。"""
    reference = date(2026, 10, 26)
    assert (estimated_target(reference, 63) - reference).days == 87
    assert (estimated_target(reference, 126) - reference).days == 176


def test_all_input_times_checked_and_shanghai_close_boundary(synthetic_observations):
    """非末行未来获得时间也须拒绝；北京时间15:30以前不是当日收盘。"""
    now = datetime(2026, 10, 26, 8, tzinfo=UTC)
    rows = [
        synthetic_observations[0].model_copy(update={"fetched_at": now + timedelta(seconds=1)}),
        synthetic_observations[-1].model_copy(update={"fetched_at": now - timedelta(days=1)}),
    ]
    with pytest.raises(ValueError, match="尚未可用"):
        validate_input_times(rows, now)
    row = synthetic_observations[0].model_copy(
        update={"observation_date": date(2026, 10, 26), "fetched_at": now - timedelta(days=1)}
    )
    with pytest.raises(ValueError, match="尚未完成"):
        validate_input_times([row], datetime(2026, 10, 26, 7, 29, tzinfo=UTC))
    validate_input_times([row], datetime(2026, 10, 26, 7, 30, tzinfo=UTC))


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_real_artifact_hash_freeze_and_tamper_rejection(
    tmp_path, monkeypatch, trained_gold_models, live_test_observations, algorithm
):
    """隔离目录实际写工件再验摘要；篡改阻止推理，不覆盖原工件。"""
    directory, models = trained_gold_models
    shutil.copytree(directory, tmp_path, dirs_exist_ok=True)
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    model = next(record for record in models if record["algorithm"] == algorithm)
    manifest = json.loads((tmp_path / (model["model_id"] + ".json")).read_text())
    assert manifest["metadata"]["input_sha256"]
    assert manifest["metadata"]["business_domain"] == "GOLD_RESEARCH"
    path = tmp_path / model["artifact"]
    path.write_bytes(path.read_bytes() + b"tampered")
    with pytest.raises(ValueError, match="摘要不一致"):
        predict_gold(GoldPredictRequest(observations=live_test_observations, models=[model]))


def test_dual_algorithm_artifacts_roundtrip_and_independent_evaluation(
    monkeypatch, trained_gold_models, live_test_observations
):
    """四个真实工件各自完成三折、最终留出及校准；推理结果来自本算法的保存工件。"""
    directory, models = trained_gold_models
    monkeypatch.setenv("ETF_MODEL_DIR", str(directory))
    assert {(m["algorithm"], m["horizon"]) for m in models} == {
        (algorithm, horizon) for algorithm in ("lightgbm", "xgboost") for horizon in (63, 126)
    }
    assert len({m["model_id"] for m in models}) == 4
    outcome = predict_gold(GoldPredictRequest(observations=live_test_observations, models=models))
    assert outcome["status"] == "SUCCEEDED"
    predictions = {row["model_id"]: row for row in outcome["predictions"]}
    frame = benchmark_frame(live_test_observations).iloc[[-1]]
    for record in models:
        algorithm = record["algorithm"]
        metadata = record["metadata"]
        metrics = record["metrics"]
        assert (
            metadata["algorithm"]
            == {"lightgbm": "LightGBM_small_fixed", "xgboost": "XGBoost_small_fixed"}[algorithm]
        )
        assert metrics["algorithm"] == algorithm
        assert len(metrics["rolling"]) == 3
        assert metrics["final_test"]["test_samples"] == 504
        for fold in metrics["rolling"] + [metrics["final_test"]]:
            assert fold["algorithm"] == algorithm
            assert fold["train_label_end"] < fold["calibration_start"]
            assert fold["calibration_label_end"] < fold["test_start"]
        assert all(
            fold["test_end"] < metrics["final_test"]["test_start"] for fold in metrics["rolling"]
        )
        saved = joblib.load(directory / record["artifact"])["bundle"]
        if algorithm == "xgboost":
            assert "median_model" not in saved and isinstance(saved["median_model_json"], bytes)
            assert metadata["source_code"]["dependencies"]["xgboost"] == xgb.__version__
        bundle = restore_bundle(saved, algorithm)
        regressor = LGBMRegressor if algorithm == "lightgbm" else xgb.XGBRegressor
        classifier = LGBMClassifier if algorithm == "lightgbm" else xgb.XGBClassifier
        assert isinstance(bundle["median_model"], regressor)
        assert bundle["classifier"] is None or isinstance(bundle["classifier"], classifier)
        if algorithm == "xgboost":
            objective = json.loads(bundle["median_model"].get_booster().save_config())["learner"][
                "objective"
            ]
            assert objective["name"] == "reg:quantileerror"
            assert json.loads(objective["quantile_loss_param"]["quantile_alpha"]) == [0.5]
        expected = infer(bundle, frame)
        prediction = predictions[record["model_id"]]
        assert prediction["algorithm"] == algorithm and prediction["horizon"] == record["horizon"]
        assert prediction["median_return"] == pytest.approx(expected["median"][0])
        assert np.isfinite(prediction["median_return"])
        assert Decimal(prediction["interval_lower"]) <= Decimal(prediction["interval_upper"])
        if expected["probability"] is None:
            assert prediction["probability_up"] is None
        else:
            assert prediction["probability_up"] == pytest.approx(expected["probability"][0])
            assert 0 <= prediction["probability_up"] <= 1


@pytest.mark.parametrize("algorithms", [[], ["unknown"], ["xgboost", "xgboost"]])
def test_invalid_algorithm_requests_rejected(algorithms):
    """未知、空或重复算法在训练前拒绝，不能悄悄替换成LightGBM。"""
    with pytest.raises(ValidationError):
        GoldTrainRequest(observations=[], algorithms=algorithms)


def test_explicit_xgboost_request_trains_only_requested_algorithm(
    tmp_path, monkeypatch, synthetic_observations
):
    """只请求XGB时确实仅产出两个XGB工件，不混入或复用LGBM结果。"""
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    outcome = train_gold(
        GoldTrainRequest(observations=synthetic_observations, algorithms=["xgboost"])
    )
    assert outcome["status"] == "SUCCEEDED"
    assert [(row["algorithm"], row["horizon"]) for row in outcome["models"]] == [
        ("xgboost", 63),
        ("xgboost", 126),
    ]


def test_same_algorithm_identity_cannot_overwrite_frozen_artifact(
    monkeypatch, trained_gold_models, synthetic_observations
):
    """完全相同的算法、拟合时刻和输入只能复用旧工件，重训不得覆盖冻结内容。"""
    directory, models = trained_gold_models
    monkeypatch.setenv("ETF_MODEL_DIR", str(directory))
    before = {row["artifact"]: (directory / row["artifact"]).read_bytes() for row in models}
    with pytest.raises(ValueError, match="身份已存在"):
        train_gold(
            GoldTrainRequest(
                observations=synthetic_observations,
                algorithms=["xgboost"],
                fit_at=datetime(2026, 10, 26, 12, tzinfo=UTC),
                code_version="SYNTHETIC_TEST_ONLY",
            )
        )
    assert all((directory / name).read_bytes() == content for name, content in before.items())


@pytest.mark.parametrize("algorithm", ["lightgbm", "xgboost"])
def test_single_class_calibration_does_not_invent_probability(synthetic_observations, algorithm):
    """单类样本仍可训练收益回归，但上涨概率必须留空而非固定为0.5。"""
    labeled = labeled_frame(benchmark_frame(synthetic_observations), 63)
    train, calibration = temporal_split(labeled, 1800)
    train, calibration = train.copy(), calibration.copy()
    for frame in (train, calibration):
        frame["up"] = 1
        frame["target_return"] = frame["target_return"].abs() + 0.001
    bundle = fit_bundle(train, calibration, algorithm)
    assert bundle["classifier"] is None and bundle["calibrator"] is None
    assert bundle["calibration_status"] == "INSUFFICIENT_CLASS_COVERAGE"
    assert infer(bundle, calibration.tail(1))["probability"] is None


def test_legacy_lightgbm_artifact_without_algorithm_still_loads(
    tmp_path, monkeypatch, trained_gold_models, live_test_observations
):
    """旧记录没有顶层algorithm和bundle算法标识时，仍按原训练元数据识别和加载。"""
    directory, models = trained_gold_models
    shutil.copytree(directory, tmp_path, dirs_exist_ok=True)
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    original = next(record for record in models if record["algorithm"] == "lightgbm")
    manifest_path = tmp_path / (original["model_id"] + ".json")
    legacy = json.loads(manifest_path.read_text())
    legacy.pop("algorithm")
    artifact_path = tmp_path / legacy["artifact"]
    artifact = joblib.load(artifact_path)
    artifact["bundle"].pop("algorithm")
    joblib.dump(artifact, artifact_path)
    legacy["metadata"]["artifact_sha256"] = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(legacy))
    result = predict_gold(GoldPredictRequest(observations=live_test_observations, models=[legacy]))
    assert result["predictions"][0]["algorithm"] == "lightgbm"


@pytest.mark.parametrize("tamper", ["request_algorithm", "manifest_algorithm", "artifact_path"])
def test_algorithm_or_artifact_identity_tampering_rejected(
    tmp_path, monkeypatch, trained_gold_models, live_test_observations, tamper
):
    """改请求标签、同时改清单算法或替换文件路径均不能把另一算法冒充独立模型。"""
    directory, models = trained_gold_models
    shutil.copytree(directory, tmp_path, dirs_exist_ok=True)
    monkeypatch.setenv("ETF_MODEL_DIR", str(tmp_path))
    original = next(record for record in models if record["algorithm"] == "lightgbm")
    manifest_path = tmp_path / (original["model_id"] + ".json")
    manifest = json.loads(manifest_path.read_text())
    request_model = {"model_id": original["model_id"]}
    if tamper == "request_algorithm":
        request_model["algorithm"] = "xgboost"
    elif tamper == "manifest_algorithm":
        manifest["algorithm"] = "xgboost"
        manifest["metadata"]["algorithm"] = "XGBoost_small_fixed"
    else:
        manifest["artifact"] = "../outside.joblib"
    manifest_path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="不一致"):
        predict_gold(
            GoldPredictRequest(observations=live_test_observations, models=[request_model])
        )


def test_native_sge_uses_fixed_request_once_without_identity_overrides(monkeypatch):
    """Windows固定原生客户端，静态命令没有外部输入、身份覆盖或安全降级。"""
    calls = []

    def run(command, **options):
        """仅捕获隔离测试的静态子进程请求，不进行网络访问。"""
        calls.append((command, options))
        return subprocess.CompletedProcess(command, 0, '{"time":[]}', "")

    monkeypatch.setattr("etf_quant.gold_data.subprocess.run", run)
    with httpx.Client(
        transport=httpx.MockTransport(lambda request: pytest.fail("不能回退httpx"))
    ) as client:
        assert fetch_sge(client, "WINDOWS_INVOKE_WEBREQUEST") == {"time": []}
    assert len(calls) == 1
    command, options = calls[0]
    assert command[:5] == ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"]
    script = command[-1]
    assert "https://www.sge.com.cn/graph/Dailyhq" in script and "@{instid='Au99.99'}" in script
    assert all(
        word not in script.lower()
        for word in (
            "user-agent",
            "cookie",
            "skipcertificate",
            "securityprotocol",
            "websession",
            "bypass",
        )
    )
    assert options["timeout"] == 25 and "shell" not in options


@pytest.mark.parametrize(
    "returncode,stdout,stderr,expected",
    [
        (1, "", "SGE_HTTP_403", "SGE_HTTP_403"),
        (0, "<html>安全阻断</html>", "", "SGE_INVALID_JSON"),
        (1, "", "unexpected private system error", "SGE_NATIVE_REQUEST_FAILED"),
    ],
)
def test_native_rejection_has_no_fallback_or_sensitive_error(
    monkeypatch, returncode, stdout, stderr, expected
):
    """403/非JSON/系统失败均立即退出，不切客户端或回显外部错误正文。"""
    calls = []

    def run(command, **options):
        """用明确拒绝响应模拟系统客户端，不包含真实远程访问。"""
        calls.append(command)
        return subprocess.CompletedProcess(command, returncode, stdout, stderr)

    monkeypatch.setattr("etf_quant.gold_data.subprocess.run", run)
    with (
        httpx.Client(
            transport=httpx.MockTransport(lambda request: pytest.fail("拒绝后不得回退"))
        ) as client,
        pytest.raises(GoldSourceFailure) as error,
    ):
        fetch_sge(client, "WINDOWS_INVOKE_WEBREQUEST")
    assert error.value.code == expected and str(error.value) == expected
    assert len(calls) == 1


def test_native_timeout_and_source_health_preserve_failure(monkeypatch):
    """采集超时只记FAILED和真实传输方式，不能返回空的成功来源。"""
    from etf_quant import gold_data

    def run(command, **options):
        """模拟单次系统客户端超时。"""
        raise subprocess.TimeoutExpired(command, options["timeout"])

    monkeypatch.setattr(gold_data.subprocess, "run", run)
    monkeypatch.setattr(gold_data.platform, "system", lambda: "Windows")
    monkeypatch.setattr(gold_data, "SERIES", {BENCHMARK: gold_data.SERIES[BENCHMARK]})
    result = sync_gold(GoldSyncRequest())
    assert result["observations"] == []
    source = result["sources"][0]
    assert source["status"] == "FAILED"
    assert source["transport"] == "WINDOWS_INVOKE_WEBREQUEST"
    assert source["error"] == "SGE_NATIVE_TIMEOUT"
    assert source["last_observation_date"] is None


def test_httpx_403_does_not_switch_to_native(monkeypatch):
    """非Windows预选httpx也不能在安全拒绝后换传输重试。"""
    monkeypatch.setattr(
        "etf_quant.gold_data.subprocess.run",
        lambda *args, **kwargs: pytest.fail("403后不得换客户端"),
    )
    with (
        httpx.Client(
            transport=httpx.MockTransport(lambda request: httpx.Response(403, text="blocked"))
        ) as client,
        pytest.raises(httpx.HTTPStatusError),
    ):
        fetch_sge(client, "HTTPX")
