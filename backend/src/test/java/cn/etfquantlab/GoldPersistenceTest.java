package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.gold.GoldCsv;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@AutoConfigureMockMvc
@Transactional
class GoldPersistenceTest {
    @Autowired private Store store;
    @Autowired private GoldService gold;
    @Autowired private GoldCsv csv;
    @Autowired private TaskService tasks;
    @Autowired private MockMvc mvc;
    @MockitoBean private QuantClient quant;

    /** 只写已有专属测试库，所有本类数据在测试后回滚。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 黄金迁移增加隔离命名空间，旧ETF账户完全不受写入影响。 */
    @Test
    void importsAppendRevisionsAndRetryDoesNotDuplicate() {
        int accounts = store.db.queryForObject("select count(*) from paper_account", Integer.class);
        String source = "TEST_" + UUID.randomUUID();
        ObjectNode first = observation(source, "r1", "900");
        var initial = gold.importData(batch(first), false);
        assertThat(initial.get("imported")).isEqualTo(1);
        assertThat(gold.importData(batch(first), false).get("duplicates")).isEqualTo(1);
        ObjectNode second = observation(source, "r2", "901");
        assertThat(gold.importData(batch(second), false).get("imported")).isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from gold_observation where source=?",
                                Integer.class,
                                source))
                .isEqualTo(2);
        assertThat(store.db.queryForObject("select count(*) from paper_account", Integer.class))
                .isEqualTo(accounts);
        ObjectNode changed = observation(source, "r1", "902");
        assertThatThrownBy(() -> gold.importData(batch(changed), false))
                .hasMessageContaining("相同黄金来源修订");
    }

    /** 模板无行情样例，手填质量被强制降级，不能混入官方训练输入。 */
    @Test
    void csvRemainsUnverifiedAndRejectsUnitErrors() {
        String source = "CSV_TEST_" + UUID.randomUUID();
        String line =
                "SGE_AU9999_CLOSE,"
                        + today()
                        + ",900,CNY/g,"
                        + source
                        + ",https://www.sge.com.cn/sjzx/quotation_daily_new,,UNKNOWN,"
                        + Instant.now().minusSeconds(1)
                        + ",r1,REAL_OFFICIAL_CURRENT_VINTAGE\n";
        JsonNode input = csv.parse(GoldCsv.HEADER + "\n" + line);
        gold.importData(input, true);
        assertThat(
                        store.db.queryForObject(
                                "select quality from gold_observation where source=?",
                                String.class,
                                source))
                .isEqualTo("CSV_UNVERIFIED");
        assertThat(gold.observations())
                .noneMatch(row -> source.equals(row.path("source").asText()));
        assertThatThrownBy(() -> csv.parse(GoldCsv.HEADER + "\n")).hasMessageContaining("没有数据行");
        ObjectNode invalid = observation(source, "r2", "900");
        invalid.put("unit", "USD/oz");
        assertThatThrownBy(() -> gold.importData(batch(invalid), false))
                .hasMessageContaining("CNY/g");
    }

    /** 来源短覆盖属于有效采集；失败保留最近成功时间及原观测日期。 */
    @Test
    void sourceFailureKeepsLastSuccessAndOriginalDate() {
        ObjectNode good = batch();
        good.set(
                "sources",
                store.json.valueToTree(
                        List.of(
                                Map.of(
                                        "series_id",
                                        "TEST_SOURCE",
                                        "status",
                                        "PARTIAL_COVERAGE",
                                        "last_observation_date",
                                        "2026-10-23"))));
        gold.importData(good, false);
        String success =
                store.db.queryForObject(
                        "select last_success_at::text from gold_source where series_id='TEST_SOURCE'",
                        String.class);
        assertThat(success).isNotNull();
        good.set(
                "sources",
                store.json.valueToTree(
                        List.of(
                                Map.of(
                                        "series_id",
                                        "TEST_SOURCE",
                                        "status",
                                        "FAILED",
                                        "reason",
                                        "TIMEOUT"))));
        gold.importData(good, false);
        assertThat(
                        store.db.queryForObject(
                                "select last_success_at::text from gold_source where series_id='TEST_SOURCE'",
                                String.class))
                .isEqualTo(success);
        assertThat(
                        store.db.queryForObject(
                                "select details->>'last_observation_date' from gold_source where series_id='TEST_SOURCE'",
                                String.class))
                .isEqualTo("2026-10-23");
    }

    /** 同一参考日和模型重试返回原生成时点；数据库冻结保护拒绝覆盖。 */
    @Test
    void liveForecastIsFrozenAndIdempotent() {
        String id = "gold-test-" + UUID.randomUUID();
        String revision = UUID.randomUUID().toString();
        ObjectNode currentData = batch(observation("TEST_FREEZE", revision, "900"));
        currentData.set(
                "sources",
                store.json.valueToTree(
                        List.of(Map.of("series_id", GoldService.BENCHMARK, "status", "HEALTHY"))));
        gold.importData(currentData, false);
        ObjectNode model =
                store.json
                        .createObjectNode()
                        .put("model_id", id)
                        .put("horizon", 63)
                        .put("artifact", id + ".joblib");
        model.set(
                "metadata",
                store.json
                        .createObjectNode()
                        .put("fit_at", Instant.now().minusSeconds(30).toString())
                        .put("artifact_sha256", "a".repeat(64)));
        gold.importModels(store.json.valueToTree(Map.of("models", List.of(model))));
        ObjectNode prediction =
                store.json
                        .createObjectNode()
                        .put("model_id", id)
                        .put("horizon", 63)
                        .put("reference_date", today())
                        .put("reference_price", "900")
                        .put("generated_at", Instant.now().minusSeconds(1).toString());
        prediction.put("data_cutoff", prediction.path("generated_at").asText());
        prediction.put("fit_at", Instant.now().minusSeconds(30).toString());
        prediction.put("training_cutoff", today());
        prediction.put("target_date", LocalDate.parse(today()).plusDays(90).toString());
        prediction.put("target_date_status", "ESTIMATED");
        prediction.put("label_version", "TEST_GOLD_LABEL");
        prediction.put("feature_version", "TEST_GOLD_FEATURES");
        prediction.put("calibration_version", "TEST_GOLD_CALIBRATION");
        prediction.put("artifact_sha256", "a".repeat(64));
        prediction.set(
                "input_snapshot",
                store.json.createObjectNode().put("reference_observation", revision));
        when(quant.post(eq("/gold/predict"), any()))
                .thenReturn(
                        store.json.valueToTree(
                                Map.of("predictions", List.of(prediction), "status", "SUCCEEDED")));
        JsonNode first = gold.predict().path("predictions").get(0);
        prediction.put("generated_at", Instant.now().toString());
        when(quant.post(eq("/gold/predict"), any()))
                .thenReturn(
                        store.json.valueToTree(
                                Map.of("predictions", List.of(prediction), "status", "SUCCEEDED")));
        JsonNode second = gold.predict().path("predictions").get(0);
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        assertThat(second.path("generated_at")).isEqualTo(first.path("generated_at"));
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from gold_prediction where model_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(gold.evaluate().get("appended")).isEqualTo(0);
        prediction.put("artifact_sha256", "b".repeat(64));
        when(quant.post(eq("/gold/predict"), any()))
                .thenReturn(
                        store.json.valueToTree(
                                Map.of("predictions", List.of(prediction), "status", "SUCCEEDED")));
        assertThatThrownBy(gold::predict).hasMessageContaining("工件摘要");
        assertThatThrownBy(
                        () ->
                                store.db.update(
                                        "update gold_prediction set generated_at=now() where model_id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    /** 来源失败保留旧观测，但禁止把旧价包装成新的正式预测。 */
    @Test
    void failedBenchmarkSourceBlocksNewPredictions() {
        ObjectNode data =
                batch(observation("TEST_FAILED_SOURCE", UUID.randomUUID().toString(), "900"));
        data.set(
                "sources",
                store.json.valueToTree(
                        List.of(Map.of("series_id", GoldService.BENCHMARK, "status", "FAILED"))));
        gold.importData(data, false);
        assertThat(gold.predict().path("status").asText()).isEqualTo("BLOCKED_SOURCE_UNAVAILABLE");
        assertThat(gold.readyForResearch()).isFalse();
        verifyNoInteractions(quant);
    }

    /** 停机超过30日仍从最后已保存官方交易日补起，不永久丢失中间交易日。 */
    @Test
    void longOutageSyncStartsAtLastSavedTradingDate() {
        java.sql.Date oldDate = java.sql.Date.valueOf(LocalDate.parse(today()).minusDays(45));
        ObjectNode row = observation("TEST_OUTAGE", UUID.randomUUID().toString(), "900");
        row.put("observation_date", oldDate.toString());
        gold.importData(batch(row), false);
        String lastSaved =
                store.db.queryForObject(
                        "select max(observation_date)::text from gold_observation where series_id=?"
                                + " and quality='REAL_OFFICIAL_CURRENT_VINTAGE'",
                        String.class,
                        GoldService.BENCHMARK);
        // 测试库其他夹具可能留下更近记录；隔离清理通过DELETE触发器禁止，因此只在纯净窗口验证。
        org.junit.jupiter.api.Assumptions.assumeTrue(oldDate.toString().equals(lastSaved));
        when(quant.post(eq("/gold/data/sync"), any())).thenReturn(batch());
        gold.sync(store.json.createObjectNode());
        org.mockito.ArgumentCaptor<Object> request =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(quant).post(eq("/gold/data/sync"), request.capture());
        assertThat(((Map<?, ?>) request.getValue()).get("start")).isEqualTo(oldDate.toString());
    }

    /** 缺失已知交易日不能把第64条观测误当第63日；补齐后追加正确到期记录。 */
    @Test
    void maturityWaitsForMissingKnownSessionWithoutShiftingTarget() {
        LocalDate reference = LocalDate.parse(today()).minusDays(150);
        String model = "gold-maturity-test-" + UUID.randomUUID();
        UUID predictionId = UUID.randomUUID();
        ObjectNode frozen =
                store.json
                        .createObjectNode()
                        .put("id", predictionId.toString())
                        .put("model_id", model)
                        .put("horizon", 63)
                        .put("reference_date", reference.toString())
                        .put("reference_price", "900")
                        .put("probability_up", "0.6")
                        .put("target_date", reference.plusDays(100).toString());
        store.db.update(
                "insert into gold_model(model_id,horizon,details) values(?,63,'{}')", model);
        store.db.update(
                "insert into gold_prediction(id,business_key,horizon,model_id,reference_date,generated_at,details)"
                        + " values(?,?,63,?,?,now(),?::jsonb)",
                predictionId,
                "TEST_GAP_" + predictionId,
                model,
                java.sql.Date.valueOf(reference),
                store.encode(frozen));
        java.util.ArrayList<JsonNode> prices = new java.util.ArrayList<>();
        java.util.ArrayList<String> dates = new java.util.ArrayList<>();
        ObjectNode missing = null;
        for (int day = 1; day <= 64; day++) {
            String date = reference.plusDays(day).toString();
            dates.add(date);
            ObjectNode observation =
                    observation("TEST_CALENDAR_GAP", UUID.randomUUID().toString(), "1000");
            observation.put("observation_date", date);
            if (day == 20) {
                missing = observation;
            } else {
                prices.add(observation);
            }
        }
        ObjectNode batch = store.json.createObjectNode();
        batch.set("observations", store.json.valueToTree(prices));
        batch.set(
                "sources",
                store.json.valueToTree(
                        List.of(Map.of("series_id", GoldService.BENCHMARK, "status", "HEALTHY"))));
        batch.set(
                "calendar",
                store.json.valueToTree(Map.of("dates", dates, "version", "ISOLATED_TEST_ONLY")));
        gold.importData(batch, false);
        assertThat(gold.evaluate().get("status")).isEqualTo("BLOCKED_CALENDAR_GAPS");
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from gold_actual where prediction_id=?",
                                Integer.class,
                                predictionId))
                .isZero();
        gold.importData(batch(missing), false);
        assertThat(gold.evaluate().get("appended")).isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select details->>'actual_target_date' from gold_actual where prediction_id=?",
                                String.class,
                                predictionId))
                .isEqualTo(reference.plusDays(63).toString());
        assertThat(gold.evaluate().get("appended")).isEqualTo(0);
        assertThat(
                        store.db.queryForObject(
                                "select details->>'target_date' from gold_prediction where id=?",
                                String.class,
                                predictionId))
                .isEqualTo(reference.plusDays(100).toString());
    }

    /** 重复入队返回同一持久任务ID，黄金前缀不会命中ETF任务。 */
    @Test
    void goldQueueIsIdempotent() {
        String key = UUID.randomUUID().toString();
        var first = tasks.enqueue("GOLD_SYNC", store.json.createObjectNode(), key);
        var again = tasks.enqueue("GOLD_SYNC", store.json.createObjectNode(), key);
        var etf = tasks.enqueue("MARKET_SYNC", store.json.createObjectNode(), key);
        assertThat(first.get("id")).isEqualTo(again.get("id")).isNotEqualTo(etf.get("id"));
    }

    /** 页面查询与模板下载没有外部调用；鉴权和CSRF与现有页面一致。 */
    @Test
    void readOnlyPageNeverCollectsOrTrains() throws Exception {
        mvc.perform(get("/api/gold/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gold/overview").with(user("test").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/gold/import/template").with(user("test").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/gold/tasks").with(user("test").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(quant);
    }

    /** 构造当前交易日的隔离样例，不表示真实市场价格。 */
    private ObjectNode observation(String source, String revision, String value) {
        return store.json
                .createObjectNode()
                .put("series_id", GoldService.BENCHMARK)
                .put("observation_date", today())
                .put("value", value)
                .put("unit", "CNY/g")
                .put("source", source)
                .put("source_url", "https://www.sge.com.cn/sjzx/quotation_daily_new")
                .putNull("available_at")
                .put("availability_precision", "UNKNOWN_HISTORICAL")
                .put("fetched_at", Instant.now().minusSeconds(2).toString())
                .put("revision", revision)
                .put("quality", "REAL_OFFICIAL_CURRENT_VINTAGE");
    }

    /** 统一采集器结构，空观测允许保存真实来源失败状态。 */
    private ObjectNode batch(JsonNode... observations) {
        ObjectNode result = store.json.createObjectNode();
        result.set("observations", store.json.valueToTree(List.of(observations)));
        return result;
    }

    /** 与正式服务使用同一北京时间日界，不受UTC跨日影响。 */
    private String today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
