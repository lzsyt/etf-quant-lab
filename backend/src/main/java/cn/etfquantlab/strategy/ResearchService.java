package cn.etfquantlab.strategy;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResearchService {
    private final Store store;
    private final MarketService market;
    private final QuantClient quant;
    private final TransactionTemplate tx;

    /** 研究工件由Python管理，Java持久化版本、数据范围及实验结果。 */
    public ResearchService(
            Store store, MarketService market, QuantClient quant, TransactionTemplate tx) {
        this.store = store;
        this.market = market;
        this.quant = quant;
        this.tx = tx;
    }

    /** 按明确截止日期训练，保留复现所需输入和输出；不覆盖已有模型版本。 */
    public JsonNode train(JsonNode request) {
        String symbol = request.path("symbol").asText("510300");
        String nature = request.path("nature").asText("REAL");
        LocalDate start = LocalDate.parse(request.path("start").asText("2021-01-01"));
        LocalDate end = LocalDate.parse(request.path("end").asText(LocalDate.now().toString()));
        String basis = request.path("price_basis").asText("RAW");
        market.validateInstrument(symbol);
        Map<String, Object> payload = new HashMap<>();
        payload.put("symbol", symbol);
        payload.put("algorithm", request.path("algorithm").asText("lightgbm"));
        payload.put("horizon", request.path("horizon").asInt(1));
        payload.put("seed", 42);
        payload.put("bars", market.bars(symbol, start, end, nature, basis));
        payload.put("benchmark", market.bars("510300", start, end, nature, basis));
        payload.put("trading_dates", "REAL".equals(nature) ? calendar(start, end) : List.of());
        JsonNode result = quant.post("/models/train", payload);
        tx.executeWithoutResult(
                status -> {
                    store.lock("model:" + result.path("id").asText());
                    if (store.db.queryForObject(
                                    "select count(*) from model_version where id=?",
                                    Integer.class,
                                    result.path("id").asText())
                            == 0) {
                        store.db.update(
                                "insert into model_version(id,symbol,algorithm,horizon,feature_version,"
                                        + "training_range,hyperparameters,metrics,metadata)"
                                        + " values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                                result.path("id").asText(),
                                symbol,
                                result.path("algorithm").asText(),
                                result.path("horizon").asInt(),
                                result.path("feature_version").asText(),
                                store.encode(result.path("training_range")),
                                store.encode(result.path("hyperparameters")),
                                store.encode(result.path("metrics")),
                                store.encode(result));
                        store.audit(
                                "MODEL_TRAIN",
                                Map.of("model_id", result.path("id").asText(), "nature", nature));
                    }
                });
        return result;
    }

    /** 只选择数据截止日不晚于实验截止日的模型；返回冻结的完整元数据。 */
    public JsonNode latestModel(
            String symbol, String algorithm, int horizon, String nature, LocalDate end) {
        List<JsonNode> versions =
                store.db.query(
                        "select metadata::text from model_version where symbol=?"
                                + " and algorithm=? and horizon=? and metadata->>'nature'=?"
                                + " and (metadata->>'data_cutoff')::date<=? order by trained_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        symbol,
                        algorithm,
                        horizon,
                        nature,
                        Date.valueOf(end));
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("缺少对应时间范围模型：" + symbol + "/" + algorithm);
        }
        return versions.get(0);
    }

    /** 从数据库可信交易日历取日期；演示数据在调用方显式采用样本日期。 */
    public List<String> calendar(LocalDate start, LocalDate end) {
        return store.db.query(
                "select trade_date::text from trading_calendar where is_open and trade_date between ? and ?"
                        + " order by trade_date",
                (rs, row) -> rs.getString(1),
                Date.valueOf(start),
                Date.valueOf(end));
    }

    /** 仿真约束只读取已登记品种，输出Python需要的最小交易属性。 */
    public List<Map<String, Object>> instruments(List<String> symbols) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String symbol : symbols) {
            market.validateInstrument(symbol);
            result.add(
                    store.db.queryForMap(
                            "select symbol,lot_size,settlement_days,limit_rate,tick_size,"
                                    + "rule_valid_from::text from etf_instrument where symbol=?",
                            symbol));
        }
        return result;
    }

    /** 风险配置以最近一次审计版本为准，所有比较组共用一份快照。 */
    public JsonNode risk() {
        return store
                .db
                .query(
                        "select parameters::text from risk_config_history order by changed_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)))
                .stream()
                .findFirst()
                .orElse(store.decode("{}"));
    }

    /** 多组实验共用资本、区间、成本和风控；没有历史新闻证据的实验显式排除。 */
    public JsonNode backtest(JsonNode request, String jobKey) {
        List<JsonNode> existing =
                store.db.query(
                        "select result::text from backtest_experiment"
                                + " where configuration->>'job_key'=? and status='SUCCEEDED' order by"
                                + " created_at limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        jobKey);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        List<String> symbols = new ArrayList<>();
        request.path("symbols").forEach(node -> symbols.add(node.asText()));
        if (symbols.isEmpty()) {
            symbols.add("510300");
        }
        List<String> strategies = new ArrayList<>();
        request.path("strategies").forEach(node -> strategies.add(node.asText()));
        if (strategies.isEmpty()) {
            strategies.addAll(List.of("ma", "buy_hold", "cash"));
        }
        String nature = request.path("nature").asText("REAL");
        LocalDate start = LocalDate.parse(request.path("start").asText("2024-01-01"));
        LocalDate end = LocalDate.parse(request.path("end").asText(LocalDate.now().toString()));
        Map<String, List<JsonNode>> models = new HashMap<>();
        boolean outOfSample = false;
        for (String strategy : strategies) {
            if (!List.of("lightgbm", "xgboost", "logistic").contains(strategy)) {
                continue;
            }
            outOfSample = true;
            List<JsonNode> versions = new ArrayList<>();
            for (String symbol : symbols) {
                JsonNode model =
                        latestModel(
                                symbol, strategy, request.path("horizon").asInt(1), nature, end);
                LocalDate firstSignal =
                        LocalDate.parse(
                                model.path("training_range").path("test").path("start").asText());
                List<String> next = calendar(firstSignal.plusDays(1), end);
                if (next.isEmpty() || model.path("oos_predictions").isEmpty()) {
                    throw new IllegalArgumentException(
                            "缺少严格样本外预测或其后续有效成交日：" + symbol + "/" + strategy);
                }
                LocalDate firstExecution = LocalDate.parse(next.get(0));
                if (firstExecution.isAfter(start)) {
                    start = firstExecution;
                }
                versions.add(model);
            }
            models.put(strategy, versions);
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("所选区间没有共同样本外成交窗口，请重新选择模型或日期");
        }
        List<JsonNode> bars = new ArrayList<>();
        for (String symbol : symbols) {
            bars.addAll(market.bars(symbol, LocalDate.of(2021, 1, 1), end, nature, "RAW"));
        }
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("bars", bars);
        common.put("instruments", instruments(symbols));
        common.put("start", start.toString());
        common.put("end", end.toString());
        common.put("initial_capital", request.path("initial_capital").asText("100000.00"));
        common.put("risk", request.has("risk") ? request.path("risk") : risk());
        common.put(
                "trading_dates",
                "REAL".equals(nature) ? calendar(LocalDate.of(2021, 1, 1), end) : List.of());
        common.put("evaluation_type", outOfSample ? "HISTORICAL_OOS" : "HISTORICAL_RULE");
        common.put("corporate_actions", verifiedActions(symbols, end));
        UUID experimentId = UUID.randomUUID();
        Map<String, Object> frozen = new LinkedHashMap<>(common);
        frozen.put("job_key", jobKey);
        frozen.put("request", request);
        frozen.put("models", models);
        store.db.update(
                "insert into backtest_experiment(id,status,evaluation_type,nature,configuration)"
                        + " values(?,'RUNNING',?,?,?::jsonb)",
                experimentId,
                common.get("evaluation_type"),
                nature,
                store.encode(frozen));
        try {
            Map<String, Object> results = new LinkedHashMap<>();
            for (String strategy : strategies) {
                if (List.of("llm", "fusion").contains(strategy)) {
                    results.put(
                            strategy,
                            Map.of("status", "NOT_ELIGIBLE", "reason", "缺少当时可得的历史新闻，只能进行前瞻模拟"));
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>(common);
                payload.put("strategy", strategy);
                if (models.containsKey(strategy)) {
                    List<Map<String, Object>> signals = new ArrayList<>();
                    Map<String, List<Map<String, Object>>> dailySignals = new LinkedHashMap<>();
                    for (JsonNode model : models.get(strategy)) {
                        for (JsonNode point : model.path("oos_predictions")) {
                            Map<String, Object> signal = new LinkedHashMap<>();
                            signal.put("symbol", model.path("symbol").asText());
                            signal.put("signal_date", point.path("signal_date").asText());
                            signal.put("eligible", point.path("probability").asDouble() >= .55);
                            dailySignals
                                    .computeIfAbsent(
                                            point.path("signal_date").asText(),
                                            ignored -> new ArrayList<>())
                                    .add(signal);
                        }
                    }
                    // 组合仓位按同一天所有达标ETF等权，和前瞻预测共用同一分配函数。
                    JsonNode riskSnapshot = store.json.valueToTree(common.get("risk"));
                    for (List<Map<String, Object>> group : dailySignals.values()) {
                        PredictionService.allocate(group, riskSnapshot);
                        for (Map<String, Object> signal : group) {
                            signal.remove("eligible");
                            signals.add(signal);
                        }
                    }
                    payload.put("signals", signals);
                }
                results.put(strategy, quant.post("/backtests/run", payload));
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", experimentId);
            summary.put("nature", nature);
            summary.put("evaluation_type", common.get("evaluation_type"));
            summary.put("start", start.toString());
            summary.put("end", end.toString());
            summary.put("results", results);
            summary.put("comparison", "相同资金、成本与风控；买入持有基准同样受仓位和每日买入上限约束");
            JsonNode result = store.json.valueToTree(summary);
            tx.executeWithoutResult(
                    status -> {
                        for (Object item : results.values()) {
                            if (item instanceof JsonNode strategyResult) {
                                for (JsonNode order : strategyResult.path("orders")) {
                                    store.db.update(
                                            "insert into backtest_order(id,experiment_id,business_key,details)"
                                                    + " values(?,?,?,?::jsonb)",
                                            UUID.randomUUID(),
                                            experimentId,
                                            order.path("business_key").asText(),
                                            store.encode(order));
                                }
                                for (JsonNode fill : strategyResult.path("fills")) {
                                    store.db.update(
                                            "insert into backtest_fill(id,experiment_id,details) values(?,?,?::jsonb)",
                                            UUID.randomUUID(),
                                            experimentId,
                                            store.encode(fill));
                                }
                            }
                        }
                        store.db.update(
                                "update backtest_experiment set status='SUCCEEDED',result=?::jsonb where id=?",
                                store.encode(result),
                                experimentId);
                        store.audit(
                                "BACKTEST_COMPLETE",
                                Map.of("experiment_id", experimentId, "nature", nature));
                    });
            return result;
        } catch (RuntimeException error) {
            store.db.update(
                    "update backtest_experiment set status='FAILED',error_code=? where id=?",
                    error.getClass().getSimpleName(),
                    experimentId);
            throw error;
        }
    }

    /** 只把经核实分红和拆分传给撮合引擎，没有资料时明确采用价格收益口径。 */
    public List<JsonNode> verifiedActions(List<String> symbols, LocalDate end) {
        List<JsonNode> actions = new ArrayList<>();
        for (String symbol : symbols) {
            actions.addAll(
                    store.db.query(
                            "select row_to_json(a)::text from (select symbol,ex_date,pay_date,"
                                    + "cash_per_unit::text,split_factor::text,reference_price::text,verified,source_url"
                                    + " from corporate_action where symbol=? and ex_date<=? and verified=true) a",
                            (rs, row) -> store.decode(rs.getString(1)),
                            symbol,
                            Date.valueOf(end)));
        }
        return actions;
    }
}
