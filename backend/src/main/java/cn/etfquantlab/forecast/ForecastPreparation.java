package cn.etfquantlab.forecast;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardModels;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 当前资产的资料与模型准备，沿用原生能力，不生成预测、不调用付费分析。 */
@Service
public class ForecastPreparation {
    private final ForecastRunService runs;
    private final Store store;
    private final FundResearchService funds;
    private final GoldService gold;
    private final MarketService market;
    private final PredictionService dates;
    private final ForwardModels forward;
    private final ResearchService legacy;
    private final CloseModels close;

    /** 只连接既有资产服务，数据源沿用当前部署配置。 */
    public ForecastPreparation(
            ForecastRunService runs,
            Store store,
            FundResearchService funds,
            GoldService gold,
            MarketService market,
            PredictionService dates,
            ForwardModels forward,
            ResearchService legacy,
            CloseModels close) {
        this.runs = runs;
        this.store = store;
        this.funds = funds;
        this.gold = gold;
        this.market = market;
        this.dates = dates;
        this.forward = forward;
        this.legacy = legacy;
        this.close = close;
    }

    /** 准备只能显式选择单个真实资产目标，不能把缺失参数解释成全量训练。 */
    public void validate(JsonNode request) {
        if (!request.hasNonNull("asset")
                || !List.of("SYNC", "TRAIN").contains(request.path("action").asText())) {
            throw new IllegalArgumentException("准备任务必须选择资产目标和SYNC或TRAIN动作");
        }
        runs.selections(request);
    }

    /** 队列执行按原生协议分发；基金/黄金原生训练一次准备该资产各已支持期限。 */
    public Map<String, Object> execute(JsonNode request) {
        validate(request);
        ForecastRunService.Selection selected = runs.selections(request).get(0);
        boolean sync = "SYNC".equals(request.path("action").asText());
        Object outcome;
        switch (selected.target()) {
            case "FUND_NAV_CHANGE", "FUND_NAV_UP" ->
                    outcome = sync ? funds.sync(selected.asset()) : funds.train(selected.asset());
            case "GOLD_BENCHMARK_RETURN" ->
                    outcome = sync ? gold.sync(store.json.createObjectNode()) : gold.train();
            default -> {
                if (sync) market.syncCalendar();
                LocalDate day = dates.latestExpected(Instant.now());
                if (sync) {
                    List<JsonNode> reports = new ArrayList<>();
                    for (String asset :
                            selected.asset().equals("510300")
                                    ? List.of("510300")
                                    : List.of(selected.asset(), "510300")) {
                        reports.add(
                                market.sync(
                                        Map.of(
                                                "symbol",
                                                asset,
                                                "provider",
                                                "SINA",
                                                "start",
                                                "2021-01-01",
                                                "end",
                                                day.toString(),
                                                "price_basis",
                                                "RAW")));
                    }
                    outcome = reports;
                } else if ("ETF_CLOSE_RETURN".equals(selected.target())) {
                    outcome = close.ensure(selected.asset(), selected.horizon(), day);
                } else if ("ETF_FORWARD_OPEN".equals(selected.target())) {
                    outcome = forward.ensure(selected.asset(), day);
                } else {
                    List<JsonNode> models = new ArrayList<>();
                    for (String algorithm : List.of("lightgbm", "xgboost")) {
                        models.add(
                                legacy.train(
                                        store.json.valueToTree(
                                                Map.of(
                                                        "symbol",
                                                        selected.asset(),
                                                        "algorithm",
                                                        algorithm,
                                                        "horizon",
                                                        selected.horizon(),
                                                        "start",
                                                        "2021-01-01",
                                                        "end",
                                                        day.toString(),
                                                        "nature",
                                                        "REAL",
                                                        "price_basis",
                                                        "RAW"))));
                    }
                    outcome = models;
                }
            }
        }
        return Map.of(
                "status",
                "COMPLETED",
                "asset",
                selected.asset(),
                "target",
                selected.target(),
                "action",
                request.path("action").asText(),
                "result",
                outcome);
    }
}
