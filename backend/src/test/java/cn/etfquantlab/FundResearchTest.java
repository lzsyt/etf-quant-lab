package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.fund.FundNews;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@AutoConfigureMockMvc
@Transactional
class FundResearchTest {
    @Autowired private Store store;
    @Autowired private cn.etfquantlab.scheduling.TaskService tasks;
    @Autowired private FundResearchService funds;
    @Autowired private FundNews news;
    @Autowired private AnalysisService analysis;
    @Autowired private TransactionTemplate tx;
    @Autowired private MockMvc mvc;
    @MockitoBean private QuantClient quant;

    /** 拒绝在正式库运行回归，测试事务结束后回滚所有夹具。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 同来源重抓不更新首次可得时间，修订只追加，前导零完整保存。 */
    @Test
    void navRevisionAndFirstAvailabilityAreImmutable() {
        ObjectNode first = observation("007169", "a".repeat(64), "1.25");
        assertThat(funds.importNav("007169", batch(first)).get("imported")).isEqualTo(1);
        ObjectNode same =
                first.deepCopy()
                        .put("first_observed_at", Instant.now().toString())
                        .put("fetched_at", Instant.now().toString());
        assertThat(funds.importNav("007169", batch(same)).get("duplicates")).isEqualTo(1);
        assertThat(funds.observations("007169").get(0).path("first_observed_at"))
                .isEqualTo(first.path("first_observed_at"));
        funds.importNav("007169", batch(observation("007169", "b".repeat(64), "1.26")));
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from fund_nav_observation where fund_code='007169'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        new java.math.BigDecimal(
                                funds.observations("007169").get(0).path("unit_nav").asText()))
                .isEqualByComparingTo("1.26");
        assertThatThrownBy(
                        () ->
                                funds.importNav(
                                        "007169", batch(first.deepCopy().put("unit_nav", "9"))))
                .hasMessageContaining("相同来源修订");
    }

    /** 无真实净值时错误状态保持缺失，GET全部没有外部副作用。 */
    @Test
    void readsAreSideEffectFreeAndAuthIsPreserved() throws Exception {
        mvc.perform(get("/api/funds")).andExpect(status().isUnauthorized());
        for (String path :
                List.of(
                        "/api/funds",
                        "/api/funds/007169/nav",
                        "/api/funds/007169/forecasts",
                        "/api/funds/007169/news",
                        "/api/funds/007169/research-summary")) {
            mvc.perform(get(path).with(user("test"))).andExpect(status().isOk());
        }
        mvc.perform(
                        post("/api/funds/007169/tasks")
                                .with(user("test"))
                                .contentType("application/json")
                                .content("{\"type\":\"FUND_TRAIN\",\"idempotency_key\":\"x\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(quant);
    }

    /** 基金目录只返回任务摘要，训练原始输入输出不会随列表下载。 */
    @Test
    void catalogOmitsTaskPayloads() {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request,result) "
                        + "values(?,?,'FUND_TRAIN','SUCCEEDED',?::jsonb,?::jsonb)",
                id,
                "catalog-test:" + id,
                "{\"private_input\":\"large\"}",
                "{\"training_details\":\"large\"}");
        var tasks = (List<JsonNode>) funds.catalog().get("tasks");
        JsonNode task =
                tasks.stream()
                        .filter(row -> id.toString().equals(row.path("id").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.has("request")).isFalse();
        assertThat(task.has("result")).isFalse();
    }

    /** 预测列表只含所选窗口摘要，保留失败结论和基准指标，内部推理仍能读取完整模型。 */
    @Test
    void forecastModelsAreSummariesWithoutChangingInternalRecords() {
        ObjectNode model = store.json.createObjectNode();
        model.put("id", "payload-model-20");
        model.put("fund_code", "460300");
        model.put("horizon", 20);
        model.put("status", "TRAINED");
        model.put("validation_status", "FAILED");
        model.put("adjustment_eligible", false);
        model.put("artifact_path", "internal-model.joblib");
        model.put("artifact_hash", "a".repeat(64));
        model.putArray("reason_codes").add("VALIDATION_BASELINES_NOT_BEATEN");
        model.putObject("metrics").putObject("validation").put("mae", "0.1");
        model.putArray("input_revisions").add("x".repeat(600_000));
        model.put("other_training_details", "y".repeat(600_000));
        store.db.update(
                "insert into fund_model(id,fund_code,horizon,details) values(?,?,?,?::jsonb)",
                "payload-model-20",
                "460300",
                20,
                store.encode(model));
        store.db.update(
                "insert into fund_model(id,fund_code,horizon,details) values(?,?,?,?::jsonb)",
                "payload-model-60",
                "460300",
                60,
                store.encode(model.deepCopy().put("id", "payload-model-60").put("horizon", 60)));
        JsonNode response = store.json.valueToTree(funds.forecasts("460300", 20));
        assertThat(response.path("models")).hasSize(1);
        JsonNode summary = response.path("models").get(0);
        assertThat(summary.path("horizon").asInt()).isEqualTo(20);
        assertThat(summary.path("validation_status").asText()).isEqualTo("FAILED");
        assertThat(summary.path("adjustment_eligible").asBoolean()).isFalse();
        assertThat(summary.path("metrics").path("validation").path("mae").asText())
                .isEqualTo("0.1");
        assertThat(summary.has("input_revisions")).isFalse();
        assertThat(summary.has("artifact_path")).isFalse();
        assertThat(summary.has("other_training_details")).isFalse();
        assertThat(store.encode(response).length()).isLessThan(10_000);
        assertThat(funds.models("460300")).hasSize(2);
        assertThat(funds.models("460300").get(0).path("input_revisions").get(0).asText())
                .hasSize(600_000);
        assertThat(funds.models("460300").get(0).path("artifact_path").asText())
                .isEqualTo("internal-model.joblib");
    }

    /** 已成功分析的历史记录不再显示准备阶段原因，数据库原证据保持不变。 */
    @Test
    void successfulNewsReadRemovesStalePreparationReasonsWithoutChangingEvidence() {
        UUID id = UUID.randomUUID();
        ObjectNode original =
                store.json
                        .createObjectNode()
                        .put("ds_status", "SUCCEEDED")
                        .put("analysis_status", "VALID")
                        .put("ds_reason", "null");
        original.put("schema_version", "fund-news-evidence-v2");
        original.put("fund_code", "007169");
        original.putObject("fund_identity")
                .put("fund_code", "007169")
                .put("name", "易方达中债1–3年国开行债券指数A")
                .put("asset_class", "BOND")
                .put("investment_mechanism", "跟踪中债1–3年国开行债券指数");
        original.putArray("reason_codes").add("NOT_ANALYZED").add("OTHER_WARNING");
        original.putArray("uncertainties").add("尚未调用基金专用分析；已准备的资料不等于模型结论").add("材料存在时效限制");
        store.db.update(
                "insert into fund_news_analysis(id,fund_code,business_key,input_cutoff_at,details) "
                        + "values(?,'007169',?,now(),?::jsonb)",
                id,
                "test-news-" + id,
                store.encode(original));

        JsonNode displayed = funds.latestNews("007169");
        assertThat(displayed.path("analysis_status").asText()).isEqualTo("VALID");
        assertThat(displayed.path("reason_codes").toString()).isEqualTo("[\"OTHER_WARNING\"]");
        assertThat(displayed.path("uncertainties").toString()).contains("材料存在时效限制");
        assertThat(displayed.path("uncertainties").toString()).doesNotContain("尚未调用");
        assertThat(displayed.path("ds_reason").isNull()).isTrue();
        assertThat(
                        store.db.queryForObject(
                                "select details::text from fund_news_analysis where id=?",
                                String.class,
                                id))
                .contains("NOT_ANALYZED");
    }

    /** 旧成功记录只能只读标为待身份核验，不能继续展示黄金机制或触发重新收费。 */
    @Test
    void legacySuccessfulNewsIsWithheldWithoutUpdatingStoredEvidence() throws Exception {
        UUID id = UUID.randomUUID();
        String original =
                """
                {"fund_code":"007169","ds_status":"SUCCEEDED","analysis_status":"VALID",
                 "schema_version":"fund-news-evidence-v1","forecast_usage":"DISPLAY_ONLY",
                 "sentiment_score":"0.1","facts":[{"quote":"华夏黄金季报"}],
                 "inferences":[{"mechanism":"本基金跟踪Au99.99，净值由金价决定"}]}
                """;
        store.db.update(
                "insert into fund_news_analysis(id,fund_code,business_key,input_cutoff_at,details) "
                        + "values(?,'007169',?,now(),?::jsonb)",
                id,
                "legacy-identity-" + id,
                original);
        JsonNode displayed = funds.latestNews("007169");
        assertThat(displayed.path("analysis_status").asText()).isEqualTo("UNVERIFIED");
        assertThat(displayed.path("forecast_usage").asText()).isEqualTo("NOT_USED");
        assertThat(displayed.path("sentiment_score").isNull()).isTrue();
        assertThat(displayed.path("inferences")).isEmpty();
        assertThat(displayed.path("facts")).isEmpty();
        JsonNode stored =
                store.decode(
                        store.db.queryForObject(
                                "select details::text from fund_news_analysis where id=?",
                                String.class,
                                id));
        assertThat(stored).isEqualTo(store.json.readTree(original));
        verifyNoInteractions(quant);
    }

    /** 失败快照仍保留失败原因和准备阶段提示，不能伪装为成功。 */
    @Test
    void failedNewsReadRetainsOriginalFailure() {
        UUID id = UUID.randomUUID();
        ObjectNode original =
                store.json
                        .createObjectNode()
                        .put("ds_status", "FAILED")
                        .put("ds_reason", "INVALID_OUTPUT")
                        .put("analysis_status", "INVALID_OUTPUT");
        original.putArray("reason_codes").add("NOT_ANALYZED");
        store.db.update(
                "insert into fund_news_analysis(id,fund_code,business_key,input_cutoff_at,details) "
                        + "values(?,'460300',?,now(),?::jsonb)",
                id,
                "test-failed-news-" + id,
                store.encode(original));

        JsonNode displayed = funds.latestNews("460300");
        assertThat(displayed.path("analysis_status").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(displayed.path("ds_reason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(displayed.path("reason_codes").toString()).contains("NOT_ANALYZED");
    }

    /** 日历参考单独返回，覆盖不足不外推，冻结点及数据库原文始终不变。 */
    @Test
    void calendarEstimateDoesNotRewriteFrozenTargets() {
        store.db.update(
                "delete from trading_calendar where trade_date between '2026-10-28' and '2026-12-31'");
        store.db.update(
                "insert into trading_calendar(trade_date,is_open,source) "
                        + "select d::date,extract(isodow from d)<6,'AKSHARE_SINA_AND_SSE_OFFICIAL_2026' "
                        + "from generate_series('2026-10-28'::date,'2026-12-31'::date,'1 day') d");
        for (int horizon : List.of(20, 60)) {
            UUID id = UUID.randomUUID();
            ObjectNode frozen =
                    store.json
                            .createObjectNode()
                            .put("id", id.toString())
                            .put("anchor_date", "2026-10-27")
                            .put("horizon", horizon)
                            .put("horizon_unit", "FUND_NAV_OBSERVATIONS");
            frozen.putArray("points").addObject().put("horizon", horizon).putNull("target_date");
            store.db.update(
                    "insert into fund_forecast(id,fund_code,business_key,horizon,generated_at,details) "
                            + "values(?,'007169',?,?,now(),?::jsonb)",
                    id,
                    "timing:" + id,
                    horizon,
                    store.encode(frozen));
            JsonNode response = store.json.valueToTree(funds.forecasts("007169", horizon));
            JsonNode timing = response.path("current_timing").path(id.toString());
            assertThat(timing.path("status").asText()).isEqualTo("WAITING_OBSERVATIONS");
            assertThat(timing.path("required").asInt()).isEqualTo(horizon);
            if (horizon == 20) {
                assertThat(timing.path("estimated_target_date").asText()).isEqualTo("2026-11-24");
                assertThat(timing.path("estimate_status").asText())
                        .isEqualTo("EXCHANGE_CALENDAR_PROXY");
            } else {
                assertThat(timing.path("estimated_target_date").isNull()).isTrue();
                assertThat(timing.path("estimate_status").asText())
                        .isEqualTo("CALENDAR_COVERAGE_INSUFFICIENT");
            }
            assertThat(
                            response.path("forecasts")
                                    .get(0)
                                    .path("points")
                                    .get(0)
                                    .path("target_date")
                                    .isNull())
                    .isTrue();
            assertThat(
                            store.decode(
                                    store.db.queryForObject(
                                            "select details::text from fund_forecast where id=?",
                                            String.class,
                                            id)))
                    .isEqualTo(frozen);
        }
    }

    /** 重训及重试不更改原冻结生成时间，预测实际值在独立评价中追加。 */
    @Test
    void forecastRetriesKeepOriginalSnapshotAndNoInventedDates() {
        funds.importNav("460300", batch(observation("460300", "c".repeat(64), "1.25")));
        ObjectNode model =
                store.json
                        .createObjectNode()
                        .put("id", "model-test")
                        .put("artifact_hash", "d".repeat(64));
        store.db.update(
                "insert into fund_model(id,fund_code,horizon,details) values('model-test','460300',20,?::jsonb)",
                store.encode(model));
        ObjectNode forecast =
                store.json
                        .createObjectNode()
                        .put("fund_code", "460300")
                        .put("capability", "RETURN_POINT")
                        .put("model_version_id", "model-test")
                        .put("data_snapshot_id", "e".repeat(64))
                        .put("generated_at", Instant.now().minusSeconds(1).toString());
        forecast.set(
                "points",
                store.json.valueToTree(List.of(Map.of("horizon", 20, "point_return", "0.01"))));
        when(quant.post(eq("/funds/models/predict"), any()))
                .thenReturn(store.json.valueToTree(Map.of("forecasts", List.of(forecast))));
        store.db.update(
                "insert into fund_news_analysis(id,fund_code,business_key,input_cutoff_at,created_at,details) "
                        + "values(?,'460300','future-news',now()+interval '1 hour',now()+interval '1 hour',"
                        + "'{\"horizon\":20,\"ds_status\":\"SUCCEEDED\",\"analysis_id\":\"future-news\"}')",
                UUID.randomUUID());
        JsonNode first = funds.predict("460300").path("forecasts").get(0);
        forecast.put("generated_at", Instant.now().toString());
        JsonNode second = funds.predict("460300").path("forecasts").get(0);
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        assertThat(second.path("generated_at")).isEqualTo(first.path("generated_at"));
        assertThat(second.path("points").get(0).path("target_date").isMissingNode()).isTrue();
        assertThat(second.path("news_usage").asText()).isEqualTo("NOT_USED");
    }

    /** 默认关闭DS时不得预留费用或调用run，且记录明确原因。 */
    @Test
    void disabledNewsDoesNotSpendAndRetryReusesCall() {
        ObjectNode quote =
                store.json
                        .createObjectNode()
                        .put("domain", "FUND_NAV_RESEARCH")
                        .put("request_fingerprint", "a".repeat(64))
                        .put("input_sha256", "b".repeat(64))
                        .put("evidence_snapshot_id", "c".repeat(64))
                        .put("reserved_usd", "0.01")
                        .put("enabled", true)
                        .put("configured", true);
        quote.putArray("eligible_source_ids").add("real-source");
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        int costs = store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            JsonNode first = news.analyze("007169", Instant.now());
            JsonNode again = news.analyze("007169", Instant.now());
            assertThat(first.path("id")).isEqualTo(again.path("id"));
            assertThat(first.path("error_code").asText()).isEqualTo("EXPLANATION_DISABLED");
            assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                    .isEqualTo(costs);
            org.mockito.Mockito.verify(quant, org.mockito.Mockito.never())
                    .post(eq("/analysis/fund/run"), any());
        } finally {
            MDC.remove("trace_id");
        }
    }

    /** 开启后只跳过旧的零费用关闭记录；成功或不确定的调用仍保持跨任务去重。 */
    @Test
    void enablingNewsDoesNotReuseDisabledRecordOrDuplicatePaidCall() {
        ObjectNode quote =
                store.json
                        .createObjectNode()
                        .put("domain", "FUND_NAV_RESEARCH")
                        .put("request_fingerprint", "a".repeat(64))
                        .put("input_sha256", "b".repeat(64))
                        .put("evidence_snapshot_id", "c".repeat(64))
                        .put("reserved_usd", "0.01")
                        .put("enabled", true)
                        .put("configured", true)
                        .put("model", "test-model")
                        .put("model_version", "test-version")
                        .put("prompt_version", "test-prompt")
                        .put("schema_version", "test-schema")
                        .put("evidence_version", "test-evidence");
        quote.putArray("eligible_source_ids").add("real-source");
        ObjectNode response =
                quote.deepCopy()
                        .put("status", "SUCCEEDED")
                        .put("cost_usd", "0.001")
                        .put("request_model", "test-model");
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        when(quant.post(eq("/analysis/fund/run"), any())).thenReturn(response);
        Instant cutoff = Instant.parse("2020-01-06T12:30:00Z");
        try {
            MDC.put("trace_id", UUID.randomUUID().toString());
            JsonNode disabled = news.analyze("007169", cutoff);
            assertThat(disabled.path("error_code").asText()).isEqualTo("EXPLANATION_DISABLED");
            FundNews enabled =
                    new FundNews(store, quant, analysis, tx, new BigDecimal("1.00"), true);
            MDC.put("trace_id", UUID.randomUUID().toString());
            JsonNode completed = enabled.analyze("007169", cutoff);
            assertThat(completed.path("id").asText()).isNotEqualTo(disabled.path("id").asText());
            assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
            MDC.put("trace_id", UUID.randomUUID().toString());
            JsonNode retry = enabled.analyze("007169", cutoff);
            assertThat(retry.path("id")).isEqualTo(completed.path("id"));
            org.mockito.Mockito.verify(quant, org.mockito.Mockito.times(1))
                    .post(eq("/analysis/fund/run"), any());

            // 收费结果不确定时保留预留，并在不同任务重试中继续复用原失败记录。
            response.put("status", "FAILED").put("billing_uncertain", true).put("cost_usd", "0");
            MDC.put("trace_id", UUID.randomUUID().toString());
            JsonNode uncertain = enabled.analyze("008701", cutoff);
            assertThat(uncertain.path("status").asText()).isEqualTo("FAILED");
            assertThat(
                            store.db.queryForObject(
                                    "select status from api_call_cost where id=?",
                                    String.class,
                                    UUID.fromString(uncertain.path("id").asText())))
                    .isEqualTo("UNCERTAIN");
            MDC.put("trace_id", UUID.randomUUID().toString());
            assertThat(enabled.analyze("008701", cutoff).path("id"))
                    .isEqualTo(uncertain.path("id"));
            org.mockito.Mockito.verify(quant, org.mockito.Mockito.times(2))
                    .post(eq("/analysis/fund/run"), any());

            // 即使历史状态异常，关闭记录只要关联费用预留，就绝不发起新的付费请求。
            MDC.put("trace_id", UUID.randomUUID().toString());
            JsonNode inconsistent = news.analyze("460300", cutoff);
            UUID inconsistentId = UUID.fromString(inconsistent.path("id").asText());
            store.db.update(
                    "insert into api_call_cost(id,business_key,status,reserved_cost,pricing_snapshot) "
                            + "values(?,?,'RESERVED',0.01,'{}'::jsonb)",
                    inconsistentId,
                    "test-existing-cost");
            MDC.put("trace_id", UUID.randomUUID().toString());
            assertThat(enabled.analyze("460300", cutoff).path("id"))
                    .isEqualTo(inconsistent.path("id"));
            org.mockito.Mockito.verify(quant, org.mockito.Mockito.times(2))
                    .post(eq("/analysis/fund/run"), any());
        } finally {
            MDC.remove("trace_id");
        }
    }

    /** 最新来源失败即使缓存日期尚新也停止生成，旧记录继续可读。 */
    @Test
    void failedSourceBlocksNewPredictions() {
        funds.importNav("008701", batch(observation("008701", "f".repeat(64), "1.25")));
        String healthyDependency = funds.dependencyVersion("008701");
        store.db.update("update fund_source_status set status='FAILED' where fund_code='008701'");
        assertThat(funds.dependencyVersion("008701")).isNotEqualTo(healthyDependency);
        assertThat(funds.predict("008701").path("status").asText()).isEqualTo("BLOCKED_SOURCE");
        assertThat(funds.nav("008701").get("status")).isEqualTo("ERROR");
        assertThat(funds.observations("008701")).hasSize(1);
        verifyNoInteractions(quant);
    }

    /** 异常负报价不能抵扣共享预算或创建费用预留。 */
    @Test
    void negativeQuoteNeverReservesBudget() {
        when(quant.post(eq("/analysis/fund/quote"), any()))
                .thenReturn(store.json.createObjectNode().put("reserved_usd", "-1"));
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            assertThatThrownBy(() -> news.analyze("007169", Instant.now()))
                    .hasMessageContaining("不能为负数");
        } finally {
            MDC.remove("trace_id");
        }
        org.mockito.Mockito.verify(quant, org.mockito.Mockito.never())
                .post(eq("/analysis/fund/run"), any());
    }

    /** 通用旧任务入口不能绕过新研究的拥有者及冻结输入检查。 */
    @Test
    void genericTaskCannotForgeComparisonOwner() throws Exception {
        mvc.perform(
                        post("/api/tasks")
                                .with(user("attacker"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"type\":\"FUND_COMPARE\",\"payload\":{\"owner_id\":\"victim\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(quant);
    }

    /** 已排队的定投比较保持历史原样，既不消费，也不允许重新入队。 */
    @Test
    void retiredComparisonTasksRemainUntouched() {
        store.db.update("update task_execution set status='SUCCEEDED'");
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request) "
                        + "values(?,?,'FUND_COMPARE','QUEUED','{}'::jsonb)",
                id,
                "retired-comparison:" + id);
        String before =
                store.db.queryForObject(
                        "select row_to_json(t)::text from task_execution t where id=?",
                        String.class,
                        id);
        tasks.consume();
        assertThat(
                        store.db.queryForObject(
                                "select row_to_json(t)::text from task_execution t where id=?",
                                String.class,
                                id))
                .isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                tasks.enqueue(
                                        "FUND_COMPARE", store.json.createObjectNode(), "retired"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(quant);
    }

    /** 构造明确隔离夹具，不向生产注册这些数字。 */
    private ObjectNode observation(String code, String hash, String nav) {
        return store.json
                .createObjectNode()
                .put("fund_code", code)
                .put("nav_date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())
                .put("unit_nav", nav)
                .put("first_observed_at", Instant.now().minusSeconds(10).toString())
                .put("fetched_at", Instant.now().minusSeconds(1).toString())
                .put("provider", "ISOLATED_TEST")
                .put("revision_hash", hash)
                .put("quality_status", "VALID")
                .put("nature", "REAL");
    }

    /** 批次沿用正式导入结构，测试只提供必要字段。 */
    private JsonNode batch(ObjectNode row) {
        return store.json.valueToTree(
                Map.of(
                        "fund_code",
                        row.path("fund_code").asText(),
                        "nature",
                        "REAL",
                        "observations",
                        List.of(row)));
    }
}
