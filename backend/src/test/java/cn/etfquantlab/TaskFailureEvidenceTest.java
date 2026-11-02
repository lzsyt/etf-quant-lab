package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchController;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskFailureEvidenceTest {
    @Autowired private Store store;
    @Autowired private TaskService tasks;
    @Autowired private ResearchController controller;
    @MockitoBean private ForwardService forward;
    @MockitoBean private QuantClient quant;

    /** 真实队列与SQL行为只在隔离测试库验证，每个用例回滚测试记录。 */
    @BeforeEach
    void isolatedDatabaseOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 同一执行的业务原因应随失败任务保存，健康投影变化后列表及详情仍保留原证据。 */
    @Test
    void failureReasonSurvivesHealthProjectionReplacement() {
        UUID id = queued();
        String reason = "日终行情缺失：2026-10-26，ETF 159915；等待真实日线补齐";
        when(forward.run(false))
                .thenAnswer(
                        call -> {
                            health(id.toString(), "now()", reason);
                            throw new IllegalArgumentException("EXTERNAL_SECRET_FIXTURE");
                        });
        tasks.consume();
        JsonNode task = controller.task(id, () -> "test");
        assertThat(task.path("status").asText()).isEqualTo("FAILED");
        assertThat(task.path("result").path("failure").path("reason").asText()).isEqualTo(reason);
        assertThat(task.toString()).doesNotContain("EXTERNAL_SECRET_FIXTURE");
        health(UUID.randomUUID().toString(), "now()", "另一任务的新原因");
        JsonNode listed =
                controller.tasks(() -> "test").stream()
                        .filter(row -> id.toString().equals(row.path("id").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(listed.path("error_message").asText()).isEqualTo(reason);
    }

    /** 重试开始后旧心跳或其他任务的阶段错误不能冒充本次失败。 */
    @Test
    void staleOrForeignHealthDoesNotLeakIntoCurrentAttempt() {
        for (boolean stale : new boolean[] {true, false}) {
            UUID id = queued();
            doAnswer(
                            call -> {
                                health(
                                        stale ? id.toString() : UUID.randomUUID().toString(),
                                        stale ? "now()-interval '1 hour'" : "now()",
                                        "旧失败原因");
                                throw new IllegalArgumentException("UNSAFE_MESSAGE_FIXTURE");
                            })
                    .when(forward)
                    .run(false);
            tasks.consume();
            JsonNode task = controller.task(id, () -> "test");
            assertThat(task.path("status").asText()).isEqualTo("FAILED");
            assertThat(task.path("result").isNull()).isTrue();
            assertThat(task.toString()).doesNotContain("UNSAFE_MESSAGE_FIXTURE");
        }
    }

    /** 新重试进入RUNNING后清掉前次失败展示，再由本次结果独立更新。 */
    @Test
    void retryClearsPreviousErrorBeforeExecuting() {
        UUID id = queued();
        store.db.update(
                "update task_execution set error_code='OLD',"
                        + "result='{\"failure\":{\"reason\":\"旧失败\"}}'::jsonb where id=?",
                id);
        when(forward.run(false))
                .thenAnswer(
                        call -> {
                            JsonNode running = controller.task(id, () -> "test");
                            assertThat(running.path("status").asText()).isEqualTo("RUNNING");
                            assertThat(running.path("result").isNull()).isTrue();
                            assertThat(running.path("error_code").isNull()).isTrue();
                            return java.util.Map.of("status", "TEST_SUCCESS");
                        });
        tasks.consume();
        assertThat(controller.task(id, () -> "test").path("status").asText())
                .isEqualTo("SUCCEEDED");
    }

    /** 测试任务优先领取且业务键独立，避免消费隔离库其他用例留下的排队任务。 */
    private UUID queued() {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request,updated_at)"
                        + " values(?,?,'FORWARD_EOD','QUEUED','{}','1900-01-01')",
                id,
                "FIXTURE:" + id);
        return id;
    }

    /** 只构造固定SQL时间表达式；trace和业务原因始终参数化。 */
    private void health(String trace, String time, String reason) {
        store.db.update(
                "insert into forward_health(stage,status,last_attempt_at,reason,trace_id,details)"
                        + " values('MARKET','FAILED',"
                        + time
                        + ",?,?,'{}')"
                        + " on conflict(stage) do update set status='FAILED',last_attempt_at=excluded.last_attempt_at,"
                        + "reason=excluded.reason,trace_id=excluded.trace_id",
                reason,
                trace);
    }
}
