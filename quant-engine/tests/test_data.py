from datetime import UTC, date, datetime
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from etf_quant.api import app
from etf_quant.data import clean_records, fetch_bars
from etf_quant.features import feature_frame
from etf_quant.schemas import FetchRequest


def request(**kwargs):
    return FetchRequest(
        symbol="510300", start=date(2023, 1, 1), end=date(2024, 12, 31), provider="DEMO", **kwargs
    )


def test_demo_is_deterministic_and_marked():
    first = fetch_bars(request())
    second = fetch_bars(request())
    assert first["report"]["nature"] == "DEMO"
    assert first["bars"][0]["close"] == second["bars"][0]["close"]
    assert first["report"]["count"] > 400


def test_invalid_duplicate_and_missing_dates_reported():
    rows = [
        {
            "date": "2023-01-03",
            "open": 3,
            "high": 4,
            "low": 2,
            "close": 3,
            "volume": 100,
            "amount": 300,
        }
    ]
    invalid = dict(rows[0], date="2023-01-04", high=1)
    result = clean_records(rows + rows + [invalid], request(expected_dates=[date(2023, 1, 5)]))
    assert result["report"]["rejected_count"] == 2
    assert result["report"]["missing_dates"] == ["2023-01-05"]


def test_eastmoney_volume_lots_converted_to_shares():
    req = FetchRequest(symbol="510300", start=date(2023, 1, 1), end=date(2023, 2, 1))
    row = {
        "日期": "2023-01-03",
        "开盘": 3,
        "最高": 4,
        "最低": 2,
        "收盘": 3,
        "成交量": 100,
        "成交额": 30000,
    }
    assert Decimal(clean_records([row], req)["bars"][0]["volume"]) == 10000


def test_future_rows_do_not_change_past_features():
    rows = fetch_bars(request())["bars"]
    past = feature_frame(rows[:200])
    full = feature_frame(rows)
    assert past.iloc[-1]["rsi14"] == full.iloc[199]["rsi14"]
    assert past.iloc[-1]["volatility20"] == full.iloc[199]["volatility20"]


def test_internal_authentication_and_mock_fetch(monkeypatch):
    monkeypatch.setenv("ETF_INTERNAL_TOKEN", "t" * 40)
    client = TestClient(app)
    payload = request().model_dump(mode="json")
    assert client.post("/market/fetch", json=payload).status_code == 401
    response = client.post("/market/fetch", json=payload, headers={"X-Internal-Token": "t" * 40})
    assert response.status_code == 200
    assert response.json()["report"]["nature"] == "DEMO"


def tencent_request(**kwargs):
    """固定历史日期的三ETF测试请求，不访问实际网络。"""
    return FetchRequest(
        symbol="510300",
        start=date(2024, 1, 3),
        end=date(2024, 1, 3),
        provider="TENCENT",
        **kwargs,
    )


def mock_tencent(monkeypatch, payload):
    """模拟源协议并记录请求，避免单元测试依赖免费源波动。"""
    from etf_quant import data

    calls = []

    class Response:
        def raise_for_status(self):
            pass

        def json(self):
            return payload

    class Session:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def get(self, url, **kwargs):
            calls.append((url, kwargs))
            return Response()

    monkeypatch.setattr(data.requests, "Session", Session)
    return calls


def tencent_payload():
    """本夹具是明确的模拟协议数据，不作为真实行情证据。"""
    return {
        "code": 0,
        "data": {
            "sh510300": {
                "day": [
                    [
                        "2024-01-03",
                        "3.001",
                        "3.123",
                        "3.300",
                        "2.900",
                        "12.34",
                        {},
                        "0.1",
                        "2.3456",
                    ],
                ]
            }
        },
    }


def test_tencent_raw_units_provenance_and_exact_date(monkeypatch):
    payload = tencent_payload()
    payload["data"]["sh510300"]["day"].append(
        ["2024-01-02", "3", "3", "3", "3", "1", {}, "0.1", "1"]
    )
    calls = mock_tencent(monkeypatch, payload)
    result = fetch_bars(tencent_request(expected_dates=[date(2024, 1, 3)]))
    assert len(result["bars"]) == 1
    bar = result["bars"][0]
    assert bar["source"] == "TENCENT_HTTP" and bar["nature"] == "REAL"
    assert bar["price_basis"] == "RAW"
    assert Decimal(bar["open"]) == Decimal("3.001")
    assert Decimal(bar["volume"]) == Decimal(1234)
    assert Decimal(bar["amount"]) == Decimal(23456)
    assert bar["raw_values"]["volume"] == "12.34"
    assert bar["raw_values"]["amount"] == "2.3456"
    assert result["report"]["missing_dates"] == []
    assert calls[0][1]["params"]["param"] == "sh510300,day,2024-01-03,2024-01-03,640,"


@pytest.mark.parametrize(
    "payload",
    [
        {"code": 1, "data": {}},
        {"code": 0, "data": {"sh510300": {"qfqday": []}}},
        {"code": 0, "data": {"sz159915": {"day": []}}},
        {"code": 0, "data": {"sh510300": {"day": [["2024-01-03", "3"]]}}},
    ],
)
def test_tencent_rejects_adjusted_mismatched_and_invalid_responses(monkeypatch, payload):
    mock_tencent(monkeypatch, payload)
    with pytest.raises(ValueError, match="腾讯公开未复权日线接口暂不可用"):
        fetch_bars(tencent_request())


def test_tencent_never_silently_uses_raw_for_adjusted_request(monkeypatch):
    calls = mock_tencent(monkeypatch, tencent_payload())
    with pytest.raises(ValueError, match="未复权日线"):
        fetch_bars(tencent_request(price_basis="QFQ"))
    assert calls == []


def test_tencent_obeys_existing_close_time_validation(monkeypatch):
    from etf_quant import data

    class BeforeClose(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime(2024, 1, 3, 6, 0, tzinfo=UTC)

    monkeypatch.setattr(data, "datetime", BeforeClose)
    mock_tencent(monkeypatch, tencent_payload())
    with pytest.raises(ValueError, match="没有可入库的有效行情"):
        fetch_bars(tencent_request())
