package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardModels;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardRules;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
class ForwardProtocolTest {
    @Autowired private Store store;
    @Autowired private ForwardService forward;
    @Autowired private ApplicationContext context;
    @MockitoBean private QuantClient quant;

    /** 所有模拟分析、账户和行情夹具只能进入隔离测试库，不访问真实模型。 */
    @BeforeEach
    void isolatedDatabaseOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** A/B的0.55采用闭边界，D的0.1采用严格开边界，禁止悄悄放宽。 */
    @Test
    void probabilityAndScoreThresholdBoundariesDiffer() {
        assertThat(decide("LGBM_BASE", ml("0.55"), missing("NO_RELEVANT_NEWS")))
                .containsEntry("eligible", true);
        assertThat(decide("LGBM_BASE", ml("0.5499999999"), missing("NO_RELEVANT_NEWS")))
                .containsEntry("eligible", false);
        assertThat(decide("FUSION_EXPERIMENT", ml("0.55"), news("0.1", "low")))
                .containsEntry("eligible", false)
                .containsEntry("score", new BigDecimal("0.100"));
        assertThat(decide("FUSION_EXPERIMENT", ml("0.55"), news("0.1000000001", "low")))
                .containsEntry("eligible", true);
        ObjectNode xgb = ml("0.55").put("algorithm", "xgboost");
        assertThat(decide("XGB_BASE", xgb, missing("NO_RELEVANT_NEWS")))
                .containsEntry("eligible", true);
    }

    /** 缺新闻、接口错误、超时和非法输出都记录相应降级原因，资格与A一致。 */
    @Test
    void unusableNewsFallsBackToExactlySameMachineLearningRule() {
        for (String reason :
                List.of("NO_RELEVANT_NEWS", "API_FAILED", "API_TIMEOUT", "INVALID_OUTPUT")) {
            for (String probability : List.of("0.54", "0.55", "0.70")) {
                var baseline = decide("LGBM_BASE", ml(probability), missing(reason));
                for (String strategy : List.of("LGBM_MATCHED_CONTROL", "FUSION_EXPERIMENT")) {
                    var row = decide(strategy, ml(probability), missing(reason));
                    assertThat(row)
                            .containsEntry("effective_mode", "ML_FALLBACK")
                            .containsEntry("fallback_reason", reason)
                            .containsEntry("eligible", baseline.get("eligible"));
                }
            }
        }
    }

    /** 真正有效的high不是失败，D清零目标并保留风险否决，C仍忽略新闻风险。 */
    @Test
    void validHighRiskCannotEscapeThroughFallback() {
        var fusion = decide("FUSION_EXPERIMENT", ml("0.9"), news("1", "high"));
        assertThat(fusion)
                .containsEntry("news_available", true)
                .containsEntry("effective_mode", "FUSION")
                .containsEntry("eligible", false)
                .containsEntry("target_weight", BigDecimal.ZERO)
                .containsEntry("reason_code", "NEWS_RISK_VETO")
                .doesNotContainKey("fallback_reason");
        assertThat(decide("LGBM_MATCHED_CONTROL", ml("0.9"), news("1", "high")))
                .containsEntry("eligible", true);
    }

    /** S=0是成功的中性分析，其门槛不等于缺新闻时A的0.55门槛。 */
    @Test
    void validNeutralNewsDiffersFromNoNews() {
        var neutral = decide("FUSION_EXPERIMENT", ml("0.56"), news("0", "medium"));
        var unavailable = decide("FUSION_EXPERIMENT", ml("0.56"), missing("NO_RELEVANT_NEWS"));
        assertThat(neutral)
                .containsEntry("news_available", true)
                .containsEntry("effective_mode", "FUSION")
                .containsEntry("eligible", false);
        assertThat(unavailable)
                .containsEntry("news_available", false)
                .containsEntry("effective_mode", "ML_FALLBACK")
                .containsEntry("eligible", true);
    }

