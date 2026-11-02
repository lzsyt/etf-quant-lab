package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.paper.PaperService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.scheduling.TaskService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
class V11IntegrityTest {
    @Autowired private Store store;
    @Autowired private PredictionService predictions;
    @Autowired private cn.etfquantlab.prediction.PredictionController predictionController;
    @Autowired private PaperService paper;
    @Autowired private MarketService market;
    @Autowired private TaskService tasks;
    @Autowired private cn.etfquantlab.paper.PaperController paperController;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;

    /** 真实数据库断言只在隔离库执行，禁止对正式账户或预测做测试写入。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 同一份风险约束在0至3只ETF达阈值时产生确定的组合仓位。 */
    @Test
    void allocationUsesSamePortfolioLimitsForBacktestAndForward() {
        for (int eligible = 0; eligible <= 3; eligible++) {
            List<Map<String, Object>> group = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                Map<String, Object> row = new HashMap<>();
                row.put("eligible", index < eligible);
                group.add(row);
            }
            PredictionService.allocate(
                    group,
                    store.decode("{\"max_total_weight\":\"0.40\",\"max_single_weight\":\"0.20\"}"));
            BigDecimal total =
                    group.stream()
                            .map(row -> new BigDecimal(row.get("target_weight").toString()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).isLessThanOrEqualTo(new BigDecimal("0.40"));
            if (eligible == 0) {
                assertThat(total).isZero();
            } else {
                assertThat(new BigDecimal(group.get(0).get("target_weight").toString()))
                        .isEqualByComparingTo(eligible < 3 ? "0.20" : "0.1333333333");
            }
        }
    }

    /** 中证500代码不能被误标为上证50，即使其他证券字段正常。 */
    @Test
    void metadataRejectsWrongIndexMapping() {
        Map<String, Object> item =
                new HashMap<>(
                        store.db.queryForMap("select * from etf_instrument where symbol='510500'"));
        assertThat(MarketService.instrumentIssues(item)).isEmpty();
        item.put("name", "南方上证50ETF");
        item.put("tracking_index", "上证50");
        assertThat(MarketService.instrumentIssues(item)).isNotEmpty();
    }

    /** 休市公告标记优先于旧日历；未来覆盖不足必须报错而非补工作日。 */
    @Test
    void nextTradingDateSkipsExplicitClosureAndFailsOutsideCoverage() {
        calendar("2035-09-28", true);
        calendar("2035-10-01", false);
        calendar("2035-10-08", true);
        assertThat(predictions.nextDates(LocalDate.of(2035, 9, 28), 1))
                .containsExactly("2035-10-08");
        assertThatThrownBy(() -> predictions.nextDates(LocalDate.of(2090, 1, 1), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("覆盖不足");
    }

    /** 保存真实生成时刻，行情截止和目标交易日独立；冻结后数据库直接拒绝修改。 */
    @Test
    void predictionFreezesThreeDistinctTimesWithoutBackdating() {
        calendar("2024-01-04", true);
        calendar("2024-01-05", true);
        String strategy = "evidence-" + UUID.randomUUID();
        Map<String, Object> record = new HashMap<>();
        record.put("symbol", "510300");
        record.put("strategy", strategy);
        record.put("horizon", 1);
        record.put("signal_date", "2024-01-04");
        record.put("model_version", "ISOLATED_TEST");
        record.put("direction", "UP");
        record.put("probability", new BigDecimal("0.6"));
        record.put("raw_score", new BigDecimal("0.7"));
        record.put("score_semantics", "UNCALIBRATED_CLASS_PROBABILITY");
        Instant before = Instant.now().minusSeconds(1);
        assertThat(predictions.freeze(List.of(record), Instant.now())).isEqualTo(1);
        Map<String, Object> frozen =
                store.db.queryForMap("select * from prediction where strategy_id=?", strategy);
        assertThat(((Timestamp) frozen.get("created_at")).toInstant()).isAfter(before);
        assertThat(((Timestamp) frozen.get("market_cutoff")).toInstant())
                .isEqualTo(Instant.parse("2024-01-04T07:00:00Z"));
        assertThat(frozen.get("target_date").toString()).isEqualTo("2024-01-05");
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "update prediction set probability=1 where id=?",
                                        frozen.get("id")))
                .hasMessageContaining("Frozen predictions cannot be changed");
        assertThat(predictions.freeze(List.of(record), Instant.now())).isZero();
    }

