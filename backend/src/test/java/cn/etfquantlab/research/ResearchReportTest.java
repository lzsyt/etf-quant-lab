package cn.etfquantlab.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ResearchReportTest {
    @Autowired private Store store;
    @Autowired private ResearchReportService reports;
    @Autowired private ResearchViewService views;
    @MockitoBean private QuantClient quant;

    /** 集成写入只在独立测试库且在事务结束后回滚，不触碰线上历史。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        when(quant.post(eq("/paper/metrics"), any())).thenReturn(store.decode("{}"));
    }

    /** 校准曲线必须按概率分桶，缺失概率不得当作零概率混入。 */
    @Test
    void calibrationUsesOnlyRealProbabilityAndReportsSampleSize() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var output = mapper.createArrayNode();
        ResearchViewService.calibration(
                output,
                "lightgbm",
                List.of(
                        mapper.readTree("{\"probability\":0.21,\"actual_up\":true}"),
                        mapper.readTree("{\"probability\":0.29,\"actual_up\":false}"),
                        mapper.readTree("{\"probability\":null,\"actual_up\":true}"),
                        mapper.readTree("{\"probability\":1.0,\"actual_up\":true}")));
        assertThat(output.size()).isEqualTo(2);
        assertThat(output.get(0).path("samples").asInt()).isEqualTo(2);
        assertThat(output.get(0).path("predicted_probability").asDouble())
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(output.get(0).path("observed_frequency").asDouble()).isEqualTo(0.5);
        assertThat(output.get(1).path("observed_frequency").asDouble()).isEqualTo(1.0);
    }

    /** 不同估值日期不能直接计算超额收益，空曲线也不宣称可比。 */
    @Test
    void accountComparisonRequiresTheSameActualSnapshotDates() {
        var first = List.of(store.decode("{\"trade_date\":\"2026-10-22\"}"));
        var second = List.of(store.decode("{\"trade_date\":\"2026-10-23\"}"));
        assertThat(ResearchViewService.sameDates(first, second)).isFalse();
        assertThat(ResearchViewService.sameDates(first, first)).isTrue();
        assertThat(ResearchViewService.sameDates(List.of(), List.of())).isFalse();
    }

    /** 实际读四类SQL入口，检验空数据、Python不可用及窗口参数边界。 */
    @Test
    void fourEvidenceModesStaySeparateAndDegradeWithoutInventingMetrics() {
        for (String mode : List.of("history", "oos", "forward", "paper")) {
            var result = views.rankings(mode, 1);
            assertThat(result.path("mode").asText()).isEqualTo(mode);
            assertThat(result.path("rows").isArray()).isTrue();
        }
        when(quant.post(eq("/paper/metrics"), any()))
                .thenThrow(new IllegalStateException("TEST_TIMEOUT"));
        var degraded = views.rankings("paper", 1);
        assertThat(degraded.path("limitations").toString()).contains("暂不可用");
        assertThatThrownBy(() -> views.rankings("mixed", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> views.rankings("forward", 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 相同输入幂等、变化追加版本，且已有日报内容不会被后来的查询改写。 */
    @Test
    void reportsAreVersionedIdempotentAndFrozen() {
        LocalDate day = latestFriday();
        prepareDay(day);
        var first = reports.generate(day, Map.of("status", "FIRST"));
        var duplicate = reports.generate(day, Map.of("status", "FIRST"));
        var second = reports.generate(day, Map.of("status", "SECOND"));
        assertThat(duplicate.path("id")).isEqualTo(first.path("id"));
        assertThat(second.path("version").asInt()).isEqualTo(first.path("version").asInt() + 1);
        assertThat(reports.get(UUID.fromString(first.path("id").asText())).path("report"))
                .isEqualTo(first.path("report"));
        assertThat(first.path("report").path("generated_at").asText()).isNotBlank();
        assertThat(first.path("report").path("conclusion").asText()).contains("样本不足");
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "update daily_research_report set version=999 where id=?",
                                        UUID.fromString(first.path("id").asText())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    /** 不把今天余额伪装成旧交易日的日报，也不在周末制造报告交易日。 */
    @Test
    void refusesBackfilledOrNonTradingReportDates() {
        LocalDate day = latestFriday();
        prepareDay(day);
        assertThatThrownBy(() -> reports.generate(day.minusDays(7), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.generate(day.plusDays(1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 允许可用WARNING日线，拒绝异常、盘中、未来抓取及休市日期，不夸大当天ETF覆盖。 */
    @Test
    void marketCoverageUsesOnlyCompletedAvailableTradingSessions() {
        LocalDate day = latestFriday();
        prepareDay(day);
        Instant complete = day.atTime(16, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        addBar("510500", day, "WARNING", complete);
        addBar("159915", day, "REJECTED", complete);
        addBar("159915", day, "VALID", complete.minusSeconds(4 * 3600));
        addBar("159915", day, "VALID", Instant.now().plusSeconds(86400));
        LocalDate closed = day.plusDays(1);
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) values(?::date,'ISOLATED_TEST',false)"
                        + " on conflict(trade_date) do update set is_open=false,source='ISOLATED_TEST'",
                closed.toString());
        addBar(
                "159915",
                closed,
                "VALID",
                closed.atTime(16, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        var report = reports.generate(day, Map.of()).path("report");
        assertThat(report.path("market_coverage_count").asInt()).isEqualTo(2);
        for (var item : report.path("market")) {
            if ("159915".equals(item.path("symbol").asText())) {
                assertThat(item.path("cutoff").asText()).isNotEqualTo(day.toString());
            } else {
                assertThat(item.path("cutoff").asText()).isEqualTo(day.toString());
            }
        }
    }

    /** 新日期只有异常K线时不能推进最新报告日，仍可归档真正完整的上一交易日。 */
    @Test
    void rejectedNewBarsDoNotAdvanceTheLatestReportDate() {
        LocalDate rejectedDay = latestFriday();
        LocalDate completedDay = rejectedDay.minusDays(7);
        prepareDay(completedDay);
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) values(?::date,'ISOLATED_TEST',true)"
                        + " on conflict(trade_date) do update set is_open=true,source='ISOLATED_TEST'",
                rejectedDay.toString());
        addBar("510500", rejectedDay, "REJECTED", Instant.now());
        assertThatThrownBy(() -> reports.generate(rejectedDay, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最新已到达行情日");
        assertThat(reports.generate(completedDay, Map.of()).path("report_date").asText())
                .isEqualTo(completedDay.toString());
    }

    /** 行情观察反推的日历记录不是可信开市证据，即使is_open为true也不得生成正式日报。 */
    @Test
    void observedMarketDatesCannotAuthorizeResearchReports() {
        LocalDate day = latestFriday();
        prepareDay(day);
        store.db.update(
                "update trading_calendar set source='OBSERVED_MARKET' where trade_date=?::date",
                day.toString());
        assertThatThrownBy(() -> reports.generate(day, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已登记");
    }

    /** 日报展示真实账户结算日期和未结算null，后续结算不能改写已冻结的报告。 */
    @Test
    void accountsExposeTheirActualSettlementDatesWithoutChangingFrozenReports() {
        LocalDate day = latestFriday();
        prepareDay(day);
        String account =
                store.db.queryForObject(
                        "select id from paper_account order by id limit 1", String.class);
        store.db.update("update paper_account set last_trade_date=null");
        store.db.update(
                "update paper_account set last_trade_date=?::date where id=?",
                day.minusDays(1).toString(),
                account);
        var first = reports.generate(day, Map.of());
        for (var item : first.path("report").path("accounts")) {
            if (account.equals(item.path("strategy_id").asText())) {
                assertThat(item.path("last_settlement_date").asText())
                        .isEqualTo(day.minusDays(1).toString());
            } else {
                assertThat(item.path("last_settlement_date").isNull()).isTrue();
            }
        }
        store.db.update(
                "update paper_account set last_trade_date=?::date where id=?",
                day.toString(),
                account);
        var second = reports.generate(day, Map.of());
        assertThat(second.path("version").asInt()).isEqualTo(first.path("version").asInt() + 1);
        assertThat(reports.get(UUID.fromString(first.path("id").asText())).path("report"))
                .isEqualTo(first.path("report"));
    }

    /** 测试固定到当前已结束的周五，正式业务日期仍由官方日历决定。 */
    private LocalDate latestFriday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .minusDays(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
    }

    /** 构造独立测试行情，用于验证数据库查询而非证明真实证券价格。 */
    private void prepareDay(LocalDate day) {
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) "
                        + "values(?::date,'ISOLATED_TEST',true) on conflict do nothing",
                day.toString());
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,"
                        + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                        + " values(?,'510300',?::date,10,10,10,10,1000000,'RAW','ISOLATED_TEST',"
                        + "'REAL',now(),'VALID','{}')",
                UUID.randomUUID(),
                day.toString());
    }

    /** 仅在事务隔离测试库构造边界K线，正式研究数据不写入这些测试价格。 */
    private void addBar(String symbol, LocalDate day, String quality, Instant fetchedAt) {
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,"
                        + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                        + " values(?,?,?::date,10,10,10,10,1000000,'RAW','ISOLATED_TEST','REAL',?,?,'{}')",
                UUID.randomUUID(),
                symbol,
                day.toString(),
                Timestamp.from(fetchedAt),
                quality);
    }
}