    /** 中性且非high时，C/D同一标的资格及组合目标必须完全一致。 */
    @Test
    void matchedControlAndFusionAreIdenticalForNeutralNews() {
        List<Map<String, Object>> control = new ArrayList<>();
        List<Map<String, Object>> fusion = new ArrayList<>();
        String[] probabilities = {"0.56", "0.60", "0.71"};
        for (int index = 0; index < ForwardRules.SYMBOLS.size(); index++) {
            String symbol = ForwardRules.SYMBOLS.get(index);
            ObjectNode model = ml(probabilities[index]).put("symbol", symbol);
            ObjectNode neutral = news("0", "medium").put("symbol", symbol);
            control.add(ForwardRules.decide("LGBM_MATCHED_CONTROL", symbol, model, neutral));
            fusion.add(ForwardRules.decide("FUSION_EXPERIMENT", symbol, model, neutral));
        }
        ForwardRules.allocate(control, Map.of());
        ForwardRules.allocate(fusion, Map.of());
        for (int index = 0; index < control.size(); index++) {
            assertThat(fusion.get(index).get("eligible"))
                    .isEqualTo(control.get(index).get("eligible"));
            assertThat(fusion.get(index).get("target_weight"))
                    .isEqualTo(control.get(index).get("target_weight"));
        }
    }

    /** 已标AVAILABLE但字段非法或期限/标的不匹配时必须记录INVALID_OUTPUT。 */
    @Test
    void invalidNewsCannotMasqueradeAsAvailableNeutralAnalysis() {
        List<ObjectNode> invalid =
                List.of(
                        news("0", "low").put("horizon", 5),
                        news("0", "low").put("symbol", "159915"),
                        news("0", "critical"),
                        news("2", "low"),
                        news("0", "low").putNull("sentiment_score"),
                        news("0", "low").put("relevant_source_ids", "not-an-array"),
                        news("0", "low").set("relevant_source_ids", store.json.createArrayNode()));
        for (ObjectNode value : invalid) {
            assertThat(decide("FUSION_EXPERIMENT", ml("0.7"), value))
                    .containsEntry("news_available", false)
                    .containsEntry("effective_mode", "ML_FALLBACK")
                    .containsEntry("fallback_reason", "INVALID_OUTPUT");
        }
    }

    /** 原始/校准概率缺失或越界时是无目标保持，不能自动清仓或伪造0概率。 */
    @Test
    void invalidMachineLearningOutputDoesNotBecomeZeroTarget() {
        for (ObjectNode value :
                List.of(
                        ml("0.7").put("status", "FAILED"),
                        ml("1.1"),
                        ml("-0.1"),
                        ml("0.7").putNull("probability"),
                        ml("0.7").putNull("raw_probability"))) {
            var row = decide("FUSION_EXPERIMENT", value, news("1", "low"));
            assertThat(row).containsEntry("eligible", false).containsEntry("target_weight", null);
            assertThat(row).doesNotContainKey("raw_probability");
        }
    }

    /** 消费端再次核对标签/特征/协议/期限/角色/品种/算法，旧模型不能误入。 */
    @Test
    void modelIdentityMustMatchEveryProtocolDimension() {
        Map<String, String> mismatches =
                Map.ofEntries(
                        Map.entry("symbol", "159915"), Map.entry("algorithm", "xgboost"),
                        Map.entry("feature_version", "ohlcv-v1"),
                                Map.entry("label_version", "CLOSE_TO_CLOSE"),
                        Map.entry("protocol_version", "OLD_PROTOCOL"),
                                Map.entry("target_semantics", "CLOSE_UP"),
                        Map.entry("model_role", "LEGACY_EVALUATION_MODEL"),
                                Map.entry("nature", "DEMO"),
                        Map.entry("price_basis", "QFQ"), Map.entry("model_id", ""));
        for (var item : mismatches.entrySet()) {
            var row =
                    decide(
                            "LGBM_BASE",
                            ml("0.9").put(item.getKey(), item.getValue()),
                            news("1", "low"));
            assertThat(row)
                    .containsEntry("target_weight", null)
                    .containsEntry("eligible", false)
                    .containsEntry("reason_code", "MODEL_IDENTITY_MISMATCH");
        }
        assertThat(decide("LGBM_BASE", ml("0.9").put("horizon", 5), news("1", "low")))
                .containsEntry("reason_code", "MODEL_IDENTITY_MISMATCH");
    }