    /** 本周末刚创建的历史日期预测仍只能是迟到样本，不能增加严格前瞻正确率。 */
    @Test
    void latePredictionGetsOutcomeButNoAccuracyOrBrier() {
        calendar("2024-01-04", true);
        calendar("2024-01-05", true);
        marketBar("2024-01-04", "10", Instant.now());
        marketBar("2024-01-05", "11", Instant.now());
        UUID id = insertPrediction("UP", Instant.now(), "2024-01-04", "2024-01-05");
        predictions.reconcile(LocalDate.of(2024, 1, 5));
        Map<String, Object> actual =
                store.db.queryForMap("select * from prediction_actual where prediction_id=?", id);
        assertThat(actual.get("correct")).isNull();
        assertThat(actual.get("brier_score")).isNull();
        assertThat((BigDecimal) actual.get("actual_return")).isEqualByComparingTo("0.1");
    }

    /** 价格不涨不跌不能算作下跌方向预测正确。 */
    @Test
    void flatRealizedReturnIsNotCorrectDownPrediction() {
        calendar("2024-01-08", true);
        calendar("2024-01-09", true);
        marketBar("2024-01-08", "10", Instant.now());
        marketBar("2024-01-09", "10", Instant.now());
        UUID id =
                insertPrediction(
                        "DOWN", Instant.parse("2024-01-08T09:00:00Z"), "2024-01-08", "2024-01-09");
        predictions.reconcile(LocalDate.of(2024, 1, 9));
        assertThat(
                        store.db.queryForObject(
                                "select correct from prediction_actual where prediction_id=?",
                                Boolean.class,
                                id))
                .isFalse();
    }

    /** 周末和官方休市日不调用撮合服务，不推进账户结算日期。 */
    @Test
    void settlementOnClosedDayDoesNotReachMatchingEngine() {
        String account = account();
        calendar("2024-10-01", false);
        assertThat(paper.settleAccount(account, LocalDate.of(2024, 10, 1)).get("status"))
                .isEqualTo("NON_TRADING_DAY");
        verify(quant, never()).post(eq("/paper/step"), any());
        assertThat(
                        store.db.queryForObject(
                                "select last_trade_date from paper_account where id=?",
                                java.sql.Date.class,
                                account))
                .isNull();
    }

    /** 当日盘中抓取的真实数据不能因为被标记VALID就进入模型输入。 */
    @Test
    void partialSessionBarIsExcludedFromResearch() {
        calendar("2023-11-07", true);
        marketBar("2023-11-07", "10", Instant.parse("2023-11-07T03:00:00Z"));
        assertThat(
                        market.bars(
                                "510300",
                                LocalDate.of(2023, 11, 7),
                                LocalDate.of(2023, 11, 7),
                                "REAL",
                                "RAW"))
                .isEmpty();
    }

    /** 过期worker无法续租他人的任务，新worker令牌才能更新时间。 */
    @Test
    void taskHeartbeatRequiresCurrentLeaseToken() {
        var item = tasks.enqueue("MARKET_SYNC", store.decode("{}"), UUID.randomUUID().toString());
        UUID id = (UUID) item.get("id");
        UUID lease = UUID.randomUUID();
        store.db.update(
                "update task_execution set status='RUNNING',lease_token=?,"
                        + "heartbeat_at='2000-01-01T00:00:00Z' where id=?",
                lease,
                id);
        tasks.renewLease(id, UUID.randomUUID());
        assertThat(
                        store.db
                                .queryForObject(
                                        "select heartbeat_at from task_execution where id=?",
                                        Timestamp.class,
                                        id)
                                .toInstant())
                .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
        tasks.renewLease(id, lease);
        assertThat(
                        store.db
                                .queryForObject(
                                        "select heartbeat_at from task_execution where id=?",
                                        Timestamp.class,
                                        id)
                                .toInstant())
                .isAfter(Instant.now().minusSeconds(10));
    }

