"""仅回环部署的内部分析 API；除健康检查外均要求专属服务令牌。"""

import hmac
import logging
import os
import re
import uuid
from datetime import UTC, datetime

from etf_ai.forecast import ForecastAnalysisRequest, analyze_forecast, quote_forecast
from etf_ai.forward import ForwardAnalysisRequest, analyze_forward, quote_forward
from etf_ai.fund import FundAnalysisRequest, analyze_fund, quote_fund
from etf_ai.gold import GoldAnalysisRequest, analyze_gold, quote_gold
from etf_ai.gpt import GptReviewRequest, analyze_gpt, quote_gpt
from etf_ai.model_settings import (
    ModelSettingsRequest,
    ModelSettingsValidation,
    catalog,
    validate_selection,
)
from etf_ai.news import collect_news
from etf_ai.providers import analyze, quote
from etf_ai.schemas import AnalysisRequest
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.exceptions import RequestValidationError

from etf_quant.backtesting import (
    BacktestRequest,
    RiskConfig,
    StepRequest,
    daily_step,
    run_backtest,
    strategy_signals,
)
from etf_quant.close_models import (
    ClosePredictRequest,
    CloseTrainRequest,
    predict_close,
    train_close_model,
)
from etf_quant.data import fetch_bars, fetch_calendar
from etf_quant.features import indicator_rows
from etf_quant.forward_execution import (
    ForwardFreezeRequest,
    ForwardStepRequest,
    ForwardValuationRequest,
    freeze_orders,
    settle_forward,
    value_forward,
)
from etf_quant.forward_models import (
    ForwardPredictRequest,
    ForwardTrainRequest,
    predict_forward,
    train_forward_model,
)
from etf_quant.fund_data import FundFetchRequest, fetch_fund_nav
from etf_quant.fund_models import (
    FundEvaluateRequest,
    FundPredictRequest,
    FundTrainRequest,
    evaluate_fund,
    predict_funds,
    train_funds,
)
from etf_quant.fund_news import (
    FundNewsPrepareRequest,
    FundNewsValidateRequest,
    prepare_fund_news,
    validate_fund_news,
)
from etf_quant.fusion import FusionRequest, evaluate_fusion
from etf_quant.gold_data import GoldSyncRequest, sync_gold
from etf_quant.gold_models import GoldPredictRequest, GoldTrainRequest, predict_gold, train_gold
from etf_quant.models import PredictRequest, TrainRequest, predict, train_model
from etf_quant.schemas import BarsRequest, FetchRequest

app = FastAPI(title="ETF Quant Lab Internal", docs_url=None, redoc_url=None, openapi_url=None)
LOG = logging.getLogger("uvicorn.error")


@app.exception_handler(RequestValidationError)
async def request_validation_handler(request, error):
    """422仅返回字段路径和错误类型，既保留契约失败证据也不回显输入秘密。"""
    from fastapi.responses import JSONResponse

    fields = [
        {"field": ".".join(map(str, item["loc"])), "type": item["type"]} for item in error.errors()
    ]
    trace = getattr(request.state, "trace_id", "")
    LOG.warning(
        "utc=%s trace_id=%s validation_errors=%s", datetime.now(UTC).isoformat(), trace, fields
    )
    return JSONResponse(
        status_code=422,
        content={"detail": "REQUEST_SCHEMA_INVALID", "fields": fields, "trace_id": trace},
    )


@app.middleware("http")
async def trace_request(request, call_next):
    """只记录时间、路径、状态和关联ID，绝不记录令牌、请求正文或模型密钥。"""
    candidate = request.headers.get("x-trace-id", "")
    trace = candidate if re.fullmatch(r"[A-Za-z0-9-]{1,80}", candidate) else str(uuid.uuid4())
    request.state.trace_id = trace
    try:
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace
        LOG.info(
            "utc=%s trace_id=%s path=%s http_status=%s",
            datetime.now(UTC).isoformat(),
            trace,
            request.url.path,
            response.status_code,
        )
        return response
    except Exception as error:
        LOG.error(
            "utc=%s trace_id=%s path=%s error=%s",
            datetime.now(UTC).isoformat(),
            trace,
            request.url.path,
            type(error).__name__,
        )
        raise


