"""实际 HTTP 与专属数据库的端到端验收，不输出登录凭据。"""
import json
import os
from pathlib import Path
import time
from contextlib import contextmanager

import httpx

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def authenticated_client(base_url="http://127.0.0.1:18080"):
    """凭据只在本地受限配置与 HTTP 会话内使用。"""
    config = json.loads(Path(os.environ.get("ETF_E2E_CONFIG", ROOT / ".runtime/secrets.json")).read_text())
    client = httpx.Client(base_url=base_url, timeout=180)
    for attempt in range(60):
        try:
            if client.get("/actuator/health").status_code == 200:
                break
        except httpx.HTTPError:
            pass
        if attempt == 59:
            client.close()
            raise RuntimeError("后端健康检查未通过")
        time.sleep(1)
    csrf = client.get("/api/auth/csrf").json()
    response = client.post("/api/auth/login", data={"username":"admin", "password":config["admin_password"]},
                           headers={csrf["header"]:csrf["token"]})
    assert response.status_code == 204, response.status_code
    csrf = client.get("/api/auth/csrf").json()
    client.headers[csrf["header"]] = csrf["token"]
    try:
        yield client
    finally:
        client.close()


def main():
    for _ in range(40):
        try:
            if httpx.get("http://127.0.0.1:18080/actuator/health").status_code == 200:
                break
        except httpx.HTTPError:
            pass
        time.sleep(1)
    assert httpx.get("http://127.0.0.1:18080/api/market/instruments").status_code == 401
    with authenticated_client() as client:
        instruments = client.get("/api/market/instruments")
        assert instruments.status_code == 200 and len(instruments.json()) == 3
        payload = {"symbol":"510300", "start":"2021-01-01", "end":"2024-12-31", "provider":"DEMO"}
        first = client.post("/api/market/sync", json=payload)
        assert first.status_code == 200, first.text
        second = client.post("/api/market/sync", json=payload)
        assert second.status_code == 200, second.text
        bars = client.get("/api/market/bars", params={"symbol":"510300","nature":"DEMO"}).json()
        assert len(bars) == first.json()["count"]
        assert all(bar["nature"] == "DEMO" for bar in bars)
        indicators = client.get("/api/market/indicators", params={"symbol":"510300","nature":"DEMO"})
        assert indicators.status_code == 200, indicators.text
        real = client.get("/api/market/bars", params={"symbol":"510300","nature":"REAL"}).json()
        assert all(bar["nature"] == "REAL" for bar in real)
    print(json.dumps({"status":"PASSED","authentication":True,"csrf_login":True,
                      "demo_bars":len(bars),"duplicate_sync":True,
                      "real_demo_separation":True,"indicators":True}))


if __name__ == "__main__":
    main()
