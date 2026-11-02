package cn.etfquantlab;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.gold.GoldScheduler;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GoldSchedulerTest {
    private final TaskService tasks = mock(TaskService.class);
    private final GoldService gold = mock(GoldService.class);
    private final Store store = new Store(mock(JdbcTemplate.class), new ObjectMapper());

    /** ETF日终时段不入任何黄金任务，保留原队列资源。 */
    @Test
    void noGoldWorkDuringEtfClose() {
        when(gold.enabled()).thenReturn(true);
        scheduler(true).schedule(Instant.parse("2026-10-26T10:00:00Z"));
        verifyNoInteractions(tasks);
    }

    /** 夜间逐日增量、按月训练、按周预测和解释使用各自持久业务键。 */
    @Test
    void stableCycleKeysSupportStartupCatchUp() {
        when(gold.enabled()).thenReturn(true);
        when(gold.readyForResearch()).thenReturn(true);
        when(gold.currentMonthModelsReady(any())).thenReturn(true);
        scheduler(true).schedule(Instant.parse("2026-10-27T15:00:00Z"));
        verify(tasks).enqueue(eq("GOLD_SYNC"), any(), eq("gold-daily-v1:2026-10-27"));
        verify(tasks).enqueue(eq("GOLD_TRAIN"), any(), eq("gold-monthly-dual-ml-v2:2026-10-01"));
        verify(tasks).enqueue(eq("GOLD_PREDICT"), any(), eq("gold-weekly-v1:2026-10-26"));
        verify(tasks).enqueue(eq("GOLD_EXPLAIN"), any(), eq("gold-explanation-v1:2026-10-26"));
    }

    /** 本月训练尚未完成时不消耗周预测键，后续模型登记完成可用同一周期补入。 */
    @Test
    void weeklyPredictionWaitsForBothCurrentMonthModels() {
        when(gold.enabled()).thenReturn(true);
        when(gold.readyForResearch()).thenReturn(true);
        when(gold.currentMonthModelsReady(any())).thenReturn(false);
        scheduler(true).schedule(Instant.parse("2026-10-27T15:00:00Z"));
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never())
                .enqueue(eq("GOLD_PREDICT"), any(), any());
        when(gold.currentMonthModelsReady(any())).thenReturn(true);
        scheduler(true).schedule(Instant.parse("2026-10-27T15:01:00Z"));
        verify(tasks).enqueue(eq("GOLD_PREDICT"), any(), eq("gold-weekly-v1:2026-10-26"));
    }

    /** 关闭黄金开关或总调度不会偷偷恢复任何任务。 */
    @Test
    void disabledModuleAndGlobalSwitchStopScheduling() {
        when(gold.enabled()).thenReturn(false);
        scheduler(true).schedule(Instant.parse("2026-10-26T15:00:00Z"));
        when(gold.enabled()).thenReturn(true);
        scheduler(false).schedule(Instant.parse("2026-10-26T15:00:00Z"));
        verifyNoInteractions(tasks);
    }

    /** 默认研究时刻在采集之后；未来交易日不是用周末推定为正式日历。 */
    private GoldScheduler scheduler(boolean enabled) {
        return new GoldScheduler(tasks, store, gold, enabled, "21:00", "22:30", "MONDAY");
    }
}