@app.exception_handler(ValueError)
async def value_error_handler(request, error):
    """业务校验错误返回受控文本，内部异常不输出密钥或配置。"""
    from fastapi.responses import JSONResponse

    LOG.warning(
        "utc=%s trace_id=%s business_rejected=%s",
        datetime.now(UTC).isoformat(),
        getattr(request.state, "trace_id", ""),
        str(error)[:200],
    )
    return JSONResponse(
        status_code=422,
        content={"detail": str(error)[:200], "trace_id": getattr(request.state, "trace_id", "")},
    )


@app.post("/news/collect", dependencies=[])
def news_collect(x_internal_token: str = Header(default="")):
    """只从固定允许的公开来源收集资料。"""
    authorize(x_internal_token)
    return collect_news()


@app.post("/analysis/quote")
def analysis_quote(request: AnalysisRequest, x_internal_token: str = Header(default="")):
    """返回成本上界及配置状态，不返回任何密钥。"""
    authorize(x_internal_token)
    return quote(request)


@app.post("/analysis/run")
def analysis_run(request: AnalysisRequest, x_internal_token: str = Header(default="")):
    """由Java完成持久费用预留后才实际调用模型。"""
    authorize(x_internal_token)
    return analyze(request)


def authorize(x_internal_token: str = Header(default="")):
    """缺少配置时拒绝访问，常量时间比较避免暴露密钥特征。"""
    expected = os.environ.get("ETF_INTERNAL_TOKEN", "")
    if len(expected) < 32 or not hmac.compare_digest(expected, x_internal_token):
        raise HTTPException(status_code=401, detail="内部认证失败")


@app.get("/health")
def health():
    """仅披露服务状态和模拟交易限定，不披露配置。"""
    return {
        "status": "UP",
        "mode": "PAPER_ONLY",
        "version": "2.2.0",
        "protocol_version": "OPEN_NET_REBALANCE_V1",
        "gold_label_version": "SGE_CLOSE_RETURN_63_126_V1",
    }


@app.post("/forward/models/train", dependencies=[Depends(authorize)])
def forward_train(request: ForwardTrainRequest):
    """独立开盘标签模型训练，不能覆盖旧收盘标签工件。"""
    return train_forward_model(request)


@app.post("/close/models/train", dependencies=[Depends(authorize)])
def close_train(request: CloseTrainRequest):
    """按独立收盘涨跌幅合同训练20/60交易日回归工件。"""
    return train_close_model(request)


@app.post("/close/models/predict", dependencies=[Depends(authorize)])
def close_predict(request: ClosePredictRequest):
    """只生成收盘涨跌幅研究预测，不产生真实或模拟委托。"""
    return predict_close(request)


@app.post("/forward/models/predict", dependencies=[Depends(authorize)])
def forward_predict(request: ForwardPredictRequest):
    """完整身份与真实可用时间检查后才推理。"""
    return predict_forward(request)


@app.post("/forward/paper/freeze", dependencies=[Depends(authorize)])
def forward_freeze(request: ForwardFreezeRequest):
    """开盘前冻结意图，不接收执行日行情。"""
    return freeze_orders(request)


@app.post("/forward/paper/step", dependencies=[Depends(authorize)])
def forward_step(request: ForwardStepRequest):
    """只消费已冻结计划，由Java事务落账。"""
    return settle_forward(request)


@app.post("/forward/paper/value", dependencies=[Depends(authorize)])
def forward_value(request: ForwardValuationRequest):
    """缺少及时计划只估值，不倒造过去开盘成交。"""
    return value_forward(request)


@app.post("/analysis/forward/quote", dependencies=[Depends(authorize)])
def forward_quote(request: ForwardAnalysisRequest):
    """成本上界和逐ETF候选审计不调用付费模型。"""
    return quote_forward(request)


@app.post("/analysis/forward/run", dependencies=[Depends(authorize)])
def forward_analysis(request: ForwardAnalysisRequest):
    """Java持久预算预留后才执行一次逐ETF批量分析。"""
    return analyze_forward(request)


