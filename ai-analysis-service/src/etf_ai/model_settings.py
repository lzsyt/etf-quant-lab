"""设置页允许的模型及官方价格；只包含非敏感元数据，不访问提供商或触发收费。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

PRICE_CHECKED_ON = "2026-10-31"
GPT_EFFORTS = ["low", "medium", "high", "xhigh", "max"]
DS_EFFORTS = ["none", "low", "high", "max"]
MODEL_CATALOG = {
    "deepseek": {
        "deepseek-flash": {
            "name": "DeepSeek Flash",
            "model_version": "DeepSeek-V4.1-Flash-1017",
            "input_per_million": "0.3",
            "output_per_million": "1.2",
            "efforts": DS_EFFORTS,
            "source": "https://api-docs.deepseek.com/quick_start/pricing/",
        },
        "deepseek-v4-pro": {
            "name": "DeepSeek V4 Pro",
            "model_version": "DeepSeek-V4-Pro-0813",
            "input_per_million": "1.32",
            "output_per_million": "3.96",
            "efforts": DS_EFFORTS,
            "source": "https://api-docs.deepseek.com/quick_start/pricing/",
        },
    },
    "openai": {
        "gpt-6.1-sol": {
            "name": "GPT-6.1 Sol",
            "model_version": "gpt-6.1-sol",
            "input_per_million": "2",
            "output_per_million": "10",
            "efforts": GPT_EFFORTS,
            "source": "https://developers.openai.com/api/docs/models/gpt-6.1-sol",
        },
        "gpt-6-luna": {
            "name": "GPT-6 Luna",
            "model_version": "gpt-6-luna",
            "input_per_million": "0.1",
            "output_per_million": "0.5",
            "efforts": ["none", *GPT_EFFORTS],
            "source": "https://developers.openai.com/api/docs/models/gpt-6-luna",
        },
        "gpt-6-astra": {
            "name": "GPT-6 Astra",
            "model_version": "gpt-6-astra",
            "input_per_million": "10",
            "output_per_million": "50",
            "efforts": GPT_EFFORTS,
            "source": "https://developers.openai.com/api/docs/models/gpt-6-astra",
        },
    },
}


class ModelSelection(BaseModel):
    """只接受模型名和强度，不能借设置请求覆盖密钥、地址、代理或价格。"""

    model_config = ConfigDict(extra="forbid")
    model: str = Field(min_length=1, max_length=80)
    reasoning_effort: str = Field(min_length=1, max_length=16)


class ModelSettingsRequest(BaseModel):
    """系统页一次读取两个供应商的已有选择；空值沿用部署配置。"""

    model_config = ConfigDict(extra="forbid")
    selections: dict[Literal["deepseek", "openai"], ModelSelection] = Field(
        default_factory=dict
    )


class ModelSettingsValidation(BaseModel):
    """保存前的内部校验请求，不携带任何业务分析输入。"""

    model_config = ConfigDict(extra="forbid")
    provider: Literal["deepseek", "openai"]
    selection: ModelSelection


def validate_selection(provider, selection):
    """使用实际调用支持的目录验证组合，不将DeepSeek中档映射成高档后假装原样执行。"""
    selected = ModelSelection.model_validate(selection)
    model = MODEL_CATALOG.get(provider, {}).get(selected.model)
    if not model or selected.reasoning_effort not in model["efforts"]:
        raise ValueError("所选模型或推理强度不受支持")
    return selected


def catalog(selections):
    """展示当前生效选择及模型价格；Key只显示有无，GPT代理地址不返回。"""
    from etf_ai.gpt import GptSettings, proxy_ready
    from etf_ai.providers import Settings

    providers = []
    for provider, settings_type, name in [
        ("deepseek", Settings, "DeepSeek"),
        ("openai", GptSettings, "GPT"),
    ]:
        settings = settings_type.for_selection(selections.get(provider))
        providers.append(
            {
                "id": provider,
                "provider": provider,
                "name": name,
                "enabled": settings.enabled,
                "configured": bool(settings.api_key)
                and (provider != "openai" or proxy_ready(settings)),
                "model": settings.model,
                "reasoning_effort": settings.reasoning_effort,
                "current": settings.selection(),
                "models": [
                    {"id": key, **value}
                    for key, value in MODEL_CATALOG[provider].items()
                ],
                "price_checked_on": PRICE_CHECKED_ON,
            }
        )
    return {"providers": providers}
