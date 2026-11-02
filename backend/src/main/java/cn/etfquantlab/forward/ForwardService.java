package cn.etfquantlab.forward;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ForwardService {
    public static final String EXPERIMENT = "OPEN_H1_20261024_V2";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Value("${etf.forecast-enabled:${ETF_FORECAST_ENABLED:false}}")
    private boolean unifiedForecast;

    private final Store store;
    private final MarketService market;
    private final PredictionService legacyDates;
    private final ResearchService research;
    private final ForwardModels models;
    private final ForwardNews news;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final boolean enabled;

    /** 新实验共用既有行情和队列，账户及冻结证据完全独立。 */
    public ForwardService(
            Store store,
            MarketService market,
            PredictionService legacyDates,
            ResearchService research,
            ForwardModels models,
            ForwardNews news,
            QuantClient quant,
            TransactionTemplate tx,
            @Value("${etf.forward-enabled:false}") boolean enabled) {
        this.store = store;
        this.market = market;
        this.legacyDates = legacyDates;
        this.research = research;
        this.models = models;
        this.news = news;
        this.quant = quant;
        this.tx = tx;
        this.enabled = enabled;
    }

    /** 功能开关只影响新实验，关闭后旧数据仍可查询。 */
    public boolean enabled() {
        return enabled;
    }

    /** 串行日终严格遵循行情、到期/记账、模型、新闻、冻结依赖；初始化不收费。 */
    public synchronized Map<String, Object> run(boolean initialize) {
        if (!enabled) {
            throw new IllegalArgumentException("新实验功能开关未启用");
        }
        Instant now = Instant.now();
        var local = now.atZone(SHANGHAI);
        if (!initialize
                && (!Boolean.TRUE.equals(market.tradingDayStatus(local.toLocalDate()))
                        || local.toLocalTime().isBefore(LocalTime.of(16, 0)))) {
            return Map.of("status", "SKIPPED_NON_TRADING_SESSION", "generated_at", now);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            market.syncCalendar();
        } catch (RuntimeException error) {
            report.put("calendar_warning", ForwardModels.safeReason(error));
        }
        LocalDate day = legacyDates.latestExpected(Instant.now());
        report.put("market", prepareMarket(day, initialize));
        LocalDate[] dates = following(day);
        if (!Instant.now().isBefore(open(dates[0]))) {
            health("PREDICTION", "BLOCKED", "预测已错过对应执行开盘，等待后续合法交易时段", Map.of("signal_date", day));
            return Map.of("status", "BLOCKED_LATE_SIGNAL", "signal_date", day);
        }
        initialize(dates[0]);
        report.put("experiment_id", EXPERIMENT);
        report.put("trade_date", day);
        try {
            report.put("evaluated", evaluate(day));
            health("EVALUATION", "SUCCEEDED", "无成交预测也独立到期评价；零样本不代表已验证", report.get("evaluated"));
            report.put("settlements", settleThrough(day));
            health("MATCHING", "SUCCEEDED", "日线近似模拟；成交日市场时刻与实际记账时刻分开", report.get("settlements"));
        } catch (RuntimeException error) {
            health("MATCHING", "FAILED", ForwardModels.safeReason(error), Map.of());
            throw error;
        }
        try {
            List<JsonNode> trained = models.ensure(day);
            report.put("models", trained.stream().map(model -> model.path("id").asText()).toList());
            health("TRAINING", "SUCCEEDED", null, report.get("models"));
        } catch (RuntimeException error) {
            health("TRAINING", "FAILED", ForwardModels.safeReason(error), Map.of());
            report.put("training_failure", ForwardModels.safeReason(error));
        }
        JsonNode analysis;
        try {
            JsonNode collected = news.collect();
            report.put("news_collection", collected);
        } catch (RuntimeException error) {
            report.put(
                    "news_collection",
                    Map.of(
                            "status",
                            "COLLECTION_FAILED",
                            "reason",
                            ForwardModels.safeReason(error)));
        }
        try {
            // 原账户和模拟结算保持原协议；统一预测接管后旧解释分支不再产生重复费用。
            analysis =
                    unifiedForecast
                            ? store.json.valueToTree(
                                    Map.of(
                                            "status",
                                            "MISSING",
                                            "error_code",
                                            "UNIFIED_FORECAST_TAKES_OVER"))
                            : news.analyze(Instant.now(), !initialize);
            health(
                    "NEWS",
                    analysis.path("status").asText(),
                    analysis.path("error_code").asText(null),
                    Map.of(
                            "analysis_id",
                            analysis.path("id").asText(),
                            "collection",
                            report.get("news_collection")));
        } catch (RuntimeException error) {
            analysis =
                    store.json.valueToTree(Map.of("status", "FAILED", "error_code", "API_FAILED"));
            health(
                    "NEWS",
                    "FAILED",
                    ForwardModels.safeReason(error),
                    Map.of("collection", report.get("news_collection")));
        }
        report.put("analysis_id", analysis.path("id").asText());
        try {
            report.put("frozen", freeze(day, dates, analysis));
            health("PREDICTION", "SUCCEEDED", "冻结后不可改写；模型异常保持持仓并明确展示", report.get("frozen"));
        } catch (RuntimeException error) {
            health("PREDICTION", "FAILED", ForwardModels.safeReason(error), Map.of());
            throw error;
        }
        report.put("status", report.containsKey("training_failure") ? "DEGRADED" : "SUCCEEDED");
        report.put("generated_at", Instant.now());
        report.put("trace_id", trace());
        return report;
    }

    /** 首次仅创建四个新账户；已有批次和余额绝不重置。 */
    public void initialize(LocalDate start) {
        tx.executeWithoutResult(
                status -> {
                    store.lock("forward:initialize:" + EXPERIMENT);
                    if (store.db.queryForObject(
                                    "select count(*) from forward_experiment where id=?",
                                    Integer.class,
                                    EXPERIMENT)
                            > 0) {
                        return;
                    }
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("symbols", ForwardRules.SYMBOLS);
                    config.put("risk", research.risk());
                    config.put("instruments", research.instruments(ForwardRules.SYMBOLS));
                    config.put("cost_version", "SIM_COST_V1");
                    config.put("cost_basis", "SIMULATION_ASSUMPTIONS_BROKER_FEES_UNVERIFIED");
                    config.put("corporate_action_coverage", "UNKNOWN");
                    config.put("execution_model", "日线近似模拟；缺开盘盘口，不能保证真实市场成交");
                    config.put("adaptive_fusion", false);
                    store.db.update(
                            "insert into forward_experiment(id,strategy_version,protocol_version,label_version,nature,"
                                    + "evaluation_type,start_date,configuration) values(?,?,?,?,"
                                    + "'REAL','FORWARD_PAPER',?,?::jsonb)",
                            EXPERIMENT,
                            ForwardRules.VERSION,
                            ForwardRules.PROTOCOL,
                            ForwardRules.LABEL,
                            Date.valueOf(start),
                            store.encode(config));
                    String state =
                            """
                    {"cash":"100000.00","initial_capital":"100000.00","equity":"100000.00",
                    "peak_equity":"100000.00","locked":false,"lots":{},"receivables":[],
                    "last_prices":{},"last_trade_date":null,"cumulative_fees":"0.00","cumulative_dividends":"0.00"}
                    """;
                    for (String strategy : ForwardRules.STRATEGIES) {
                        store.db.update(
                                "insert into forward_account(id,experiment_id,strategy,"
                                        + "initial_capital,state) values(?,?,?,100000,?::jsonb)",
                                EXPERIMENT + ":" + strategy,
                                EXPERIMENT,
                                strategy,
                                state);
                    }
                    store.audit(
                            "FORWARD_EXPERIMENT_CREATED",
                            Map.of("experiment_id", EXPERIMENT, "start_date", start));
                });
    }

    /** 查询同批次账户，不读取或写入旧paper_account。 */
    public List<JsonNode> accounts() {
        return store.db.query(
                "select row_to_json(a)::text from forward_account a where experiment_id=? order by strategy",
                (rs, row) -> store.decode(rs.getString(1)),
                EXPERIMENT);
    }

    /** 读取批次冻结的风险成本配置，未来全局配置变化不改变本批对照条件。 */
    public JsonNode experiment() {
        return store
                .db
                .query(
                        "select row_to_json(e)::text from forward_experiment e where id=?",
                        (rs, row) -> store.decode(rs.getString(1)),
                        EXPERIMENT)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** 新浪保留既有增量抓取；本日行情缺失时由腾讯补齐，仍要求三标的同日完整。 */
    public Map<String, Object> prepareMarket(LocalDate day, boolean initialize) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> sources = new LinkedHashMap<>();
        details.put("market_date", day);
        details.put("sources", sources);
        try {
            if (!initialize) {
                for (String symbol : ForwardRules.SYMBOLS) {
                    Map<String, Object> attempts = new LinkedHashMap<>();
                    sources.put(symbol, attempts);
                    try {
                        attempts.put(
                                "SINA",
                                market.sync(
                                        Map.of(
                                                "symbol",
                                                symbol,
                                                "start",
                                                day.minusDays(10).toString(),
                                                "end",
                                                day.toString(),
                                                "provider",
                                                "SINA",
                                                "price_basis",
                                                "RAW")));
                    } catch (RuntimeException error) {
                        // 不传播外部异常原文；一个来源失败仍允许使用已保存且通过校验的真实行情。
                        attempts.put("SINA", Map.of("error", error.getClass().getSimpleName()));
                    }
                    if (market.bars(symbol, day, day, "REAL", "RAW").isEmpty()) {
                        try {
                            // 只补缺失的本日，复用正式清洗、单位转换和落库规则，不修订其他日期。
                            attempts.put(
                                    "TENCENT",
                                    market.sync(
                                            Map.of(
                                                    "symbol",
                                                    symbol,
                                                    "start",
                                                    day.toString(),
                                                    "end",
                                                    day.toString(),
                                                    "provider",
                                                    "TENCENT",
                                                    "price_basis",
                                                    "RAW")));
                        } catch (RuntimeException error) {
                            attempts.put(
                                    "TENCENT", Map.of("error", error.getClass().getSimpleName()));
                        }
                    }
                }
            }
            List<JsonNode> current = bars(day);
            List<String> missing =
                    ForwardRules.SYMBOLS.stream()
                            .filter(
                                    symbol ->
                                            current.stream()
                                                    .noneMatch(
                                                            bar ->
                                                                    symbol.equals(
                                                                            bar.path("symbol")
                                                                                    .asText())))
                            .toList();
            details.put("count", current.size());
            details.put("missing_symbols", missing);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "日终行情缺失："
                                + day
                                + "，ETF "
                                + String.join("、", missing)
                                + "；已停止结算与预测，等待真实日线补齐");
            }
            health("MARKET", "SUCCEEDED", null, details);
            return details;
        } catch (RuntimeException error) {
            health("MARKET", "FAILED", ForwardModels.safeReason(error), details);
            throw error;
        }
    }

    /** 仅读取预期交易日、真实且未复权的有效日线，绝不以旧日期代替。 */
    private List<JsonNode> bars(LocalDate day) {
        List<JsonNode> result = new ArrayList<>();
        for (String symbol : ForwardRules.SYMBOLS) {
            result.addAll(market.bars(symbol, day, day, "REAL", "RAW"));
        }
        return result;
    }

    /** 从已确认开市日中严格取后两日，不使用自然日加减替代交易日。 */
    public LocalDate[] following(LocalDate day) {
        List<LocalDate> dates =
                store.db.query(
                        "select trade_date from trading_calendar where is_open and "
                                + "trade_date>? order by trade_date limit 2",
                        (rs, row) -> rs.getDate(1).toLocalDate(),
                        Date.valueOf(day));
        if (dates.size() != 2) {
            throw new IllegalArgumentException("缺少后续两个已确认交易日");
        }
        return new LocalDate[] {dates.get(0), dates.get(1)};
    }

    /** 将开盘市场时刻与数据库UTC时刻明确转换。 */
    public static Instant open(LocalDate date) {
        return date.atTime(9, 30).atZone(SHANGHAI).toInstant();
    }

    /** 所有模型先推理一次供四策略共用，账户冻结在事务中按批次/策略/执行日幂等。 */
    public List<Object> freeze(LocalDate day, LocalDate[] dates, JsonNode analysis) {
        Instant inputCutoff = Instant.now();
        Map<String, Map<String, Object>> frozenInputs = new LinkedHashMap<>();
        for (String symbol : ForwardRules.SYMBOLS) {
            frozenInputs.put(symbol, models.input(symbol, "lightgbm", day));
        }
        for (Map<String, Object> input : frozenInputs.values()) {
            input.put("benchmark", frozenInputs.get("510300").get("bars"));
            input.put("trading_dates", frozenInputs.get("510300").get("trading_dates"));
        }
        Map<String, JsonNode> predictions = new HashMap<>();
        for (String symbol : ForwardRules.SYMBOLS) {
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                predictions.put(
                        symbol + ":" + algorithm,
                        models.predict(
                                symbol, algorithm, day, inputCutoff, frozenInputs.get(symbol)));
            }
        }
        Instant generated = Instant.now();
        if (!generated.isBefore(open(dates[0]))) {
            throw new IllegalArgumentException("推理结束时已错过执行开盘，不能冻结迟到意图");
        }
        List<JsonNode> previousBars = new ArrayList<>();
        for (String symbol : ForwardRules.SYMBOLS) {
            for (JsonNode bar : store.json.valueToTree(frozenInputs.get(symbol).get("bars"))) {
                if (day.toString().equals(bar.path("trade_date").asText())) {
                    previousBars.add(bar);
                }
            }
        }
        JsonNode configuration = experiment().path("configuration");
        List<Object> result = new ArrayList<>();
        for (JsonNode account : accounts()) {
            result.add(
                    tx.execute(
                            status -> {
                                String accountId = account.path("id").asText();
                                store.lock("forward:account:" + accountId);
                                List<String> existing =
                                        store.db.query(
                                                "select id::text from forward_plan where account_id=? and entry_date=?",
                                                (rs, row) -> rs.getString(1),
                                                accountId,
                                                Date.valueOf(dates[0]));
                                if (!existing.isEmpty()) {
                                    return Map.of(
                                            "account_id",
                                            accountId,
                                            "status",
                                            "ALREADY_FROZEN",
                                            "plan_id",
                                            existing.get(0));
                                }
                                JsonNode state =
                                        store.db.queryForObject(
                                                "select state::text from forward_account where id=? for update",
                                                (rs, row) -> store.decode(rs.getString(1)),
                                                accountId);
                                String strategy = account.path("strategy").asText();
                                List<Map<String, Object>> rows = new ArrayList<>();
                                for (String symbol : ForwardRules.SYMBOLS) {
                                    String algorithm =
                                            "XGB_BASE".equals(strategy) ? "xgboost" : "lightgbm";
                                    Map<String, Object> row =
                                            ForwardRules.decide(
                                                    strategy,
                                                    symbol,
                                                    predictions.get(symbol + ":" + algorithm),
                                                    news.item(analysis, symbol, generated));
                                    row.put("analysis_id", analysis.path("id").asText());
                                    row.put("trace_id", trace());
                                    rows.add(row);
                                }
                                ForwardRules.allocate(rows, weights(state, previousBars));
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("account_id", accountId);
                                payload.put("experiment_id", EXPERIMENT);
                                payload.put("strategy_version", ForwardRules.VERSION);
                                payload.put("signal_date", day.toString());
                                payload.put("entry_date", dates[0].toString());
                                payload.put("evaluation_date", dates[1].toString());
                                payload.put("generated_at", generated.toString());
                                payload.put("state", state);
                                payload.put("previous_bars", previousBars);
                                payload.put("instruments", configuration.path("instruments"));
                                payload.put(
                                        "targets",
                                        rows.stream()
                                                .map(
                                                        row -> {
                                                            Map<String, Object> target =
                                                                    new LinkedHashMap<>();
                                                            target.put("symbol", row.get("symbol"));
                                                            target.put(
                                                                    "target_weight",
                                                                    row.get("target_weight"));
                                                            return target;
                                                        })
                                                .toList());
                                payload.put("trading_dates", research.calendar(day, dates[1]));
                                payload.put("risk", configuration.path("risk"));
                                payload.put("nature", "REAL");
                                payload.put(
                                        "corporate_actions",
                                        research.verifiedActions(ForwardRules.SYMBOLS, dates[0]));
                                payload.put("trace_id", trace());
                                JsonNode plan = quant.post("/forward/paper/freeze", payload);
                                validatePlan(plan, accountId, dates[0]);
                                if (!Instant.now().isBefore(open(dates[0]))) {
                                    throw new IllegalArgumentException("冻结入库已越过执行开盘，拒绝迟到意图");
                                }
                                UUID planId = UUID.randomUUID();
                                store.db.update(
                                        "insert into forward_plan(id,experiment_id,account_id,"
                                                + "entry_date,generated_at,details) values(?,?,?,?,?,"
                                                + "?::jsonb)",
                                        planId,
                                        EXPERIMENT,
                                        accountId,
                                        Date.valueOf(dates[0]),
                                        Timestamp.from(generated),
                                        store.encode(plan));
                                for (Map<String, Object> row : rows) {
                                    store.db.update(
                                            "insert into forward_decision(id,experiment_id,account_id,"
                                                    + "plan_id,symbol,signal_date,entry_date,"
                                                    + "evaluation_date,generated_at,details) values(?,?,?,?,?,?,"
                                                    + "?,?,?,?::jsonb)",
                                            UUID.randomUUID(),
                                            EXPERIMENT,
                                            accountId,
                                            planId,
                                            row.get("symbol"),
                                            Date.valueOf(day),
                                            Date.valueOf(dates[0]),
                                            Date.valueOf(dates[1]),
                                            Timestamp.from(generated),
                                            store.encode(row));
                                }
                                return Map.of(
                                        "account_id",
                                        accountId,
                                        "status",
                                        "FROZEN",
                                        "plan_id",
                                        planId);
                            }));
        }
        return result;
    }

    /** 缺失目标的既有市值预留仓位，避免其他标的分配侵占其资金。 */
    private Map<String, BigDecimal> weights(JsonNode state, List<JsonNode> bars) {
        Map<String, BigDecimal> values = new HashMap<>();
        BigDecimal equity = new BigDecimal(state.path("cash").asText());
        for (JsonNode receivable : state.path("receivables")) {
            equity = equity.add(new BigDecimal(receivable.path("amount").asText()));
        }
        for (JsonNode bar : bars) {
            String symbol = bar.path("symbol").asText();
            BigDecimal quantity = BigDecimal.ZERO;
            for (JsonNode lot : state.path("lots").path(symbol)) {
                quantity = quantity.add(new BigDecimal(lot.path("quantity").asText()));
            }
            BigDecimal value = quantity.multiply(new BigDecimal(bar.path("close").asText()));
            values.put(symbol, value);
            equity = equity.add(value);
        }
        BigDecimal denominator = equity;
        values.replaceAll(
                (symbol, value) ->
                        denominator.signum() > 0
                                ? value.divide(denominator, MathContext.DECIMAL64)
                                : BigDecimal.ZERO);
        return values;
    }

    /** 按正式日历逐日追记真实冻结计划；缺计划只估值，不能生成过去开盘订单。 */
    public List<Object> settleThrough(LocalDate through) {
        List<Object> output = new ArrayList<>();
        LocalDate start = LocalDate.parse(experiment().path("start_date").asText());
        for (String day : research.calendar(start, through)) {
            LocalDate date = LocalDate.parse(day);
            List<JsonNode> current = bars(date);
            if (current.size() != 3) {
                throw new IllegalArgumentException("待结算日行情不完整：" + day);
            }
            for (JsonNode account : accounts()) {
                output.add(settleAccount(account.path("id").asText(), date, current));
            }
        }
        return output;
    }

    /** 状态、订单、费用和净值一起落同一不可变流水；重试和重启不重复扣款。 */
    public Map<String, Object> settleAccount(
            String accountId, LocalDate day, List<JsonNode> current) {
        return tx.execute(
                status -> {
                    store.lock("forward:account:" + accountId);
                    JsonNode account =
                            store.db.queryForObject(
                                    "select row_to_json(a)::text from forward_account a where id=? for update",
                                    (rs, row) -> store.decode(rs.getString(1)),
                                    accountId);
                    if (account.hasNonNull("last_trade_date")
                            && !day.isAfter(
                                    LocalDate.parse(account.path("last_trade_date").asText()))) {
                        return Map.of("account_id", accountId, "status", "ALREADY_PROCESSED");
                    }
                    if (Instant.now().isBefore(day.atTime(15, 15).atZone(SHANGHAI).toInstant())) {
                        throw new IllegalArgumentException("未到完整日线记账时刻");
                    }
                    if (!EXPERIMENT.equals(account.path("experiment_id").asText())) {
                        throw new IllegalArgumentException("拒绝跨批次账户结算");
                    }
                    if (store.db.queryForObject(
                                    "select count(*) from corporate_action where not verified and ex_date=?"
                                            + " and symbol in ('510300','510500','159915')",
                                    Integer.class,
                                    Date.valueOf(day))
                            > 0) {
                        throw new IllegalArgumentException(
                                "BLOCKED_UNVERIFIED_CORPORATE_ACTION：存在未核实公司行动，停止该日记账");
                    }
                    List<JsonNode> plans =
                            store.db.query(
                                    "select details::text from forward_plan where account_id=? and entry_date=?",
                                    (rs, row) -> store.decode(rs.getString(1)),
                                    accountId,
                                    Date.valueOf(day));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("state", account.path("state"));
                    payload.put("bars", current);
                    payload.put("booked_at", Instant.now().toString());
                    payload.put(
                            "corporate_actions",
                            research.verifiedActions(ForwardRules.SYMBOLS, day));
                    String endpoint = "/forward/paper/step";
                    if (plans.isEmpty()) {
                        endpoint = "/forward/paper/value";
                        payload.put("trade_date", day.toString());
                        payload.put(
                                "instruments",
                                experiment().path("configuration").path("instruments"));
                        payload.put("risk", experiment().path("configuration").path("risk"));
                        payload.put("nature", "REAL");
                    } else {
                        validatePlan(plans.get(0), accountId, day);
                        payload.put("plan", plans.get(0));
                    }
                    JsonNode result = quant.post(endpoint, payload);
                    JsonNode state = result.path("state");
                    if (!List.of("SETTLED", "VALUED_NO_FROZEN_PLAN")
                                    .contains(result.path("status").asText())
                            || !"REAL".equals(result.path("snapshot").path("nature").asText())
                            || !"FORWARD_PAPER"
                                    .equals(
                                            result.path("snapshot")
                                                    .path("evaluation_type")
                                                    .asText())
                            || state.isMissingNode()
                            || new BigDecimal(state.path("cash").asText("-1")).signum() < 0
                            || !day.toString().equals(state.path("last_trade_date").asText())) {
                        throw new IllegalStateException("撮合未返回有效已结算状态，不推进账户");
                    }
                    store.db.update(
                            "insert into forward_settlement(id,account_id,trade_date,"
                                    + "booked_at,details) values(?,?,?,?,?::jsonb)",
                            UUID.randomUUID(),
                            accountId,
                            Date.valueOf(day),
                            Timestamp.from(Instant.now()),
                            store.encode(result));
                    store.db.update(
                            "update forward_account set state=?::jsonb,last_trade_date=? where id=?",
                            store.encode(state),
                            Date.valueOf(day),
                            accountId);
                    return Map.of(
                            "account_id",
                            accountId,
                            "trade_date",
                            day,
                            "status",
                            "SETTLED",
                            "fills",
                            result.path("fills").size());
                });
    }

    /** 接口成功状态仍需核对冻结计划身份，mock或跨批次结果不能进入真实账本。 */
    private void validatePlan(JsonNode plan, String account, LocalDate entry) {
        if (!account.equals(plan.path("account_id").asText())
                || !EXPERIMENT.equals(plan.path("experiment_id").asText())
                || !ForwardRules.VERSION.equals(plan.path("strategy_version").asText())
                || !ForwardRules.PROTOCOL.equals(plan.path("protocol_version").asText())
                || !"REAL".equals(plan.path("nature").asText())
                || !"FORWARD_PAPER".equals(plan.path("evaluation_type").asText())
                || !entry.toString().equals(plan.path("entry_date").asText())
                || !plan.path("plan_hash").asText().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("冻结计划身份或摘要非法，拒绝真实账本写入");
        }
    }

    /** 到期预测与账户无关：零成交、空仓的有效概率也按严格开盘标签评价。 */
    public Map<String, Object> evaluate(LocalDate day) {
        int evaluated = 0;
        int isolated = 0;
        List<JsonNode> due =
                store.db.query(
                        "select row_to_json(d)::text from forward_decision d"
                                + " left join forward_actual a on a.decision_id=d.id where "
                                + "d.experiment_id=? and a.decision_id is null"
                                + " and d.evaluation_date<=? order by d.evaluation_date",
                        (rs, row) -> store.decode(rs.getString(1)),
                        EXPERIMENT,
                        Date.valueOf(day));
        for (JsonNode row : due) {
            String symbol = row.path("symbol").asText();
            LocalDate entry = LocalDate.parse(row.path("entry_date").asText());
            LocalDate end = LocalDate.parse(row.path("evaluation_date").asText());
            List<JsonNode> from = market.bars(symbol, entry, entry, "REAL", "RAW");
            List<JsonNode> to = market.bars(symbol, end, end, "REAL", "RAW");
            if (from.size() != 1 || to.size() != 1) {
                continue;
            }
            boolean action =
                    store.db.queryForObject(
                                    "select count(*) from corporate_action where symbol=? and ex_date between ? and ?",
                                    Integer.class,
                                    symbol,
                                    Date.valueOf(entry),
                                    Date.valueOf(end))
                            > 0;
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("status", action ? "ISOLATED_CORPORATE_ACTION" : "EVALUATED");
            actual.put("label_version", ForwardRules.LABEL);
            actual.put("target_semantics", ForwardRules.TARGET);
            actual.put("corporate_action_coverage", "UNKNOWN");
            actual.put("entry_bar", from.get(0));
            actual.put("evaluation_bar", to.get(0));
            if (!action) {
                BigDecimal r =
                        new BigDecimal(to.get(0).path("open").asText())
                                .divide(
                                        new BigDecimal(from.get(0).path("open").asText()),
                                        MathContext.DECIMAL128)
                                .subtract(BigDecimal.ONE);
                actual.put("actual_return", r);
                actual.put("actual_up", r.signum() > 0);
                JsonNode probability = row.path("details").path("ml").path("probability");
                if (probability.isNumber()
                        && "SUCCEEDED"
                                .equals(row.path("details").path("ml").path("status").asText())) {
                    BigDecimal difference =
                            probability
                                    .decimalValue()
                                    .subtract(r.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
                    actual.put("ml_brier_score", difference.multiply(difference));
                    actual.put(
                            "probability_source", "MACHINE_LEARNING_PROBABILITY_NOT_FUSION_SCORE");
                }
            }
            tx.executeWithoutResult(
                    status -> {
                        UUID id = UUID.fromString(row.path("id").asText());
                        store.lock("forward:actual:" + id);
                        if (store.db.queryForObject(
                                        "select count(*) from forward_actual where decision_id=?",
                                        Integer.class,
                                        id)
                                == 0) {
                            store.db.update(
                                    "insert into forward_actual(decision_id,details) values(?,?::jsonb)",
                                    id,
                                    store.encode(actual));
                        }
                    });
            if (action) {
                isolated++;
            } else {
                evaluated++;
            }
        }
        return Map.of("evaluated", evaluated, "isolated", isolated);
    }

    /** 健康只保留当前投影，真实任务结果与操作审计另存历史；失败不擦除上次成功时刻。 */
    public void health(String stage, String status, String reason, Object details) {
        store.db.update(
                "insert into forward_health(stage,status,last_attempt_at,last_success_at,reason,trace_id,details)"
                        + " values(?,?,now(),case when ?='SUCCEEDED' then now() else null end,?,?,?::jsonb)"
                        + " on conflict(stage) do update set status=excluded.status,"
                        + "last_attempt_at=excluded.last_attempt_at,"
                        + " last_success_at=coalesce(excluded.last_success_at,"
                        + "forward_health.last_success_at),reason=excluded.reason,"
                        + " trace_id=excluded.trace_id,details=excluded.details",
                stage,
                status,
                status,
                reason,
                trace(),
                store.encode(details));
    }

    /** 当前trace贯穿队列、HTTP、预测、意图及阶段健康。 */
    private String trace() {
        String value = MDC.get("trace_id");
        return value == null ? "manual-" + UUID.randomUUID() : value;
    }
}
