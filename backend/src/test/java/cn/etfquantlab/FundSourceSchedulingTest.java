package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.fund.FundScheduler;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class FundSourceSchedulingTest {
    @Autowired private Store store;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;
    private final AnalysisService news = mock(AnalysisService.class);
    private final FundResearchService funds = mock(FundResearchService.class);

    /** 真实队列只运行在隔离库，其他测试遗留夹具在本事务内暂停，结束后全部回滚。 */
    @BeforeEach
    void isolatedQueue() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("update task_execution set status='PAUSED'");
        when(funds.enabled()).thenReturn(true);
        for (String code : FundResearchService.CODES) {
            when(funds.observations(code))
                    .thenReturn(Collections.nCopies(600, store.json.createObjectNode()));
        }
    }

    /** 一分钟调度检查不等于一次采集；同十五分钟桶和重启重放只保留一个任务。 */
    @Test
    void pollBucketsArePersistentAndDoNotDirectlyAnalyze() {
        TaskService worker = worker();
        try {
            FundScheduler scheduler = new FundScheduler(worker, funds, store, true);
            scheduler.schedule(Instant.parse("2040-02-01T02:01:00Z"));
            scheduler.schedule(Instant.parse("2040-02-01T02:12:00Z"));
            new FundScheduler(worker, funds, store, true)
                    .schedule(Instant.parse("2040-02-01T02:14:00Z"));
            assertThat(activeCount("NEWS_COLLECT")).isEqualTo(1);
            scheduler.schedule(Instant.parse("2040-02-01T02:15:00Z"));
            assertThat(activeCount("NEWS_COLLECT")).isEqualTo(2);
            assertThat(activeCount("FUND_NEWS")).isZero();
            assertThat(activeCount("FUND_NAV")).isZero();
            assertThat(
                            store.db.queryForObject(
                                    "select bool_and((request->>'fund_followup')::boolean) from task_execution "
                                            + "where status='QUEUED' and task_type='NEWS_COLLECT'",
                                    Boolean.class))
                    .isTrue();
            verifyNoInteractions(news, quant);
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 错误配置的一分钟检查被夹到五分钟采集下限，不造成逐分钟来源请求。 */
    @Test
    void pollIntervalHasFiveMinuteMinimum() {
        TaskService worker = worker();
        try {
            FundScheduler scheduler = new FundScheduler(worker, funds, store, true);
            ReflectionTestUtils.setField(scheduler, "newsPollMinutes", 1);
            scheduler.schedule(Instant.parse("2040-02-01T02:01:00Z"));
            scheduler.schedule(Instant.parse("2040-02-01T02:04:00Z"));
            assertThat(activeCount("NEWS_COLLECT")).isEqualTo(1);
            scheduler.schedule(Instant.parse("2040-02-01T02:05:00Z"));
            assertThat(activeCount("NEWS_COLLECT")).isEqualTo(2);
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 净值、训练、预测和评价仍使用原7/21/23窗口，原窗口不直接触发基金收费。 */
    @Test
    void navWindowsStaySeparateFromNewsAnalysis() {
        TaskService worker = worker();
        try {
            FundScheduler scheduler = new FundScheduler(worker, funds, store, true);
            for (String instant :
                    List.of(
                            "2040-02-01T23:00:00Z",
                            "2040-02-02T13:00:00Z",
                            "2040-02-02T15:00:00Z")) {
                scheduler.schedule(Instant.parse(instant));
                scheduler.schedule(Instant.parse(instant).plusSeconds(60));
            }
            assertThat(activeCount("FUND_NAV")).isEqualTo(9);
            assertThat(activeCount("FUND_TRAIN")).isEqualTo(3);
            assertThat(activeCount("FUND_FORECAST")).isEqualTo(9);
            assertThat(activeCount("FUND_EVALUATE")).isEqualTo(9);
            assertThat(activeCount("NEWS_COLLECT")).isEqualTo(3);
            assertThat(activeCount("FUND_NEWS")).isZero();
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 任一模块或自动调度开关关闭时，不排免费或付费基金任务。 */
    @Test
    void disabledSchedulerDoesNotEnqueue() {
        TaskService worker = worker();
        try {
            new FundScheduler(worker, funds, store, false).schedule(Instant.now());
            when(funds.enabled()).thenReturn(false);
            new FundScheduler(worker, funds, store, true).schedule(Instant.now());
            assertThat(activeCount("NEWS_COLLECT")).isZero();
            assertThat(activeCount("FUND_NEWS")).isZero();
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 采集先成功返回才排三个基金子任务，父任务崩溃重放不会复制子任务。 */
    @Test
    void successfulCollectionEnqueuesEachFundOnce() {
        when(news.collect()).thenReturn(realCollection());
        TaskService worker = worker();
        try {
            UUID id = enqueueCollection(worker, true);
            worker.consume();
            assertThat(status(id)).isEqualTo("SUCCEEDED");
            assertThat(children(id)).isEqualTo(3);
            store.db.update(
                    "update task_execution set status='PAUSED' where task_type='FUND_NEWS'");
            store.db.update("update task_execution set status='QUEUED' where id=?", id);
            worker.consume();
            assertThat(status(id)).isEqualTo("SUCCEEDED");
            assertThat(children(id)).isEqualTo(3);
            verifyNoInteractions(quant);
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 普通人工采集、关闭状态、失败、无数据、演示和未来材料都不能派生基金分析。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ordinary",
                "disabled",
                "module-disabled",
                "failed",
                "empty",
                "mock",
                "future"
            })
    void collectionWithoutValidFollowupDoesNotAnalyze(String scenario) {
        ObjectNode result = realCollection();
        if (scenario.equals("empty")) {
            result.putArray("items");
        } else if (scenario.equals("mock")) {
            ((ObjectNode) result.path("items").get(0)).put("nature", "MOCK");
        } else if (scenario.equals("future")) {
            ((ObjectNode) result.path("items").get(0))
                    .put("published_at", Instant.now().plusSeconds(3600).toString());
        }
        when(news.collect()).thenReturn(result);
        if (scenario.equals("failed")) {
            when(news.collect())
                    .thenThrow(new IllegalStateException("ISOLATED_COLLECTION_FAILURE"));
        }
        if (scenario.equals("module-disabled")) {
            when(funds.enabled()).thenReturn(false);
        }
        TaskService worker = worker();
        try {
            if (scenario.equals("disabled")) {
                ReflectionTestUtils.setField(worker, "fundScheduling", false);
            }
            UUID id = enqueueCollection(worker, !scenario.equals("ordinary"));
            worker.consume();
            assertThat(status(id)).isEqualTo(scenario.equals("failed") ? "FAILED" : "SUCCEEDED");
            assertThat(children(id)).isZero();
            verifyNoInteractions(quant);
        } finally {
            worker.closeHeartbeat();
        }
    }

    /** 仅准备隔离来源结果，不进行网络抓取或真实模型请求。 */
    private ObjectNode realCollection() {
        ObjectNode result = store.json.createObjectNode();
        result.putArray("items")
                .addObject()
                .put("nature", "REAL")
                .put("publication_verified", true)
                .put("published_at", Instant.now().minusSeconds(60).toString())
                .put("fetched_at", Instant.now().minusSeconds(30).toString());
        return result;
    }

    /** 使用真实事务队列，所有业务协作者为隔离替身。 */
    private TaskService worker() {
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
                        news,
                        mock(ForwardNews.class));
        ReflectionTestUtils.setField(worker, "funds", funds);
        ReflectionTestUtils.setField(worker, "fundScheduling", true);
        return worker;
    }

    /** 每次夹具使用独立持久任务键；普通采集无派生标记。 */
    private UUID enqueueCollection(TaskService worker, boolean followup) {
        return (UUID)
                worker.enqueue(
                                "NEWS_COLLECT",
                                store.json.valueToTree(Map.of("fund_followup", followup)),
                                UUID.randomUUID().toString())
                        .get("id");
    }

    /** 只统计本事务可消费任务，排除隔离库既有暂停夹具。 */
    private int activeCount(String type) {
        return store.db.queryForObject(
                "select count(*) from task_execution where status='QUEUED' and task_type=?",
                Integer.class,
                type);
    }

    /** 按父任务唯一前缀计数，重放不会产生额外基金任务。 */
    private int children(UUID id) {
        return store.db.queryForObject(
                "select count(*) from task_execution where business_key like ?",
                Integer.class,
                "FUND_NEWS:source-collection:" + id + ":%");
    }

    /** 核对队列持久状态，而非仅验证内存调用。 */
    private String status(UUID id) {
        return store.db.queryForObject(
                "select status from task_execution where id=?", String.class, id);
    }
}
