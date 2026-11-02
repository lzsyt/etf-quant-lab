package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.paper.PaperService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyScheduler;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class V11SchedulingTest {
    private final Store store =
            new Store(mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules());
    private final MarketService market = mock(MarketService.class);
    private final PaperService paper = mock(PaperService.class);
    private final AnalysisService analysis = mock(AnalysisService.class);
    private final PredictionService predictions = mock(PredictionService.class);
    private final ResearchService research = mock(ResearchService.class);
    private final ResearchReportService reports = mock(ResearchReportService.class);
    private final TaskService tasks = mock(TaskService.class);

    /** 周末只能核验免费交易日历，不启动行情、新闻、收费模型或模拟账户。 */
    @Test
    void weekendEodSkipsEveryBusinessSideEffect() {
        assertClosedDay("2026-10-24T10:00:00Z", LocalDate.of(2026, 10, 24));
    }

    /** 法定休市虽然是星期四，仍必须按官方日历跳过。 */
    @Test
    void nationalHolidayEodSkipsEveryBusinessSideEffect() {
        assertClosedDay("2026-10-01T10:00:00Z", LocalDate.of(2026, 10, 1));
    }

    /** 开市日盘中不使用前一天数据伪装当日日终。 */
    @Test
    void marketNotClosedEodDoesNotReadPreviousDayAsToday() {
        when(market.tradingDayStatus(LocalDate.of(2026, 10, 23))).thenReturn(true);
        var result = daily().run(Instant.parse("2026-10-23T06:00:00Z"));
        assertThat(result.get("status")).isEqualTo("SKIPPED_MARKET_NOT_CLOSED");
        verifyNoInteractions(paper, analysis, predictions, research, reports);
    }

    /** 未来年份未核实日历时失败，不能将没有记录解释为休市。 */
    @Test
    void unknownCalendarFailsInsteadOfClaimingHoliday() {
        when(market.tradingDayStatus(any())).thenReturn(null);
        assertThatThrownBy(() -> daily().run(Instant.parse("2030-01-02T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未覆盖");
        verifyNoInteractions(paper, analysis, predictions, research, reports);
    }

    /** 已持久核实休市时，免费日历源暂时超时也不能退化为收费执行。 */
    @Test
    void calendarNetworkFailureUsesConfirmedClosedDayOnly() {
        when(market.syncCalendar()).thenThrow(new IllegalStateException("ISOLATED_TIMEOUT"));
        when(market.tradingDayStatus(LocalDate.of(2026, 10, 24))).thenReturn(false);
        var result = daily().run(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(result.get("status")).isEqualTo("SKIPPED_NON_TRADING_DAY");
        verifyNoInteractions(paper, analysis, predictions, research, reports);
    }

    /** 调度器不在周末排队，避免重新生成昨日任务和付费分析。 */
    @Test
    void schedulerDoesNotEnqueueOnWeekend() {
        when(market.tradingDayStatus(LocalDate.of(2026, 10, 24))).thenReturn(false);
        scheduler().schedule(Instant.parse("2026-10-24T10:00:00Z"));
        verifyNoInteractions(tasks, predictions);
    }

    /** 正式收盘调度时间之前不排队，不能提前使用尚未完整数据训练。 */
    @Test
    void schedulerWaitsUntil1730Beijing() {
        scheduler().schedule(Instant.parse("2026-10-23T09:29:59Z"));
        verifyNoInteractions(tasks, market, predictions);
    }

    /** 国庆后首个官方交易日恢复日终，业务标识使用当天日期。 */
    @Test
    void firstTradingDayAfterHolidayResumesDailyTask() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        when(market.tradingDayStatus(day)).thenReturn(true);
        when(predictions.latestExpected(any())).thenReturn(day);
        when(predictions.symbols()).thenReturn(List.of());
        scheduler().schedule(Instant.parse("2026-10-08T09:30:00Z"));
        verify(tasks).enqueue(eq("EOD"), any(), eq("daily:2026-10-08"));
    }

    /** 日历刷新仍不能确认当天时不排任何训练或日终任务。 */
    @Test
    void schedulerUnknownCalendarNeverEnqueuesPaidWork() {
        when(market.tradingDayStatus(any())).thenReturn(null);
        scheduler().schedule(Instant.parse("2030-01-02T10:00:00Z"));
        verify(market).syncCalendar();
        verify(tasks, never()).enqueue(any(), any(), any());
    }

    /** 共用固定休市断言，不读取本机实时时钟来猜测交易日期。 */
    private void assertClosedDay(String instant, LocalDate day) {
        when(market.tradingDayStatus(day)).thenReturn(false);
        var result = daily().run(Instant.parse(instant));
        assertThat(result.get("status")).isEqualTo("SKIPPED_NON_TRADING_DAY");
        verify(market).syncCalendar();
        verifyNoInteractions(paper, analysis, predictions, research, reports);
    }

    /** 所有协作者均为隔离替身，绝不触发实际数据、订单或供应商调用。 */
    private DailyService daily() {
        return new DailyService(store, market, paper, analysis, predictions, research, reports);
    }

    /** 显式启用被测试调度器，但所有执行队列仅为内存Mock。 */
    private DailyScheduler scheduler() {
        return new DailyScheduler(tasks, predictions, store, market, true);
    }
}
