package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.gold.GoldNews;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
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

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class GoldQueueTest {
    @Autowired private Store store;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;
    private final GoldService gold = mock(GoldService.class);
    private final MarketService market = mock(MarketService.class);

    /** 临时暂停测试库的其他夹具，事务回滚后原样恢复，绝不触及正式库。 */
    @BeforeEach
    void isolatedFixtureQueue() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("update task_execution set status='PAUSED'");
        when(gold.enabled()).thenReturn(true);
        when(gold.evaluate()).thenReturn(Map.of("status", "EVALUATED", "appended", 0));
        when(market.sync(org.mockito.ArgumentMatchers.any()))
                .thenReturn(store.json.createObjectNode());
    }

    /** 即使黄金任务更旧，既有ETF任务仍先领取；测试使用真实数据库队列。 */
    @Test
    void etfTasksHavePriorityOverOlderGoldTasks() {
        try (Worker worker = worker()) {
            UUID goldId = enqueue(worker.tasks, "GOLD_EVALUATE");
            UUID etfId = enqueue(worker.tasks, "MARKET_SYNC");
            store.db.update("update task_execution set updated_at='2000-01-01' where id=?", goldId);
            worker.tasks.consume();
            assertThat(status(etfId)).isEqualTo("SUCCEEDED");
            assertThat(status(goldId)).isEqualTo("QUEUED");
            verifyNoInteractions(quant);
        }
    }

    /** 关闭模块后保留黄金队列原状态，不通过反复失败消耗重试次数。 */
    @Test
    void disabledGoldIsNotClaimed() {
        when(gold.enabled()).thenReturn(false);
        try (Worker worker = worker()) {
            UUID id = enqueue(worker.tasks, "GOLD_EVALUATE");
            worker.tasks.consume();
            assertThat(status(id)).isEqualTo("QUEUED");
            assertThat(
                            store.db.queryForObject(
                                    "select attempts from task_execution where id=?",
                                    Integer.class,
                                    id))
                    .isZero();
        }
    }

    /** 领取窗口允许时，过期租约恢复同一任务，原业务ID保持不变。 */
    @Test
    void expiredLeaseRecoversSameGoldTaskAtNight() {
        int hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        org.junit.jupiter.api.Assumptions.assumeTrue(hour >= 20 || hour < 6, "正式领取窗口之外由时间门禁覆盖");
        try (Worker worker = worker()) {
            UUID id = enqueue(worker.tasks, "GOLD_EVALUATE");
            store.db.update(
                    "update task_execution set status='RUNNING',heartbeat_at=now()-interval '11 minutes',"
                            + "lease_token=? where id=?",
                    UUID.randomUUID(),
                    id);
            worker.tasks.consume();
            assertThat(status(id)).isEqualTo("SUCCEEDED");
            verify(gold).evaluate();
        }
    }

    /** 业务阻塞保留原任务，只有依赖变化后恢复；相同数据轮询不会再次执行模型。 */
    @Test
    void blockedPredictionWaitsForChangedDependenciesThenRecoversSameTask() {
        int hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        org.junit.jupiter.api.Assumptions.assumeTrue(hour >= 20 || hour < 6);
        when(gold.readyForResearch()).thenReturn(true);
        when(gold.currentMonthModelsReady(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(gold.dependencyVersion()).thenReturn("version-1");
        when(gold.predict()).thenReturn(store.decode("{\"status\":\"BLOCKED_NO_MODEL\"}"));
        try (Worker worker = worker()) {
            String key = UUID.randomUUID().toString();
            UUID id =
                    (UUID)
                            worker.tasks
                                    .enqueue("GOLD_PREDICT", store.json.createObjectNode(), key)
                                    .get("id");
            worker.tasks.consume();
            assertThat(status(id)).isEqualTo("WAITING_DATA");
            assertThat(
                            worker.tasks
                                    .enqueue("GOLD_PREDICT", store.json.createObjectNode(), key)
                                    .get("id"))
                    .isEqualTo(id);
            assertThat(status(id)).isEqualTo("WAITING_DATA");
            when(gold.dependencyVersion()).thenReturn("version-2");
            worker.tasks.enqueue("GOLD_PREDICT", store.json.createObjectNode(), key);
            assertThat(status(id)).isEqualTo("QUEUED");
            when(gold.predict()).thenReturn(store.decode("{\"status\":\"SUCCEEDED\"}"));
            worker.tasks.consume();
            assertThat(status(id)).isEqualTo("SUCCEEDED");
        }
    }

    /** 模拟各业务协作者但使用真实持久队列与事务，避免网络和费用。 */
    private Worker worker() {
        TaskService tasks =
                new TaskService(
                        store,
                        market,
                        mock(ResearchService.class),
                        tx,
                        mock(DailyService.class),
                        mock(ResearchReportService.class),
                        mock(PredictionService.class),
                        mock(ForwardService.class),
                        mock(AnalysisService.class),
                        mock(ForwardNews.class));
        ReflectionTestUtils.setField(tasks, "gold", gold);
        ReflectionTestUtils.setField(tasks, "goldNews", mock(GoldNews.class));
        return new Worker(tasks);
    }

    /** 每个测试独立业务键，重试和租约操作只针对本条任务。 */
    private UUID enqueue(TaskService tasks, String type) {
        return (UUID)
                tasks.enqueue(type, store.json.createObjectNode(), UUID.randomUUID().toString())
                        .get("id");
    }

    /** 读取单条任务状态，断言数据库持久结果而非仅检查Mock调用。 */
    private String status(UUID id) {
        return store.db.queryForObject(
                "select status from task_execution where id=?", String.class, id);
    }

    /** 测试结束关闭心跳执行器，避免后台线程越过测试生命周期。 */
    private record Worker(TaskService tasks) implements AutoCloseable {
        @Override
        public void close() {
            tasks.closeHeartbeat();
        }
    }
}
