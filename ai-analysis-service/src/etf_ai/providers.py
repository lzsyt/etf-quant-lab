"""DeepSeek及OpenAI兼容协议；显式配置授权、预算预留后才允许外呼。"""

import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass, replace
from decimal import ROUND_UP, Decimal
from urllib.parse import urlparse

import httpx
from pydantic import ValidationError

from etf_ai.model_settings import MODEL_CATALOG, validate_selection
from etf_ai.prompts import PROMPT_VERSION, messages
from etf_ai.relevance import select_relevant
from etf_ai.schemas import AnalysisRequest, EventAnalysis

_CALL_LOCK = threading.Lock()
_LAST_CALL = 0.0


@dataclass
class Settings:
    enabled: bool = False
    provider: str = "deepseek"
    api_key: str = ""
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-flash"
    model_version: str = "DeepSeek-V4.1-Flash-1017"
    input_price: Decimal = Decimal("0.3")
    output_price: Decimal = Decimal("1.2")
    max_tokens: int = 1400
    attempts: int = 2
    mock: bool = False
    rate_seconds: float = 2.0
    reasoning_effort: str = "none"

    @classmethod
    def environment(cls):
        """密钥只驻留进程环境；配置接口只返回是否配置。"""
        return cls(
            enabled=os.getenv("ETF_LLM_ENABLED", "false").lower() == "true",
            provider=os.getenv("ETF_LLM_PROVIDER", "deepseek"),
            api_key=os.getenv("DEEPSEEK_API_KEY", ""),
            base_url=os.getenv("ETF_LLM_BASE_URL", "https://api.deepseek.com"),
            model=os.getenv("ETF_LLM_MODEL", "deepseek-flash"),
            model_version=os.getenv(
                "ETF_LLM_MODEL_VERSION", "DeepSeek-V4.1-Flash-1017"
            ),
            input_price=Decimal(os.getenv("ETF_LLM_INPUT_USD_PER_MILLION", "0.3")),
            output_price=Decimal(os.getenv("ETF_LLM_OUTPUT_USD_PER_MILLION", "1.2")),
        )

    @classmethod
    def for_selection(cls, selection):
        """选择同时决定模型版本、单价与输出上限，不能只换模型名却沿用便宜模型价格。"""
        settings = cls.environment()
        if selection is None:
            return settings
        selected = validate_selection(settings.provider, selection)
        model = MODEL_CATALOG[settings.provider][selected.model]
        effort = selected.reasoning_effort
        # 保留旧默认上限；思考档位给出明确、有界的推理加正文额度，完整计入预留。
        limit = settings.max_tokens
        if settings.provider == "deepseek" and effort != "none":
            limit = {"low": 4000, "high": 8000, "max": 12000}[effort]
        elif settings.provider == "openai":
            limit = {
                "none": 4000,
                "low": 4000,
                "medium": 8000,
                "high": 8000,
                "xhigh": 12000,
                "max": 12000,
            }[effort]
        return replace(
            settings,
            model=selected.model,
            model_version=model["model_version"],
            reasoning_effort=effort,
            max_tokens=limit,
            input_price=Decimal(model["input_per_million"]),
            output_price=Decimal(model["output_per_million"]),
        )

    @classmethod
    def for_request(cls, request):
        """执行阶段读取报价随请求冻结的选择，绝不重新读取页面当前配置。"""
        return cls.for_selection(request.model_settings)

    def selection(self):
        """报价和响应留存实际选择，不包含任何部署凭据。"""
        return {"model": self.model, "reasoning_effort": self.reasoning_effort}

    def reasoning_identity(self):
        """默认关闭思考时保留旧DeepSeek指纹；改变强度才生成新的输入身份。"""
        return (
            {}
            if self.reasoning_effort == "none"
            else {"reasoning_effort": self.reasoning_effort}
        )

    def thinking_parameters(self):
        """DeepSeek关闭与开启模式明确传参，不依赖供应商会变化的默认档位。"""
        if self.reasoning_effort == "none":
            return {"thinking": {"type": "disabled"}}
        return {
            "thinking": {"type": "enabled"},
            "reasoning_effort": self.reasoning_effort,
        }


