package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.fund.FundScheduler;
import cn.etfquantlab.gold.GoldScheduler;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyScheduler;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 统一调度合同同时验证排队顺序与真实SQL领取，所有采集和模型执行均为测试替身。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ForecastSchedulingTest {
    @Autowired private Store store;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;

    /** 仅隔离数据库可暂停历史测试队列，事务结束后原状态全部恢复。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("update task_execution set status='PAUSED'");
    }

    /** 统一入口即使未启用旧前瞻模拟实验，也必须准备正确开盘协议且不启动旧收盘日终。 */
    @Test
    void etfTakeoverPreparesForwardModelsBeforeEachRun() {
        TaskService tasks = mock(TaskService.class);
        PredictionService dates = mock(PredictionService.class);
        MarketService market = mock(MarketService.class);
        LocalDate day = LocalDate.of(2026, 10, 28);
        when(tasks.unifiedEnabled()).thenReturn(true);
        when(market.tradingDayStatus(day)).thenReturn(true);
        when(dates.latestExpected(any())).thenReturn(day);
        new DailyScheduler(tasks, dates, store, market, true)
                .schedule(Instant.parse("2026-10-28T10:00:00Z"));
        var order = inOrder(tasks);
        for (String asset : List.of("510300", "510500", "159915")) {
            for (String action : List.of("SYNC", "TRAIN")) {
                order.verify(tasks)
                        .enqueue(
                                eq("FORECAST_PREPARE"),
                                argThat(
                                        payload ->
                                                asset.equals(payload.path("asset").asText())
                                                        && action.equals(
                                                                payload.path("action").asText())
                                                        && "ETF_FORWARD_OPEN"
                                                                .equals(
                                                                        payload.path("target")
                                                                                .asText())),
                                anyString());
            }
            order.verify(tasks).enqueueForecast(asset, "ETF_FORWARD_OPEN", 1, "market:" + day);
            for (int horizon : List.of(20, 60)) {
                order.verify(tasks)
                        .enqueue(
                                eq("FORECAST_PREPARE"),
                                argThat(
                                        payload ->
                                                asset.equals(payload.path("asset").asText())
                                                        && "ETF_CLOSE_RETURN"
                                                                .equals(
                                                                        payload.path("target")
                                                                                .asText())
                                                        && horizon
                                                                == payload.path("horizon").asInt()
                                                        && "TRAIN"
                                                                .equals(
                                                                        payload.path("action")
                                                                                .asText())),
                                anyString());
                order.verify(tasks)
                        .enqueueForecast(asset, "ETF_CLOSE_RETURN", horizon, "market:" + day);
            }
        }
        verify(tasks, never()).enqueue(eq("EOD"), any(), anyString());
        verify(tasks, never()).enqueue(eq("TRAIN"), any(), anyString());
    }

    /** 模型训练历史不足只使量化缺模型，不能禁止独立DeepSeek进入统一批次。 */
    @Test
    void fundAndGoldDoNotGateIndependentForecastsOnTrainingReadiness() {
        TaskService tasks = mock(TaskService.class);
        when(tasks.unifiedEnabled()).thenReturn(true);
        FundResearchService funds = mock(FundResearchService.class);
        when(funds.enabled()).thenReturn(true);
        for (String asset : FundResearchService.CODES)
            when(funds.observations(asset)).thenReturn(List.of());
        new FundScheduler(tasks, funds, store, true)
                .schedule(Instant.parse("2026-10-28T13:00:00Z"));
        for (String asset : FundResearchService.CODES)
            for (int horizon : List.of(20, 60)) {
                verify(tasks)
                        .enqueueForecast(
                                eq(asset), eq("FUND_NAV_CHANGE"), eq(horizon), anyString());
            }
        for (String asset : FundResearchService.CODES)
            verify(tasks).enqueueForecast(eq(asset), eq("FUND_NAV_UP"), eq(5), anyString());
        verify(tasks, never()).enqueue(eq("FUND_TRAIN"), any(), anyString());
        GoldService gold = mock(GoldService.class);
        when(gold.enabled()).thenReturn(true);
        when(gold.readyForResearch()).thenReturn(false);
        new GoldScheduler(tasks, store, gold, true, "21:00", "22:30", "MONDAY")
                .schedule(Instant.parse("2026-10-28T15:00:00Z"));
        for (int horizon : List.of(63, 126)) {
            verify(tasks)
                    .enqueueForecast(
                            eq("Au99.99"), eq("GOLD_BENCHMARK_RETURN"), eq(horizon), anyString());
        }
        verify(tasks, never()).enqueue(eq("GOLD_TRAIN"), any(), anyString());
    }

    /** 黄金单资产批次按run_id识别，白天等待且夜间必须在同步/训练之后执行。 */
    @Test
    void goldRunKeepsNightWindowAndNativeDependencyOrder() {
        GoldService gold = mock(GoldService.class);
        ForecastRunService runs = mock(ForecastRunService.class);
        when(gold.enabled()).thenReturn(true);
        when(gold.sync(any())).thenReturn(store.json.valueToTree(Map.of("status", "READY")));
        when(gold.train()).thenReturn(store.json.valueToTree(Map.of("status", "READY")));
        UUID runId = UUID.randomUUID();
        JsonNode selection =
                store.json.valueToTree(
                        Map.of(
                                "asset",
                                "Au99.99",
                                "target",
                                "GOLD_BENCHMARK_RETURN",
                                "horizon",
                                63));
        store.db.update(
                "insert into forecast_run(id,business_key,status,request) values(?,?,'QUEUED',?::jsonb)",
                runId,
                runId.toString(),
                store.encode(selection));
        UUID sync = queued("GOLD_SYNC", store.json.createObjectNode(), 1);
        UUID train = queued("GOLD_TRAIN", store.json.createObjectNode(), 2);
        UUID run = queued("FORECAST_RUN", store.json.valueToTree(Map.of("run_id", runId)), 3);
        when(runs.execute(runId))
                .thenReturn(
                        store.json.valueToTree(
                                Map.of("status", "COMPLETED", "request", selection)));
        TaskService worker =
                new TaskService(
                        store,
                        mock(MarketService.class),
                        mock(ResearchService.class),
                        tx,
                        mock(DailyService.class),
                        mock(ResearchReportService.class),
                        mock(PredictionService.class),
                        mock(ForwardService.class),
                        mock(AnalysisService.class),
                        mock(ForwardNews.class));
        ReflectionTestUtils.setField(worker, "gold", gold);
        ReflectionTestUtils.setField(worker, "forecastRuns", runs);
        try {
            worker.consume(Instant.parse("2026-10-28T04:00:00Z"));
            assertThat(state(run)).isEqualTo("QUEUED");
            verify(gold, never()).sync(any());
            verify(runs, never()).execute(any());
            for (int i = 0; i < 3; i++) worker.consume(Instant.parse("2026-10-28T15:00:00Z"));
            var order = inOrder(gold, runs);
            order.verify(gold).sync(any());
            order.verify(gold).train();
            order.verify(runs).execute(runId);
            for (UUID id : List.of(sync, train, run)) assertThat(state(id)).isEqualTo("SUCCEEDED");
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 显式不相等的顺序时间避免测试事务now固定导致排序偶然通过。 */
    private UUID queued(String type, JsonNode payload, int order) {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request,updated_at) values(?,?,?,'QUEUED',?::jsonb,?::timestamptz)",
                id,
                id.toString(),
                type,
                store.encode(payload),
                "1900-01-0" + order + "T00:00:00Z");
        return id;
    }

    /** 断言真实持久任务状态，不能仅凭Mock调用顺序推断队列成功。 */
    private String state(UUID id) {
        return store.db.queryForObject(
                "select status from task_execution where id=?", String.class, id);
    }
}