    /** 没有相关性证据时不预留预算、不调用模型，即使供应商已启用。 */
    @Test
    void irrelevantNewsStopsBeforePaidCall() {
        when(quant.post(eq("/analysis/quote"), any()))
                .thenReturn(
                        store.decode(
                                "{\"enabled\":true,\"configured\":true,\"model\":\"TEST-"
                                        + UUID.randomUUID()
                                        + "\",\"model_version\":\"TEST\","
                                        + "\"prompt_version\":\"TEST\",\"reserved_usd\":\"0.01\","
                                        + "\"pricing\":{},\"eligible_source_ids\":[],"
                                        + "\"relevance\":{\"version\":\"TEST\"}}"));
        var result =
                new AnalysisService(store, quant, tx, BigDecimal.ONE, BigDecimal.ZERO)
                        .analyze(Instant.now());
        assertThat(result.path("error_code").asText()).isEqualTo("NO_RELEVANT_NEWS");
        verify(quant, never()).post(eq("/analysis/run"), any());
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from api_call_cost where id=?",
                                Integer.class,
                                UUID.fromString(result.path("id").asText())))
                .isZero();
    }

    /** 由成交费用重算资金，并与持久账户及每日快照相互核对。 */
    @Test
    void accountCashAndEquityCanBeReconstructedFromFills() {
        String account = account();
        UUID order = UUID.randomUUID();
        String state =
                "{\"cash\":\"95995.00\",\"equity\":\"99995.00\","
                        + "\"lots\":{\"510300\":[{\"quantity\":\"400\",\"cost\":\"4005.00\"}]},"
                        + "\"last_prices\":{\"510300\":\"10.00\"},\"receivables\":[],\"cumulative_dividends\":\"0\"}";
        store.db.update(
                "update paper_account set cash=95995,equity=99995,state=?::jsonb where id=?",
                state,
                account);
        store.db.update(
                "insert into paper_order(id,account_id,business_key,trade_date,symbol,side,status,details)"
                        + " values(?,?,?,'2024-01-05','510300','BUY','FILLED','{}')",
                order,
                account,
                UUID.randomUUID().toString());
        store.db.update(
                "insert into paper_fill(id,order_id,quantity,price,fee,details) values(?,?,400,10,5,'{}')",
                UUID.randomUUID(),
                order);
        store.db.update(
                "insert into account_snapshot(id,account_id,trade_date,equity,cash,details) "
                        + "values(?,?,'2024-01-05',99995,95995,?::jsonb)",
                UUID.randomUUID(),
                account,
                store.encode(Map.of("state", store.decode(state))));
        Map<String, Object> result = paper.reconstruct(account);
        assertThat(result.get("consistent")).isEqualTo(true);
        assertThat((BigDecimal) result.get("fees")).isEqualByComparingTo("5");
        store.db.update("update paper_account set cash=cash+1 where id=?", account);
        assertThat(paper.reconstruct(account).get("consistent")).isEqualTo(false);
    }

    /** 质量与账户诊断来自真实SQL结果，未开启调度时不能声称策略运行中。 */
    @Test
    void diagnosticsAndQualityReportExposeEvidenceWithoutRuntimeClaims() {
        var report = market.qualityReport();
        assertThat(report.get("items")).isInstanceOf(List.class);
        assertThat(predictionController.predictions(300))
                .allSatisfy(
                        row -> {
                            assertThat(row.has("prediction")).isTrue();
                            assertThat(row.has("actual")).isTrue();
                            assertThat(row.has("execution")).isTrue();
                        });
        var diagnostics = paperController.diagnostics(account());
        assertThat(diagnostics.get("scheduling_enabled")).isEqualTo(false);
        assertThat(diagnostics.get("reason_code")).isIn("SCHEDULER_DISABLED", "STRATEGY_PAUSED");
        assertThat(diagnostics)
                .containsKeys(
                        "last_task", "last_signal_date", "latest_market_date", "next_trade_date");
    }

    /** 对照源核实的正成交量不得被新浪同价零量重抓覆盖，且同步报告计数可追溯。 */
    @Test
    void verifiedTencentVolumeCannotBeDowngradedBySinaZero() {
        UUID row = UUID.randomUUID();
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                        + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                        + " values(?,'159915','2023-12-12',10,11,9,10,123400,1234000,'RAW',"
                        + "'SINA_PRICE_TENCENT_VOLUME_VERIFIED','REAL',now(),'VALID','{}')",
                row);
        var result =
                store.decode(
                        "{\"bars\":[{\"symbol\":\"159915\",\"trade_date\":\"2023-12-12\","
                                + "\"nature\":\"REAL\",\"price_basis\":\"RAW\",\"source\":\"AKSHARE_SINA\","
                                + "\"volume\":\"0\",\"open\":\"10\",\"high\":\"11\",\"low\":\"9\","
                                + "\"close\":\"10\"}],\"report\":{\"count\":1}}");
        when(quant.post(eq("/market/fetch"), any())).thenReturn(result);
        var report = market.sync(Map.of("symbol", "159915", "provider", "SINA"));
        assertThat(report.path("skipped_verified_volume_revisions").asInt()).isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select volume from market_bar where id=?", BigDecimal.class, row))
                .isEqualByComparingTo("123400");
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("bars").get(0))
                .put("close", "10.1");
        assertThatThrownBy(() -> market.sync(Map.of("symbol", "159915", "provider", "SINA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OHLC冲突");
    }

    /** 两个来源覆盖不同日期不构成对照验证，不能把source_count当作核验通过。 */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void disjointSourcesAreNotReportedAsCrossChecked() {
        store.db.update(
                "insert into etf_instrument(symbol,name,exchange,tracking_index,lot_size,settlement_days,"
                        + "limit_rate,tick_size,rule_valid_from,source_url,rules_source_url,"
                        + "verified_at,enabled,etf_type)"
                        + " values('999901','隔离质量测试','SSE','TEST',100,1,0.10,0.001,'2020-01-01',"
                        + "'https://www.sse.com.cn/test','https://www.sse.com.cn/test',now(),false,'STOCK_ETF')");
        for (int index = 1; index <= 2; index++) {
            store.db.update(
                    "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                            + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                            + " values(?,'999901',?::date,10,11,9,10,100,1000,'RAW',?,'REAL',now(),'VALID','{}')",
                    UUID.randomUUID(),
                    "2024-01-0" + index,
                    "ISOLATED_SOURCE_" + index);
        }
        var items = (List<Map<String, Object>>) market.qualityReport().get("items");
        var item =
                items.stream()
                        .filter(row -> "999901".equals(row.get("symbol")))
                        .findFirst()
                        .orElseThrow();
        assertThat(item.get("cross_source_status")).isEqualTo("NOT_VERIFIED_NO_OVERLAP");
        assertThat(item.get("cross_source_overlap_dates")).isEqualTo(0);
    }

    /** V9直接保存的真实抽样摘要完整返回，普通新浪或失败对照日志不能冒充已核验证据。 */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void qualityEvidencePreservesV9ReportShapeAndRequiresVerifiedCrossCheck() {
        store.db.update(
                "insert into etf_instrument(symbol,name,exchange,tracking_index,lot_size,settlement_days,"
                        + "limit_rate,tick_size,rule_valid_from,source_url,rules_source_url,"
                        + "verified_at,enabled,etf_type)"
                        + " values('999902','隔离证据测试','SSE','TEST',100,1,0.10,0.001,'2020-01-01',"
                        + "'https://www.sse.com.cn/test','https://www.sse.com.cn/test',now(),false,'STOCK_ETF')");
        for (var entry :
                List.of(
                        List.of("AKSHARE_SINA", "VERIFIED"),
                        List.of("TENCENT_CROSS_CHECK", "FAILED"))) {
            store.db.update(
                    "insert into market_sync(id,symbol,source,status,started_at,completed_at,report)"
                            + " values(?,'999902',?,?,now(),now(),'{\"sample_count\":999}')",
                    UUID.randomUUID(),
                    entry.get(0),
                    entry.get(1));
        }
        var result = store.json.valueToTree(market.qualityReport());
        List<String> verifiedSymbols = new ArrayList<>();
        boolean uncheckedFound = false;
        for (var item : result.path("items")) {
            String symbol = item.path("symbol").asText();
            var evidence = item.path("cross_source_evidence");
            if (List.of("159915", "510300", "510500").contains(symbol)) {
                verifiedSymbols.add(symbol);
                assertThat(evidence.isObject()).isTrue();
                assertThat(evidence.path("provider").asText())
                        .isEqualTo("TENCENT_RAW_KLINE_VS_AKSHARE_SINA");
                assertThat(evidence.path("sample_count").asInt()).isEqualTo(11);
                assertThat(evidence.path("scope").asText()).contains("非全历史");
                assertThat(evidence.path("corrected_dates").isArray()).isTrue();
                var stored =
                        store.db.queryForObject(
                                "select report::text from market_sync where symbol=?"
                                        + " and source='TENCENT_CROSS_CHECK' and status='VERIFIED'"
                                        + " order by completed_at desc,started_at desc limit 1",
                                String.class,
                                symbol);
                assertThat(evidence).isEqualTo(store.decode(stored));
            } else if ("999902".equals(symbol)) {
                uncheckedFound = true;
                assertThat(evidence.isArray()).isTrue();
                assertThat(evidence.isEmpty()).isTrue();
                assertThat(item.path("cross_source_status").asText())
                        .isEqualTo("NOT_VERIFIED_SINGLE_SOURCE");
            }
        }
        assertThat(verifiedSymbols).containsExactlyInAnyOrder("159915", "510300", "510500");
        assertThat(uncheckedFound).isTrue();
    }

    /** 测试日历是隔离固定输入，不访问外网，也不推导正式交易日。 */
    private void calendar(String day, boolean open) {
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) values(?::date,'ISOLATED_TEST',?)"
                        + " on conflict(trade_date) do update set is_open=excluded.is_open",
                day,
                open);
    }

    /** 构造隔离市场样本，明确使用测试来源，不能复制到正式研究库。 */
    private void marketBar(String day, String close, Instant fetched) {
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                        + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                        + " values(?,'510300',?::date,10,12,9,?,1000000,10000000,'RAW',?,'REAL',?,'VALID','{}')",
                UUID.randomUUID(),
                day,
                new BigDecimal(close),
                "ISOLATED_" + UUID.randomUUID(),
                Timestamp.from(fetched));
    }

    /** 固定时间的测试预测直接写隔离库，用于验证准时、迟到和到期语义。 */
    private UUID insertPrediction(
            String direction, Instant generated, String signal, String target) {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                        + "input_cutoff,created_at,model_version,nature,probability,direction,details,"
                        + "target_date)"
                        + " values(?,?,'510300',?,1,?::date,?,?,'TEST','REAL',0.6,?,'{}',?::date)",
                id,
                id.toString(),
                "TEST-" + id,
                signal,
                Timestamp.from(generated),
                Timestamp.from(generated),
                direction,
                target);
        return id;
    }

    /** 每项账户测试采用独立主键，既有测试和历史样本不清空。 */
    private String account() {
        String id = UUID.randomUUID().toString();
        store.db.update(
                "insert into paper_account(id,strategy_id,initial_capital,cash,equity,peak_equity,state)"
                        + " values(?,'ma',100000,100000,100000,100000,?::jsonb)",
                id,
                "{\"cash\":\"100000\",\"equity\":\"100000\",\"initial_capital\":\"100000\","
                        + "\"peak_equity\":\"100000\",\"lots\":{},\"receivables\":[],\"last_prices\":{},"
                        + "\"cumulative_dividends\":\"0\"}");
        return id;
    }
}