def quote(request: AnalysisRequest, settings: Settings | None = None):
    """用UTF-8字节数上界和最大输出预留全部重试费用，收费采用保守峰值价。"""
    s = settings or Settings.for_request(request)
    request, relevance = select_relevant(request)
    payload = messages(request)
    size = sum(len(m["content"].encode()) for m in payload) + 1024
    if size > 60000 or s.input_price < 0 or s.output_price < 0:
        raise ValueError("输入超限或费用配置无效")
    reserved = (
        (size * s.input_price + s.max_tokens * s.output_price)
        * s.attempts
        / Decimal(1000000)
    ).quantize(Decimal("0.00000001"), ROUND_UP)
    return {
        "enabled": s.enabled,
        "configured": bool(s.api_key),
        "provider": s.provider,
        "model": s.model,
        "model_version": s.model_version,
        "model_settings": s.selection(),
        "prompt_version": PROMPT_VERSION,
        "reserved_usd": str(reserved),
        "input_sha256": hashlib.sha256(
            json.dumps(
                payload
                if not s.reasoning_identity()
                else {
                    "messages": payload,
                    **s.reasoning_identity(),
                    "max_tokens": s.max_tokens,
                },
                ensure_ascii=False,
            ).encode()
        ).hexdigest(),
        "eligible_source_ids": [item.id for item in request.news],
        "relevance": relevance,
        "pricing": {
            "currency": "USD",
            "input_per_million": str(s.input_price),
            "output_per_million": str(s.output_price),
            "max_output_tokens": s.max_tokens,
            "attempts": s.attempts,
            "price_checked_on": "2026-10-24",
            "source": "https://api-docs.deepseek.com/quick_start/pricing/",
            "basis": "CONSERVATIVE_PEAK_ESTIMATE",
        },
    }