    /** 缺目标持仓先占用组合额度，剩余合格目标等分且不融资。 */
    @Test
    void missingTargetReservesExistingExposureWithoutForcedLiquidation() {
        var unavailable =
                decide("LGBM_BASE", ml("0.7").put("status", "FAILED"), missing("NO_RELEVANT_NEWS"));
        var eligible =
                ForwardRules.decide(
                        "LGBM_BASE",
                        "510500",
                        ml("0.7").put("symbol", "510500"),
                        missing("NO_RELEVANT_NEWS"));
        ForwardRules.allocate(
                List.of(unavailable, eligible), Map.of("510300", new BigDecimal("0.30")));
        assertThat(unavailable).containsEntry("target_weight", null);
        assertThat((BigDecimal) eligible.get("target_weight")).isEqualByComparingTo("0.1");
    }

    /** 新旧表并存；新实验计划、决策、实际结果和流水的数据库冻结约束拒绝改写。 */
    @Test
    void databaseFreezesNewProtocolRecordsWithoutChangingLegacyHistory() {
        String oldDigest = legacyDigest();
        forward.initialize(LocalDate.of(2026, 10, 26));
        UUID decision = seedDecision("2026-10-23", "2026-10-26", "2026-10-27");
        UUID plan =
                store.db.queryForObject(
                        "select plan_id from forward_decision where id=?", UUID.class, decision);
        store.db.update(
                "insert into forward_actual(decision_id,details) values(?,'{\"status\":\"TEST\"}')",
                decision);
        UUID settlement = UUID.randomUUID();
        store.db.update(
                "insert into forward_settlement(id,account_id,trade_date,"
                        + "booked_at,details) values(?,?,'2026-10-26',now(),'{}')",
                settlement,
                accountId());
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "update forward_plan set details='{}' where id=?", plan))
                .hasMessageContaining("Frozen predictions cannot be changed");
        assertThatThrownBy(
                        () -> store.db.update("delete from forward_decision where id=?", decision))
                .hasMessageContaining("Frozen predictions cannot be changed");
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "update forward_actual set details='{}' where decision_id=?",
                                        decision))
                .hasMessageContaining("Frozen predictions cannot be changed");
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "delete from forward_settlement where id=?", settlement))
                .hasMessageContaining("Frozen predictions cannot be changed");
        assertThat(legacyDigest()).isEqualTo(oldDigest);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from flyway_schema_history where version='10' and success",
                                Integer.class))
                .isEqualTo(1);
    }

    /** 不曾成交的低概率预测到期照常评价，Brier取ML概率而非融合分数。 */
    @Test
    void untradedPredictionCanMatureIndependentlyOfAccountSettlement() {
        forward.initialize(LocalDate.of(2026, 10, 26));
        marketBar("2026-08-17", "10");
        marketBar("2026-08-18", "11");
        UUID decision = seedDecision("2026-08-14", "2026-08-17", "2026-08-18");
        forward.evaluate(LocalDate.of(2026, 8, 18));
        JsonNode actual =
                store.decode(
                        store.db.queryForObject(
                                "select details::text from forward_actual where decision_id=?",
                                String.class,
                                decision));
        assertThat(actual.path("status").asText()).isEqualTo("EVALUATED");
        assertThat(actual.path("actual_return").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(actual.path("ml_brier_score").decimalValue()).isEqualByComparingTo("0.36");
        assertThat(actual.path("probability_source").asText())
                .isEqualTo("MACHINE_LEARNING_PROBABILITY_NOT_FUSION_SCORE");
        forward.evaluate(LocalDate.of(2026, 8, 18));
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from forward_actual where decision_id=?",
                                Integer.class,
                                decision))
                .isEqualTo(1);
    }

    /** 并发、重复及重新构造服务实例只能结算一次，不重复现金扣费或追加流水。 */
    @Test
    void concurrentSettlementAndRestartKeepSingleFrozenPlanAccounting() throws Exception {
        forward.initialize(LocalDate.of(2026, 10, 26));
        String account = "ISOLATED_FORWARD_ACCOUNT_" + UUID.randomUUID();
        String initial =
                """
                {"cash":"100000.00","initial_capital":"100000.00","equity":"100000.00",
                 "peak_equity":"100000.00","locked":false,"lots":{},"receivables":[],
                 "last_prices":{},"last_trade_date":null,"cumulative_fees":"0.00",
                 "cumulative_dividends":"0.00"}
                """;
        store.db.update(
                "insert into forward_account(id,experiment_id,strategy,initial_capital,state)"
                        + " values(?,?,'LGBM_BASE',100000,?::jsonb)",
                account,
                ForwardService.EXPERIMENT,
                initial);
        UUID planId = UUID.randomUUID();
        ObjectNode plan = store.json.createObjectNode();
        plan.put("account_id", account);
        plan.put("experiment_id", ForwardService.EXPERIMENT);
        plan.put("strategy_version", ForwardRules.VERSION);
        plan.put("protocol_version", ForwardRules.PROTOCOL);
        plan.put("cost_version", "SIM_COST_V1");
        plan.put("nature", "REAL");
        plan.put("evaluation_type", "FORWARD_PAPER");
        plan.put("entry_date", "2026-08-17");
        plan.put("generated_at", "2026-08-14T09:30:00Z");
        plan.put("plan_hash", "a".repeat(64));
        store.db.update(
                "insert into forward_plan(id,experiment_id,account_id,entry_date,generated_at,details)"
                        + " values(?,?,?,'2026-08-17','2026-08-14T09:30:00Z',?::jsonb)",
                planId,
                ForwardService.EXPERIMENT,
                account,
                store.encode(plan));
        ObjectNode result =
                (ObjectNode)
                        store.decode(
                                """
                {"status":"SETTLED","already_processed":false,
                 "state":{"cash":"95995.00","initial_capital":"100000.00","equity":"99995.00",
                 "peak_equity":"100000.00","locked":false,"last_trade_date":"2026-08-17",
                 "lots":{"510300":[{"quantity":"400","cost":"4005.00","acquired":"2026-08-17"}]},
                 "receivables":[],"last_prices":{"510300":"10"},
                 "cumulative_fees":"5.00","cumulative_dividends":"0.00"},
                 "orders":[{"business_key":"ISOLATED_ORDER","symbol":"510300","side":"BUY",
                 "status":"FILLED","quantity":"400","price":"10.000","fee":"5.00","nature":"REAL"}],
                 "fills":[{"business_key":"ISOLATED_ORDER","symbol":"510300","side":"BUY",
                 "quantity":"400","price":"10.000","gross":"4000.00","fee":"5.00","nature":"REAL"}],
                 "snapshot":{"trade_date":"2026-08-17","equity":"99995.00","cash":"95995.00",
                 "position_value":"4000.00","nature":"REAL","evaluation_type":"FORWARD_PAPER"},
                 "risk_events":[],"diagnostics":[]}
                """);
        when(quant.post(eq("/forward/paper/step"), any())).thenReturn(result);
        LocalDate day = LocalDate.of(2026, 8, 17);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> forward.settleAccount(account, day, List.of()));
            var second = pool.submit(() -> forward.settleAccount(account, day, List.of()));
            assertThat(List.of(first.get().get("status"), second.get().get("status")))
                    .containsExactlyInAnyOrder("SETTLED", "ALREADY_PROCESSED");
        } finally {
            pool.shutdownNow();
        }
        // 新实例没有旧对象的内存状态，幂等性仍必须来自数据库锁与持久结算日。
        ForwardService restarted =
                new ForwardService(
                        store,
                        context.getBean(MarketService.class),
                        context.getBean(PredictionService.class),
                        context.getBean(ResearchService.class),
                        context.getBean(ForwardModels.class),
                        context.getBean(ForwardNews.class),
                        quant,
                        context.getBean(TransactionTemplate.class),
                        false);
        assertThat(restarted.settleAccount(account, day, List.of()).get("status"))
                .isEqualTo("ALREADY_PROCESSED");
        JsonNode persisted =
                store.decode(
                        store.db.queryForObject(
                                "select state::text from forward_account where id=?",
                                String.class,
                                account));
        assertThat(persisted.path("cash").asText()).isEqualTo("95995.00");
        assertThat(persisted.path("cumulative_fees").asText()).isEqualTo("5.00");
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from forward_settlement where account_id=?",
                                Integer.class,
                                account))
                .isEqualTo(1);
        verify(quant, times(1)).post(eq("/forward/paper/step"), any());
    }

    /** HTTP200不能代替业务成功，失败、测试或历史快照均不得推进真实实验账本。 */
    @Test
    void successfulHttpWithFailedOrNonRealBusinessResultCannotBookAccount() {
        forward.initialize(LocalDate.of(2026, 10, 26));
        String account = "ISOLATED_RESULT_REJECTION_" + UUID.randomUUID();
        String initial =
                """
                {"cash":"100000.00","initial_capital":"100000.00","equity":"100000.00",
                 "peak_equity":"100000.00","locked":false,"lots":{},"receivables":[],
                 "last_prices":{},"last_trade_date":null,"cumulative_fees":"0.00",
                 "cumulative_dividends":"0.00"}
                """;
        store.db.update(
                "insert into forward_account(id,experiment_id,strategy,initial_capital,state)"
                        + " values(?,?,'LGBM_BASE',100000,?::jsonb)",
                account,
                ForwardService.EXPERIMENT,
                initial);
        ObjectNode result =
                (ObjectNode)
                        store.decode(
                                """
                {"status":"VALUED_NO_FROZEN_PLAN","state":{"cash":"100000.00",
                 "equity":"100000.00","last_trade_date":"2026-08-17"},"orders":[],"fills":[],
                 "snapshot":{"trade_date":"2026-08-17","equity":"100000.00",
                 "cash":"100000.00","nature":"REAL","evaluation_type":"FORWARD_PAPER"}}
                """);
        ObjectNode failed = result.deepCopy().put("status", "FAILED");
        ObjectNode mock = result.deepCopy();
        ((ObjectNode) mock.path("snapshot")).put("nature", "TEST");
        ObjectNode historical = result.deepCopy();
        ((ObjectNode) historical.path("snapshot")).put("evaluation_type", "HISTORICAL_OOS");
        for (ObjectNode invalid : List.of(failed, mock, historical)) {
            when(quant.post(eq("/forward/paper/value"), any())).thenReturn(invalid);
            assertThatThrownBy(
                            () ->
                                    forward.settleAccount(
                                            account, LocalDate.of(2026, 8, 17), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不推进账户");
            assertThat(
                            store.db.queryForObject(
                                    "select state::text from forward_account where id=?",
                                    String.class,
                                    account))
                    .isEqualTo(
                            store.db.queryForObject(
                                    "select ?::jsonb::text", String.class, initial));
            assertThat(
                            store.db.queryForObject(
                                    "select count(*) from forward_settlement where account_id=?",
                                    Integer.class,
                                    account))
                    .isZero();
        }
    }

    /** 构造受控生产身份的隔离概率夹具；这个对象从不发往线上。 */
    private ObjectNode ml(String probability) {
        ObjectNode value = store.json.createObjectNode();
        value.put("status", "SUCCEEDED");
        value.put("symbol", "510300");
        value.put("algorithm", "lightgbm");
        value.put("horizon", 1);
        value.put("feature_version", "ohlcv-v2");
        value.put("label_version", ForwardRules.LABEL);
        value.put("protocol_version", ForwardRules.PROTOCOL);
        value.put("target_semantics", ForwardRules.TARGET);
        value.put("nature", "REAL");
        value.put("price_basis", "RAW");
        value.put("model_role", "FORWARD_PRODUCTION");
        value.put("model_id", "ISOLATED_TEST_MODEL");
        value.put("probability", new BigDecimal(probability));
        value.put("raw_probability", new BigDecimal(probability));
        return value;
    }

    /** 新闻夹具明确h1与来源，不制造真实调用或费用。 */
    private ObjectNode news(String sentiment, String risk) {
        ObjectNode value = store.json.createObjectNode();
        value.put("status", "AVAILABLE");
        value.put("symbol", "510300");
        value.put("horizon", 1);
        value.put("sentiment_score", new BigDecimal(sentiment));
        value.put("risk_level", risk);
        value.putArray("relevant_source_ids").add("ISOLATED_TEST_SOURCE");
        return value;
    }

    /** 失败状态不携带情绪数字，避免把缺新闻伪装成S=0。 */
    private ObjectNode missing(String reason) {
        return store.json.createObjectNode().put("status", "UNAVAILABLE").put("error_code", reason);
    }

    /** 简化同一标的的规则调用，所有断言仍针对实际生产规则方法。 */
    private Map<String, Object> decide(String strategy, JsonNode model, JsonNode news) {
        return ForwardRules.decide(strategy, "510300", model, news);
    }

    /** 新实验账户使用独立表，测试只引用其中一个，不重置现有状态。 */
    private String accountId() {
        return ForwardService.EXPERIMENT + ":LGBM_BASE";
    }

    /** 保存一次隔离冻结决策，无订单且低于买入阈值。 */
    private UUID seedDecision(String signal, String entry, String evaluation) {
        UUID plan = UUID.randomUUID();
        UUID decision = UUID.randomUUID();
        Instant generated =
                LocalDate.parse(signal)
                        .atTime(17, 30)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant();
        store.db.update(
                "insert into forward_plan(id,experiment_id,account_id,"
                        + "entry_date,generated_at,details) values(?,?,?,?::date,?,"
                        + "'{}')",
                plan,
                ForwardService.EXPERIMENT,
                accountId(),
                entry,
                Timestamp.from(generated));
        var details = store.json.createObjectNode();
        details.set("ml", ml("0.4"));
        details.put("score", "0.9");
        details.put("target_weight", "0");
        store.db.update(
                "insert into forward_decision(id,experiment_id,account_id,plan_id,symbol,"
                        + "signal_date,entry_date,evaluation_date,generated_at,details)"
                        + " values(?,?,?,?,'510300',?::date,?::date,?::date,?,?::jsonb)",
                decision,
                ForwardService.EXPERIMENT,
                accountId(),
                plan,
                signal,
                entry,
                evaluation,
                Timestamp.from(generated),
                store.encode(details));
        return decision;
    }

    /** 为到期评价提供明确日历和真实类型的隔离行情，不触发采集。 */
    private void marketBar(String day, String opening) {
        store.db.update(
                "insert into trading_calendar(trade_date,source) "
                        + "values(?::date,'ISOLATED_FORWARD_TEST') on conflict do "
                        + "nothing",
                day);
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,"
                        + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                        + " values(?,'510300',?::date,?::numeric,12,9,10,1000000,'RAW',"
                        + "'ISOLATED_FORWARD_TEST','REAL',?,'VALID','{}')",
                UUID.randomUUID(),
                day,
                opening,
                Timestamp.from(Instant.now()));
    }

    /** 比较旧冻结预测和模拟账本摘要，确保新表写入没有触及旧历史。 */
    private String legacyDigest() {
        return store.db.queryForObject(
                "select md5(coalesce((select string_agg(row_to_json(p)::text,'' order by id)"
                        + " from prediction p),'') || coalesce((select"
                        + " string_agg(row_to_json(a)::text,'' order by id) from paper_account a),''))",
                String.class);
    }
}