@app.post("/fusion/evaluate", dependencies=[Depends(authorize)])
def fusion_evaluate(request: FusionRequest):
    """正式融合指标需要足量前瞻已到期样本，与固定权重实验分开。"""
    return evaluate_fusion(request)


@app.post("/paper/metrics", dependencies=[Depends(authorize)])
def paper_metrics(request: dict):
    """批量复用回测统计公式，账户资金仍由Decimal结算，不重复HTTP调用。"""
    from decimal import Decimal

    from etf_quant.backtesting import performance

    result = {}
    for account in request.get("accounts", [])[:50]:
        result[account["id"]] = (
            performance(
                account["curve"], account["fills"], Decimal(str(account["initial_capital"]))
            )
            if account["curve"]
            else {}
        )
    return result


@app.post("/market/fetch", dependencies=[Depends(authorize)])
def market_fetch(request: FetchRequest):
    """市场数据抓取失败显式返回，不构造虚假行情。"""
    try:
        return fetch_bars(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/market/calendar", dependencies=[Depends(authorize)])
def calendar():
    """提供可追溯的交易日列表及覆盖范围。"""
    try:
        return fetch_calendar()
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/market/indicators", dependencies=[Depends(authorize)])
def indicators(request: BarsRequest):
    """计算单 ETF 历史指标。"""
    return {"indicators": indicator_rows([bar.model_dump(mode="json") for bar in request.bars])}


@app.post("/models/train", dependencies=[Depends(authorize)])
def model_train(request: TrainRequest):
    """串行训练并返回可追溯版本和样本外评价。"""
    try:
        return train_model(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/strategies/signals", dependencies=[Depends(authorize)])
def latest_rule_signals(request: BacktestRequest):
    """前瞻规则策略复用回测的同一计算逻辑，避免研究与上线产生偏差。"""
    signals = strategy_signals(request)
    return {"signals": [s.model_dump(mode="json") for s in signals.get(request.end, [])]}


@app.post("/models/predict", dependencies=[Depends(authorize)])
def model_predict(request: PredictRequest):
    """使用已存在工件进行截止最新行情的预测。"""
    try:
        return predict(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/backtests/run", dependencies=[Depends(authorize)])
def backtest(request: BacktestRequest):
    """回测响应包含资金曲线、真实成本及成交假设。"""
    try:
        return run_backtest(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/paper/step", dependencies=[Depends(authorize)])
def paper_step(request: StepRequest):
    """单日状态转移由 Java 数据库事务保护和持久化。"""
    try:
        return daily_step(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/risk/validate", dependencies=[Depends(authorize)])
def validate_risk(request: RiskConfig):
    """复用真实执行所用的参数约束，返回完整的可审计配置。"""
    return request.model_dump(mode="json")


@app.post("/gold/data/sync", dependencies=[Depends(authorize)])
def gold_data_sync(request: GoldSyncRequest):
    """黄金固定公开来源独立采集，来源失败与部分成功分别返回。"""
    return sync_gold(request)


@app.post("/gold/train", dependencies=[Depends(authorize)])
def gold_train(request: GoldTrainRequest):
    """只创建黄金命名空间工件，不修改ETF已训练模型。"""
    return train_gold(request)


@app.post("/gold/predict", dependencies=[Depends(authorize)])
def gold_predict(request: GoldPredictRequest):
    """独立63/126交易日研究推理，由Java事务冻结原始输出。"""
    return predict_gold(request)


@app.post("/analysis/gold/quote", dependencies=[Depends(authorize)])
def gold_analysis_quote(request: GoldAnalysisRequest):
    """黄金解释费用预估绑定独立业务域，沿用统一日预算。"""
    return quote_gold(request)


@app.post("/analysis/gold/run", dependencies=[Depends(authorize)])
def gold_analysis_run(request: GoldAnalysisRequest):
    """黄金新闻只解释冻结证据，不改变数值概率与区间。"""
    return analyze_gold(request)


@app.post("/funds/nav/fetch", dependencies=[Depends(authorize)])
def fund_nav_fetch(request: FundFetchRequest):
    """串行采集场外基金真实净值，修订由Java追加保存。"""
    return fetch_fund_nav(request)


@app.post("/funds/models/train", dependencies=[Depends(authorize)])
def fund_models_train(request: FundTrainRequest):
    """基金20/60独立时间序列研究，不变更旧ETF模型。"""
    return train_funds(request)


@app.post("/funds/models/predict", dependencies=[Depends(authorize)])
def fund_models_predict(request: FundPredictRequest):
    """显式工件推理，返回冻结端点，不在页面读取中训练。"""
    return predict_funds(request)


@app.post("/funds/models/evaluate", dependencies=[Depends(authorize)])
def fund_forecasts_evaluate(request: FundEvaluateRequest):
    """返回到期实际验证记录，保留原预测输入和输出。"""
    return evaluate_fund(request)


@app.post("/funds/news/prepare", dependencies=[Depends(authorize)])
def fund_news_prepare(request: FundNewsPrepareRequest):
    """免费准备资产相关且截止前可得的冻结资料。"""
    return prepare_fund_news(request)


@app.post("/funds/news/validate", dependencies=[Depends(authorize)])
def fund_news_validate(request: FundNewsValidateRequest):
    """免费验证模型引用，未知来源和未来新闻不得参与预测解释。"""
    return validate_fund_news(request)


@app.post("/analysis/fund/quote", dependencies=[Depends(authorize)])
def fund_analysis_quote(request: FundAnalysisRequest):
    """只报价基金专用输入，实际调用仍需共享预算预留与独立开关。"""
    return quote_fund(request)


@app.post("/analysis/fund/run", dependencies=[Depends(authorize)])
def fund_analysis_run(request: FundAnalysisRequest):
    """由Java持久预留后最多一次DS调用，结构校验失败不得形成有效结论。"""
    return analyze_fund(request)


@app.post("/analysis/forecast/quote", dependencies=[Depends(authorize)])
def forecast_analysis_quote(request: ForecastAnalysisRequest):
    """按资产目标和独立输入冻结报价，不调用模型或修改任何历史预测。"""
    return quote_forecast(request)


@app.post("/analysis/forecast/run", dependencies=[Depends(authorize)])
def forecast_analysis_run(request: ForecastAnalysisRequest):
    """仅消费Java已持久预留的同一报价，执行至多一次独立DeepSeek预测。"""
    return analyze_forecast(request)


@app.post("/analysis/gpt/quote", dependencies=[Depends(authorize)])
def gpt_forecast_quote(request: ForecastAnalysisRequest):
    """免费冻结GPT独立输入与费用，配置不完整时明确返回不可用。"""
    return quote_gpt(request)


@app.post("/analysis/gpt/run", dependencies=[Depends(authorize)])
def gpt_forecast_run(request: ForecastAnalysisRequest):
    """GPT只经指定代理调用，沿用Java共享预算与不可变预测记录。"""
    return analyze_gpt(request)


@app.post("/analysis/gpt-review/quote", dependencies=[Depends(authorize)])
def gpt_review_quote(request: GptReviewRequest):
    """历史分歧报价入口仅返回停用状态，不再预留费用。"""
    return quote_gpt(request)


@app.post("/analysis/gpt-review/run", dependencies=[Depends(authorize)])
def gpt_review_run(request: GptReviewRequest):
    """历史分歧执行入口仅返回停用状态，不再调用模型。"""
    return analyze_gpt(request)


@app.post("/analysis/settings", dependencies=[Depends(authorize)])
def model_settings_catalog(request: ModelSettingsRequest):
    """只读取受支持模型目录和配置状态，绝不调用外部模型。"""
    return catalog(request.selections)


@app.post("/analysis/settings/validate", dependencies=[Depends(authorize)])
def model_settings_validate(request: ModelSettingsValidation):
    """保存之前校验模型与档位组合，价格只能由服务目录确定。"""
    return validate_selection(request.provider, request.selection).model_dump()