def analyze(request: AnalysisRequest, settings: Settings | None = None, transport=None):
    """错误内容不透传；未知收费保留预算占用，失败绝不生成替代财经分析。"""
    s = settings or Settings.for_request(request)
    quoted = quote(request, s)
    request, relevance = select_relevant(request)
    common = {
        "prompt_version": PROMPT_VERSION,
        "model_version": s.model_version,
        "model_settings": s.selection(),
        "input_cutoff": request.cutoff.isoformat(),
        "input_sha256": quoted["input_sha256"],
        "nature": "MOCK" if s.mock else "REAL",
        "source_ids": [n.id for n in request.news],
        "relevance": relevance,
    }
    if not request.news:
        return {**common, "status": "MISSING", "error_code": "NO_RELEVANT_NEWS"}
    if not s.mock and (not s.enabled or not s.api_key):
        return {
            **common,
            "status": "MISSING",
            "error_code": "PROVIDER_DISABLED_OR_KEY_MISSING",
        }
    if any(n.nature != ("MOCK" if s.mock else "REAL") for n in request.news):
        raise ValueError("真实分析不能混入Mock新闻")
    if s.mock:
        result = EventAnalysis(
            market_sentiment="neutral",
            sentiment_score=0.0,
            risk_level="medium",
            key_factors=["显式Mock测试"],
            risk_factors=["不能用于市场判断"],
            source_ids=[request.news[0].id],
            analysis_summary="Mock合约测试数据，无实际财经分析意义。",
        )
        return {
            **common,
            "status": "SUCCEEDED",
            "result": result.model_dump(),
            "input_tokens": 0,
            "output_tokens": 0,
            "cost_usd": "0",
            "duration_ms": 0,
            "billing_uncertain": False,
            "attempts": 0,
        }
    parsed = urlparse(s.base_url)
    # 中国内地部署明确禁用GPT，不通过代理或自定义地址绕过地域限制。
    if (
        s.provider == "openai"
        or s.model.lower().startswith("gpt")
        or "openai.com" in (parsed.hostname or "")
    ):
        raise ValueError("当前中国内地部署禁用GPT适配器")
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise ValueError("模型服务必须使用HTTPS且不得在URL中包含凭据")
    if s.provider == "deepseek" and parsed.hostname != "api.deepseek.com":
        raise ValueError("DeepSeek适配器仅允许官方地址")
    if Decimal(request.reserved_usd) < Decimal(quoted["reserved_usd"]):
        raise ValueError("模型调用未预留足够费用")
    body = {
        "model": s.model,
        "messages": messages(request),
        "max_tokens": s.max_tokens,
        "response_format": {"type": "json_object"},
        "stream": False,
    }
    if s.provider == "deepseek":
        body.update(s.thinking_parameters())
    started = time.monotonic()
    incoming = outgoing = 0
    uncertain = False
    raw_output = None
    error_code = "CALL_FAILED"
    with (
        _CALL_LOCK,
        httpx.Client(
            timeout=httpx.Timeout(
                35 if s.reasoning_effort == "none" else 120, connect=8
            ),
            trust_env=False,
            follow_redirects=False,
            transport=transport,
        ) as client,
    ):
        global _LAST_CALL
        for attempt in range(s.attempts):
            time.sleep(max(0, s.rate_seconds - (time.monotonic() - _LAST_CALL)))
            _LAST_CALL = time.monotonic()
            try:
                response = client.post(
                    s.base_url.rstrip("/") + "/chat/completions",
                    json=body,
                    headers={"Authorization": "Bearer " + s.api_key},
                )
                if response.status_code != 200:
                    error_code = "HTTP_" + str(response.status_code)
                    uncertain |= response.status_code >= 500
                    if response.status_code != 429 and response.status_code < 500:
                        break
                    continue
                data = response.json()
                usage = data.get("usage", {})
                if "prompt_tokens" not in usage or "completion_tokens" not in usage:
                    uncertain = True
                incoming += max(0, int(usage.get("prompt_tokens", 0)))
                outgoing += max(0, int(usage.get("completion_tokens", 0)))
                raw_output = data["choices"][0]["message"]["content"]
                result = EventAnalysis.model_validate_json(raw_output)
                if not set(result.source_ids) <= {n.id for n in request.news}:
                    raise ValueError("UNKNOWN_SOURCE_ID")
                if data["choices"][0].get("finish_reason") != "stop":
                    raise ValueError("INCOMPLETE_OUTPUT")
                return {
                    **common,
                    "status": "SUCCEEDED",
                    "result": result.model_dump(),
                    "raw_output": raw_output,
                    "response_model": data.get("model", s.model),
                    "input_tokens": incoming,
                    "output_tokens": outgoing,
                    "cost_usd": str(
                        (incoming * s.input_price + outgoing * s.output_price)
                        / Decimal(1000000)
                    ),
                    "duration_ms": int((time.monotonic() - started) * 1000),
                    "billing_uncertain": uncertain,
                    "attempts": attempt + 1,
                }
            except (
                httpx.HTTPError,
                KeyError,
                IndexError,
                ValueError,
                ValidationError,
                TypeError,
            ) as error:
                error_code = (
                    "INVALID_OUTPUT" if raw_output is not None else type(error).__name__
                )
                uncertain |= raw_output is None
        return {
            **common,
            "status": "FAILED",
            "error_code": error_code,
            "raw_output": raw_output,
            "input_tokens": incoming,
            "output_tokens": outgoing,
            "billing_uncertain": uncertain,
            "cost_usd": str(
                (incoming * s.input_price + outgoing * s.output_price)
                / Decimal(1000000)
            ),
            "duration_ms": int((time.monotonic() - started) * 1000),
            "attempts": attempt + 1,
        }
