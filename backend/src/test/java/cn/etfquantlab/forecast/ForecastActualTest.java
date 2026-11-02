package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 到期实际结果使用隔离库事务，行情提供者为明确测试替身，不采集或调用付费模型。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ForecastActualTest {
    @Autowired private Store store;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;
    private ForecastRunService runs;
    private FundResearchService funds;
    private GoldService gold;
    private MarketService market;
    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 2);

    /** 每个用例先确认隔离数据库，再装配仅用于计算的行情替身。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        funds = mock(FundResearchService.class);
        gold = mock(GoldService.class);
        market = mock(MarketService.class);
        runs =
                new ForecastRunService(
                        store,
                        tx,
                        mock(ForecastInputs.class),
                        mock(ForecastDeepSeek.class),
                        funds,
                        gold,
                        market,
                        false);
    }

    /** 基金按真正后续净值观测计数，缺一天不会按估算日期提前成熟。 */
    @Test
    void fundWaitsForTwentiethObservationAndFreezesActualOnce() {
        UUID id = fixture("460300", "FUND_NAV_CHANGE", 20, "1", null);
        ObjectNode request = selection("460300", "FUND_NAV_CHANGE", 20);
        List<JsonNode> values = fundValues(19);
        when(funds.observations("460300")).thenReturn(values);
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(0);
        values = fundValues(20);
        ObjectNode endpoint = (ObjectNode) values.get(19);
        endpoint.put("first_observed_at", "2026-02-12T04:00:00Z");
        endpoint.put("fetched_at", "2026-02-12T05:00:00Z");
        when(funds.observations("460300")).thenReturn(values);
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(1);
        JsonNode actual = actual(id);
        assertThat(actual.path("actual_target_date").asText()).isEqualTo("2026-02-11");
        assertThat(actual.path("actual_return").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(actual.path("actual_available_at").asText()).isEqualTo("2026-02-12T05:00:00Z");
        assertThat(actual.path("target_version").asText()).isEqualTo("test-target-version");
        assertThat(actual.path("price_basis").asText()).isEqualTo("UNIT_NAV");
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(0);
        assertThat(actual(id)).isEqualTo(actual);
        assertImmutable("forecast_result", "id", id);
        assertImmutable("forecast_result_actual", "forecast_id", id);
    }

    /** 即使已取得到期净值，分歧说明也不能进入数值预测实际结果和准确率样本。 */
    @Test
    void disagreementNeverReceivesNumericalActualEvenAfterMaturity() {
        UUID review = fixture("460300", "FUND_NAV_CHANGE", 20, "1", null, "REVIEW");
        when(funds.observations("460300")).thenReturn(fundValues(20));
        assertThat(runs.evaluate(selection("460300", "FUND_NAV_CHANGE", 20)).get("appended"))
                .isEqualTo(0);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from forecast_result_actual where forecast_id=?",
                                Integer.class,
                                review))
                .isZero();
    }

    /** 五观测短期按真实净值发布次数成熟，不能把五个自然日当终点。 */
    @Test
    void fundProbabilityMaturesAtFifthObservation() {
        UUID id = fixture("007169", "FUND_NAV_UP", 5, "1", null);
        when(funds.observations("007169")).thenReturn(fundValues(4));
        assertThat(runs.evaluate(selection("007169", "FUND_NAV_UP", 5)).get("appended"))
                .isEqualTo(0);
        when(funds.observations("007169")).thenReturn(fundValues(5));
        assertThat(runs.evaluate(selection("007169", "FUND_NAV_UP", 5)).get("appended"))
                .isEqualTo(1);
        assertThat(actual(id).path("actual_target_date").asText())
                .isEqualTo(ANCHOR.plusDays(10).toString());
        assertThat(actual(id).path("price_basis").asText()).isEqualTo("UNIT_NAV");
        assertThat(actual(id).path("actual_return").decimalValue()).isEqualByComparingTo("0.2");
    }

    /** 大型冻结证据留在数据库；有限JSON读取容量仍应完成评价，原预测不得修改。 */
    @Test
    void evaluationDoesNotDeserializeLargeFrozenEvidence() {
        UUID original = fixture("007169", "FUND_NAV_UP", 5, "1", null);
        UUID large = UUID.randomUUID();
        store.db.update(
                "insert into forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,details) "
                        + "select ?,item_id,run_id,asset,target,horizon,scheme,generated_at,"
                        + "details||jsonb_build_object('id',?::text,'input_snapshot',"
                        + "jsonb_build_object('history',repeat('TEST_ONLY',32768)),"
                        + "'evidence',jsonb_build_object('source',repeat('TEST_ONLY',32768))) "
                        + "from forecast_result where id=?",
                large,
                large.toString(),
                original);
        String before =
                store.db.queryForObject(
                        "select md5(details::text) from forecast_result where id=?",
                        String.class,
                        large);
        var boundedJson = store.json.copy();
        boundedJson
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(8192).build());
        Store boundedStore = new Store(store.db, boundedJson);
        assertThatThrownBy(
                        () ->
                                boundedStore.decode(
                                        store.db.queryForObject(
                                                "select details::text from forecast_result where id=?",
                                                String.class,
                                                large)))
                .isInstanceOf(IllegalStateException.class);
        ForecastRunService boundedRuns =
                new ForecastRunService(
                        boundedStore,
                        tx,
                        mock(ForecastInputs.class),
                        mock(ForecastDeepSeek.class),
                        funds,
                        gold,
                        market,
                        false);
        when(funds.observations("007169")).thenReturn(fundValues(5));
        assertThat(boundedRuns.evaluate(selection("007169", "FUND_NAV_UP", 5)).get("appended"))
                .isEqualTo(2);
        assertThat(actual(large).path("actual_return").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(actual(large).path("target_version").asText()).isEqualTo("test-target-version");
        assertThat(actual(large).path("actual_target_date").asText())
                .isEqualTo(ANCHOR.plusDays(10).toString());
        assertThat(
                        store.db.queryForObject(
                                "select md5(details::text) from forecast_result where id=?",
                                String.class,
                                large))
                .isEqualTo(before);
        assertThat(boundedRuns.evaluate(selection("007169", "FUND_NAV_UP", 5)).get("appended"))
                .isEqualTo(0);
    }

    /** 未来日历未知时不猜终点；正式日历覆盖后只追加实际日期，不改写原预测。 */
    @Test
    void closeReturnWithUnknownTargetWaitsForConfirmedCalendar() {
        UUID id = fixture("510500", "ETF_CLOSE_RETURN", 20, "100", null);
        store.db.update(
                "delete from trading_calendar where trade_date>?", java.sql.Date.valueOf(ANCHOR));
        when(market.bars("510500", ANCHOR, ANCHOR, "REAL", "RAW"))
                .thenReturn(List.of(bar("105", ANCHOR)));
        for (int i = 1; i <= 19; i++) {
            store.db.update(
                    "insert into trading_calendar(trade_date,is_open,source) values(?,true,'TEST_ONLY')",
                    java.sql.Date.valueOf(ANCHOR.plusDays(i * 2L)));
        }
        ObjectNode request = selection("510500", "ETF_CLOSE_RETURN", 20);
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(0);
        LocalDate endpoint = ANCHOR.plusDays(40);
        store.db.update(
                "insert into trading_calendar(trade_date,is_open,source) values(?,true,'TEST_ONLY')",
                java.sql.Date.valueOf(endpoint));
        when(market.bars("510500", endpoint, endpoint, "REAL", "RAW"))
                .thenReturn(List.of(bar("110", endpoint)));
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(1);
        assertThat(actual(id).path("actual_target_date").asText()).isEqualTo(endpoint.toString());
        assertThat(actual(id).path("actual_return").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(actual(id).path("anchor_value_used").decimalValue()).isEqualByComparingTo("100");
        assertThat(
                        store.db.queryForObject(
                                "select details->>'target_date' from forecast_result where id=?",
                                String.class,
                                id))
                .isNull();
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(0);
        assertImmutable("forecast_result", "id", id);
    }

    /** 终点日期已过去但获取时刻仍在未来时，不能把标签交给选优形成前视。 */
    @Test
    void unavailableFundLabelWaitsDespitePastObservationDate() {
        UUID id = fixture("460300", "FUND_NAV_CHANGE", 20, "1", null);
        List<JsonNode> values = fundValues(20);
        ((ObjectNode) values.get(19)).put("fetched_at", Instant.now().plusSeconds(3600).toString());
        when(funds.observations("460300")).thenReturn(values);
        assertThat(runs.evaluate(selection("460300", "FUND_NAV_CHANGE", 20)).get("appended"))
                .isEqualTo(0);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from forecast_result_actual where forecast_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    /** 黄金必须同时满足官方63个交易日，缺观测不能被后一个价格顶替。 */
    @Test
    void goldRequiresCompleteOfficialTradingWindow() {
        UUID id = fixture("Au99.99", "GOLD_BENCHMARK_RETURN", 63, "100", null);
        List<JsonNode> values = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int i = 1; i <= 63; i++) {
            LocalDate day = ANCHOR.plusDays(i * 2L);
            ObjectNode row = store.json.createObjectNode();
            row.put("series_id", GoldService.BENCHMARK);
            row.put("observation_date", day.toString());
            row.put("value", "110");
            row.putNull("available_at");
            row.put("fetched_at", day.plusDays(1) + "T02:00:00Z");
            values.add(row);
            dates.add(day.toString());
        }
        when(gold.observations()).thenReturn(values);
        List<String> missingDay = new ArrayList<>(dates);
        missingDay.add(ANCHOR.plusDays(1).toString());
        goldCalendar(missingDay);
        ObjectNode request = selection("Au99.99", "GOLD_BENCHMARK_RETURN", 63);
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(0);
        goldCalendar(dates);
        assertThat(runs.evaluate(request).get("appended")).isEqualTo(1);
        assertThat(actual(id).path("actual_return").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(actual(id).path("actual_target_date").asText()).isEqualTo(dates.get(62));
        assertThat(actual(id).path("actual_available_at").asText())
                .isEqualTo("2026-05-09T02:00:00Z");
    }

    /** 历史收盘目标坚持冻结起点；后续行情修订原始锚点不能改变预测问题。 */
    @Test
    void legacyEtfUsesFrozenAnchorAndForwardUsesObservedFutureOpen() {
        LocalDate end = ANCHOR.plusDays(3);
        when(market.bars("510300", ANCHOR, ANCHOR, "REAL", "RAW"))
                .thenReturn(List.of(bar("105", ANCHOR)));
        when(market.bars("510300", end, end, "REAL", "RAW")).thenReturn(List.of(bar("110", end)));
        UUID legacy = fixture("510300", "ETF_LEGACY_CLOSE", 1, "100", end);
        assertThat(runs.evaluate(selection("510300", "ETF_LEGACY_CLOSE", 1)).get("appended"))
                .isEqualTo(1);
        assertThat(actual(legacy).path("actual_return").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(actual(legacy).path("anchor_value_used").decimalValue())
                .isEqualByComparingTo("100");
        assertThat(actual(legacy).path("evidence").path("observed_anchor").path("close").asText())
                .isEqualTo("105");
        UUID forward = fixture("510300", "ETF_FORWARD_OPEN", 1, null, end);
        assertThat(runs.evaluate(selection("510300", "ETF_FORWARD_OPEN", 1)).get("appended"))
                .isEqualTo(1);
        assertThat(actual(forward).path("anchor_value_used").decimalValue())
                .isEqualByComparingTo("105");
        assertThat(actual(forward).path("price_basis").asText()).isEqualTo("RAW");
    }

    /** 超过全局页上限的长周期等待记录，不能让另一资产已到期评价永远饿死。 */
    @Test
    void waitingLongHorizonCannotStarveOtherAssetEvaluation() {
        UUID old = fixture("Au99.99", "GOLD_BENCHMARK_RETURN", 126, "100", null);
        store.db.update(
                "insert into forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,details) "
                        + "select gen_random_uuid(),item_id,run_id,asset,target,horizon,scheme,'1980-01-01'::timestamptz,details "
                        + "from forecast_result cross join generate_series(1,5001) where id=?",
                old);
        LocalDate end = ANCHOR.plusDays(3);
        when(market.bars("510300", ANCHOR, ANCHOR, "REAL", "RAW"))
                .thenReturn(List.of(bar("100", ANCHOR)));
        when(market.bars("510300", end, end, "REAL", "RAW")).thenReturn(List.of(bar("110", end)));
        UUID target = fixture("510300", "ETF_LEGACY_CLOSE", 1, "100", end);
        assertThat(runs.evaluate(selection("510300", "ETF_LEGACY_CLOSE", 1)).get("appended"))
                .isEqualTo(1);
        assertThat(actual(target).path("actual_return").decimalValue()).isEqualByComparingTo("0.1");
    }

    /** 夹具按稀疏实际观察构造，避免把日历天误当净值发布次数。 */
    private List<JsonNode> fundValues(int count) {
        List<JsonNode> values = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            LocalDate day = ANCHOR.plusDays(i * 2L);
            ObjectNode value = store.json.createObjectNode();
            value.put("nav_date", day.toString());
            value.put("unit_nav", "1.20");
            value.put("first_observed_at", day.plusDays(1) + "T01:00:00Z");
            value.put("fetched_at", day.plusDays(1) + "T02:00:00Z");
            values.add(value);
        }
        return values;
    }

    /** 合法实际结果有来源可得时间，测试不创建任何外部行情采集。 */
    private JsonNode bar(String price, LocalDate date) {
        return store.json.valueToTree(
                Map.of(
                        "close",
                        price,
                        "open",
                        price,
                        "trade_date",
                        date.toString(),
                        "fetched_at",
                        date + "T08:00:00Z",
                        "price_basis",
                        "RAW"));
    }

    /** 只有黄金日历当前行可更新，仍在测试事务内回滚，预测和实际事实不可更新。 */
    private void goldCalendar(List<String> dates) {
        store.db.update(
                "insert into gold_source(series_id,status,checked_at,details) values(?,'READY',now(),?::jsonb) "
                        + "on conflict(series_id) do update set details=excluded.details",
                GoldService.BENCHMARK,
                store.encode(Map.of("calendar", Map.of("dates", dates))));
    }

    /** 直接冻结历史测试机会，以验证到期逻辑而非伪造当前时点已知的历史预测。 */
    private UUID fixture(String asset, String target, int horizon, String start, LocalDate end) {
        return fixture(asset, target, horizon, start, end, "PREDICTOR");
    }

    /** 分歧说明在创建时声明REVIEW，不能后续修改冻结事实来修正角色。 */
    private UUID fixture(
            String asset, String target, int horizon, String start, LocalDate end, String role) {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ObjectNode details = selection(asset, target, horizon);
        details.put("id", id.toString());
        details.put("role", role);
        details.put("anchor_date", ANCHOR.toString());
        if (start != null) details.put("anchor_value", start);
        if (end != null) details.put("target_date", end.toString());
        details.put("target_version", "test-target-version");
        details.put("generated_at", "2026-01-01T08:00:00Z");
        store.db.update(
                "insert into forecast_run(id,business_key,status,request) values(?,?,'COMPLETED','{}')",
                runId,
                runId.toString());
        store.db.update(
                "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,status,input_snapshot) "
                        + "values(?,?,?,?,?,'TEST','SUCCEEDED','{}')",
                itemId,
                runId,
                asset,
                target,
                horizon);
        store.db.update(
                "insert into forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,details) "
                        + "values(?,?,?,?,?,?,'TEST',?,?::jsonb)",
                id,
                itemId,
                runId,
                asset,
                target,
                horizon,
                Timestamp.from(Instant.parse("2026-01-01T08:00:00Z")),
                store.encode(details));
        return id;
    }

    /** 每个评价请求只查询被测资产目标。 */
    private ObjectNode selection(String asset, String target, int horizon) {
        return store.json.valueToTree(
                Map.of("asset", asset, "target", target, "horizon", horizon, "mode", "REAL"));
    }

    /** 读取刚追加的实际证据，重复评价前后内容应完全相同。 */
    private JsonNode actual(UUID id) {
        return store.decode(
                store.db.queryForObject(
                        "select details::text from forecast_result_actual where forecast_id=?",
                        String.class,
                        id));
    }

    /** 通过保存点验证V17不可变触发器，不让预期SQL拒绝中止整个测试事务。 */
    private void assertImmutable(String table, String key, UUID id) {
        for (String operation :
                List.of(
                        "update " + table + " set details='{}' where " + key + "=?",
                        "delete from " + table + " where " + key + "=?")) {
            store.db.execute(
                    (ConnectionCallback<Void>)
                            connection -> {
                                Savepoint point = connection.setSavepoint();
                                try (var statement = connection.prepareStatement(operation)) {
                                    statement.setObject(1, id);
                                    assertThatThrownBy(statement::executeUpdate)
                                            .isInstanceOf(java.sql.SQLException.class);
                                } finally {
                                    connection.rollback(point);
                                    connection.releaseSavepoint(point);
                                }
                                return null;
                            });
        }
    }
}
