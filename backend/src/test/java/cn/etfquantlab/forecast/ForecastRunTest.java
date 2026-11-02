package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实隔离PostgreSQL验证执行状态与冻结事务，所有外部模型均为明确测试替身。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
@AutoConfigureMockMvc
class ForecastRunTest {
    @Autowired private Store store;
    @Autowired private ForecastRunService runs;
    @Autowired private TransactionTemplate tx;
    @Autowired private ForecastService forecasts;
    @Autowired private MockMvc mvc;
    @MockitoBean private ForecastInputs inputs;
    @MockitoBean private ForecastDeepSeek deepSeek;
    @MockitoBean private QuantClient quant;

    /** 写夹具之前必须明确隔离库，禁止根据当前默认环境猜测可以写。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        when(inputs.freeze(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> snapshot(invocation.getArgument(3)));
        when(inputs.infer(any(), eq("FUND_LIGHTGBM"))).thenReturn(fundResult());
        when(inputs.infer(any(), eq("FUND_XGBOOST"))).thenReturn(xgbResult());
        when(deepSeek.predict(any(), any())).thenReturn(aiResult("REAL"));
    }

    /** 默认生成二十个已支持机会，旧ETF协议必须明确选择，重复提交复用同批次。 */
    @Test
    void defaultMatrixAndRequestIdempotencyAreExplicit() {
        assertThat(runs.selections(store.json.createObjectNode())).hasSize(20);
        assertThat(runs.selections(store.json.createObjectNode()))
                .noneMatch(s -> "ETF_LEGACY_CLOSE".equals(s.target()));
        ObjectNode request = request();
        UUID first = runs.create(request);
        assertThat(runs.create(request)).isEqualTo(first);
        request.put("horizon", 60);
        assertThatThrownBy(() -> runs.create(request)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 开启GPT只增加独立预测，不再派生分歧任务；同批重放不重复预测。 */
    @Test
    void gptOnlyPredictsIndependentlyWithoutDisagreementTasks() {
        ReflectionTestUtils.setField(runs, "gptEnabled", true);
        try {
            when(deepSeek.predict(any(), any(), eq("GPT")))
                    .thenAnswer(
                            call -> {
                                JsonNode input = call.getArgument(1);
                                assertThat(input.has("predictions")).isFalse();
                                ObjectNode response = aiResult("REAL").deepCopy();
                                response.put("model_version", "test-gpt");
                                ((ObjectNode) response.path("result").path("output"))
                                        .put("point_return", "-0.01");
                                return response;
                            });
            UUID runId = runs.create(request());
            JsonNode executed = runs.execute(runId);
            assertThat(executed.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(executed.path("items")).hasSize(4);
            assertThat(countResults(runId)).isEqualTo(4);
            assertThat(executed.path("items"))
                    .noneMatch(item -> "GPT_DISAGREEMENT".equals(item.path("scheme").asText()));
            runs.execute(runId);
            verify(deepSeek, times(1)).predict(any(), any(), eq("GPT"));
            verify(deepSeek, never()).predict(any(), any(), eq("GPT_DISAGREEMENT"));
        } finally {
            ReflectionTestUtils.setField(runs, "gptEnabled", false);
        }
    }

    /** 升级前排队和租约过期的分歧任务均终结为停用，不等待基础预测或发起报价。 */
    @Test
    void pendingLegacyReviewsAreBlockedWithoutCallingProvider() {
        for (String previous : List.of("QUEUED", "RUNNING")) {
            UUID runId = runs.create(request());
            UUID itemId = UUID.randomUUID();
            store.db.update(
                    "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,status,"
                            + "input_snapshot,updated_at) values(?,?,'460300','FUND_NAV_CHANGE',20,"
                            + "'GPT_DISAGREEMENT',?,'{}',now()-interval '11 minutes')",
                    itemId,
                    runId,
                    previous);
            int costsBefore =
                    store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
            JsonNode run = runs.execute(runId);
            assertThat(run.path("items")).hasSize(1);
            assertThat(run.path("items").path(0).path("status").asText()).isEqualTo("BLOCKED");
            assertThat(run.path("items").path(0).path("error_code").asText())
                    .isEqualTo("GPT_DISAGREEMENT_DISABLED");
            assertThat(countResults(runId)).isZero();
            assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                    .isEqualTo(costsBefore);
        }
        verify(deepSeek, never()).predict(any(), any(), eq("GPT_DISAGREEMENT"));
        verify(quant, never()).post(eq("/analysis/gpt-review/quote"), any());
        verify(quant, never()).post(eq("/analysis/gpt-review/run"), any());
    }

    /** 三个独立个体输出不同结果；同批重放不重新推理或重复收费。 */
    @Test
    void independentResultsShareOpportunityWithoutReadingEachOther() {
        UUID runId = runs.create(request());
        JsonNode run = runs.execute(runId);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("items")).hasSize(3);
        List<JsonNode> results =
                store.db.query(
                        "select details::text from forecast_result where run_id=? order by scheme",
                        (rs, n) -> store.decode(rs.getString(1)),
                        runId);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).path("opportunity_key"))
                .isEqualTo(results.get(1).path("opportunity_key"));
        assertThat(results.get(0).path("data_cutoff"))
                .isEqualTo(results.get(1).path("data_cutoff"));
        assertThat(results.get(0).path("scheme").asText()).isEqualTo("DEEPSEEK");
        assertThat(results.get(0).path("output").path("point_return").asText()).isEqualTo("0.02");
        assertThat(results.get(1).path("output").path("point_return").asText()).isEqualTo("0.01");
        assertThat(results.get(0).path("evidence").path("input").has("quant_requests")).isFalse();
        assertThat(runs.execute(runId).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(results.get(2).path("output").path("point_return").asText()).isEqualTo("0.03");
        verify(inputs, times(1)).infer(any(), eq("FUND_XGBOOST"));
        verify(inputs, times(1)).infer(any(), eq("FUND_LIGHTGBM"));
        verify(deepSeek, times(1)).predict(any(), any());
        assertThat(runs.list(request()).get("runs")).isNotNull();
        JsonNode listing =
                store.json.valueToTree(
                        forecasts.records("460300", "FUND_NAV_CHANGE", 20, "REAL", 100, 0));
        for (JsonNode saved : results) {
            assertThat(listing.path("records").toString()).contains(saved.path("id").asText());
            JsonNode detail =
                    forecasts.detail(
                            "unified",
                            UUID.fromString(saved.path("id").asText()),
                            "460300",
                            "FUND_NAV_CHANGE",
                            20,
                            "REAL");
            assertThat(detail.path("source").asText()).isEqualTo("unified");
            if ("DEEPSEEK".equals(saved.path("scheme").asText())) {
                assertThat(detail.path("analysis").path("summary").asText()).isEqualTo("独立资讯判断夹具");
            }
        }
        assertThat(forecasts.evaluations("460300", "FUND_NAV_CHANGE", 20, "REAL")).isNotEmpty();
        assertThat(forecasts.news("460300", "FUND_NAV_CHANGE", 20, "REAL").path("summary").asText())
                .isEqualTo("独立资讯判断夹具");
    }

    /** 基金短期保存独立概率且不虚构幅度，只能从新增目标读取。 */
    @Test
    void shortFundProbabilityIsFrozenAndIsolatedFromMediumReturn() {
        when(inputs.freeze(eq("460300"), eq("FUND_NAV_UP"), eq(5), any()))
                .thenAnswer(
                        call -> {
                            ObjectNode snapshot = snapshot(call.getArgument(3));
                            ((ObjectNode) snapshot.path("context"))
                                    .put("target", "FUND_NAV_UP")
                                    .put("horizon", 5)
                                    .put("target_version", "fund-nav-up-5-v1");
                            return snapshot;
                        });
        for (String scheme : List.of("FUND_LIGHTGBM", "FUND_XGBOOST")) {
            ObjectNode response =
                    ("FUND_XGBOOST".equals(scheme) ? xgbResult() : fundResult()).deepCopy();
            ObjectNode forecast = (ObjectNode) response.path("forecasts").path(0);
            forecast.put("target_version", "fund-nav-up-5-v1");
            ((ObjectNode) forecast.path("points").path(0))
                    .put("horizon", 5)
                    .putNull("point_return")
                    .put("probability_up", "0.64");
            when(inputs.infer(any(), eq(scheme))).thenReturn(response);
        }
        ObjectNode ai = aiResult("REAL").deepCopy();
        ai.put("target", "FUND_NAV_UP").put("horizon", 5).put("target_version", "fund-nav-up-5-v1");
        ((ObjectNode) ai.path("result").path("output")).remove("point_return");
        ((ObjectNode) ai.path("result").path("output")).put("probability_up", "0.55");
        when(deepSeek.predict(any(), any())).thenReturn(ai);
        ObjectNode request = request().put("target", "FUND_NAV_UP").put("horizon", 5);
        UUID id = runs.create(request);
        assertThat(runs.execute(id).path("status").asText()).isEqualTo("COMPLETED");
        JsonNode records =
                store.json
                        .valueToTree(forecasts.records("460300", "FUND_NAV_UP", 5, "REAL", 100, 0))
                        .path("records");
        assertThat(records).hasSize(3);
        for (JsonNode record : records) {
            assertThat(record.path("source").asText()).isEqualTo("unified");
            assertThat(record.path("output_type").asText()).isEqualTo("PROBABILITY");
            assertThat(record.path("output").path("point_return").isNull()).isTrue();
            assertThat(record.path("output").hasNonNull("probability_up")).isTrue();
        }
        assertThat(
                        store.json
                                .valueToTree(
                                        forecasts.records(
                                                "460300", "FUND_NAV_CHANGE", 20, "REAL", 100, 0))
                                .path("records"))
                .isEmpty();
        assertThat(forecasts.evaluations("460300", "FUND_NAV_UP", 5, "REAL")).isNotEmpty();
    }

    /** ETF中期必须保存幅度，未来交易日历未知仍保留真实空终点而不伪造日期。 */
    @Test
    void etfMediumReturnKeepsUnknownCalendarAndRejectsWrongArtifactIdentity() {
        when(inputs.freeze(eq("510500"), eq("ETF_CLOSE_RETURN"), eq(60), any()))
                .thenAnswer(call -> closeSnapshot(call.getArgument(3)));
        for (String scheme : List.of("CLOSE_LIGHTGBM", "CLOSE_XGBOOST")) {
            when(inputs.infer(any(), eq(scheme)))
                    .thenAnswer(
                            call -> {
                                ObjectNode response = closeResult(scheme);
                                response.put(
                                        "input_cutoff_at",
                                        ((JsonNode) call.getArgument(0))
                                                .path("context")
                                                .path("data_cutoff")
                                                .asText());
                                return response;
                            });
        }
        when(deepSeek.predict(any(), any()))
                .thenReturn(
                        store.json.valueToTree(
                                java.util.Map.of(
                                        "status",
                                        "ABSTAIN",
                                        "result",
                                        java.util.Map.of("status", "ABSTAIN"))));
        ObjectNode request =
                request()
                        .put("asset", "510500")
                        .put("target", "ETF_CLOSE_RETURN")
                        .put("horizon", 60);
        UUID id = runs.create(request);
        assertThat(runs.execute(id).path("status").asText()).isEqualTo("PARTIAL");
        JsonNode records =
                store.json
                        .valueToTree(
                                forecasts.records("510500", "ETF_CLOSE_RETURN", 60, "REAL", 100, 0))
                        .path("records");
        assertThat(records).hasSize(2);
        for (JsonNode record : records) {
            assertThat(record.path("output_type").asText()).isEqualTo("RETURN_POINT");
            assertThat(record.path("output").path("point_return").asText()).isEqualTo("0.08");
            assertThat(record.path("output").path("probability_up").isNull()).isTrue();
            assertThat(record.path("target_date").isNull()).isTrue();
            assertThat(record.path("calendar_status").asText()).isEqualTo("UNCONFIRMED");
            assertThat(record.path("model_validation_status").asText()).isEqualTo("FAILED");
            assertThat(record.path("reason_codes").toString())
                    .contains("FUTURE_CALENDAR_UNAVAILABLE");
        }
        ObjectNode wrong = closeResult("CLOSE_XGBOOST");
        wrong.put("label_version", "OPEN_TO_OPEN_H1_V1");
        when(inputs.infer(any(), eq("CLOSE_XGBOOST")))
                .thenAnswer(
                        call -> {
                            wrong.put(
                                    "input_cutoff_at",
                                    ((JsonNode) call.getArgument(0))
                                            .path("context")
                                            .path("data_cutoff")
                                            .asText());
                            return wrong;
                        });
        request.put("idempotency_key", UUID.randomUUID().toString());
        JsonNode rejected = runs.execute(runs.create(request));
        assertThat(rejected.path("items").toString()).contains("QUANT_CLOSE_IDENTITY_MISMATCH");
        for (String invalid :
                List.of("model_id", "horizon", "input_cutoff_at", "probability", "point_return")) {
            when(inputs.infer(any(), eq("CLOSE_XGBOOST")))
                    .thenAnswer(
                            call -> {
                                ObjectNode response = closeResult("CLOSE_XGBOOST");
                                String cutoff =
                                        ((JsonNode) call.getArgument(0))
                                                .path("context")
                                                .path("data_cutoff")
                                                .asText();
                                response.put("input_cutoff_at", cutoff);
                                switch (invalid) {
                                    case "model_id" -> response.put("model_id", "other-artifact");
                                    case "horizon" -> response.put("horizon", 20);
                                    case "input_cutoff_at" ->
                                            response.put(
                                                    "input_cutoff_at",
                                                    Instant.parse(cutoff)
                                                            .plusSeconds(1)
                                                            .toString());
                                    case "probability" -> response.put("probability", "0.8");
                                    case "point_return" -> response.put("point_return", "-1.01");
                                    default -> throw new AssertionError("未知负例字段");
                                }
                                return response;
                            });
            request.put("idempotency_key", UUID.randomUUID().toString());
            UUID badRun = runs.create(request);
            runs.execute(badRun);
            assertThat(countResults(badRun)).as(invalid + " must be rejected").isEqualTo(1);
        }
        assertThat(forecasts.evaluations("510500", "ETF_CLOSE_RETURN", 60, "REAL")).isNotEmpty();
    }

    /** 只构造目标明确的量化输入，两个算法工件引用始终彼此独立。 */
    private ObjectNode closeSnapshot(Instant cutoff) {
        ObjectNode snapshot = snapshot(cutoff);
        ObjectNode context = (ObjectNode) snapshot.path("context");
        context.put("asset", "510500")
                .put("target", "ETF_CLOSE_RETURN")
                .put("horizon", 60)
                .put("target_version", CloseModels.LABEL + "/" + CloseModels.PROTOCOL)
                .put("horizon_unit", "TRADING_DAYS")
                .put("calendar_status", "UNCONFIRMED")
                .put("submission_deadline", Instant.now().plusSeconds(3600).toString());
        ObjectNode requests = snapshot.putObject("quant_requests");
        for (String scheme : List.of("CLOSE_LIGHTGBM", "CLOSE_XGBOOST")) {
            ObjectNode request = requests.putObject(scheme);
            request.putObject("payload").put("model_id", "test-" + scheme);
            request.putObject("model").put("validation_status", "FAILED");
        }
        return snapshot;
    }

    /** 模型返回包含冻结端点与算法身份，示例数值仅用于软件验证。 */
    private ObjectNode closeResult(String scheme) {
        ObjectNode response = store.json.createObjectNode();
        response.put("symbol", "510500")
                .put("horizon", 60)
                .put("nature", "REAL")
                .put("model_id", "test-" + scheme)
                .put("price_basis", "RAW")
                .put("algorithm", "CLOSE_XGBOOST".equals(scheme) ? "xgboost" : "lightgbm")
                .put("label_version", CloseModels.LABEL)
                .put("protocol_version", CloseModels.PROTOCOL)
                .put("model_role", CloseModels.ROLE)
                .put("signal_date", anchor())
                .put("anchor_value", "1")
                .putNull("target_date")
                .put("point_return", "0.08");
        response.putArray("limitations").add("FUTURE_CALENDAR_UNAVAILABLE");
        return response;
    }

    /** 信息不足与MOCK不能成为真实预测；另一个体成功结果保持可见。 */
    @Test
    void abstentionAndMockNeverBecomeRealForecasts() {
        when(deepSeek.predict(any(), any()))
                .thenReturn(
                        store.json.valueToTree(
                                java.util.Map.of(
                                        "status",
                                        "SUCCEEDED",
                                        "result",
                                        java.util.Map.of("status", "ABSTAIN"))));
        UUID abstained = runs.create(request());
        assertThat(runs.execute(abstained).path("status").asText()).isEqualTo("PARTIAL");
        assertThat(countResults(abstained)).isEqualTo(2);
        when(deepSeek.predict(any(), any())).thenReturn(aiResult("MOCK"));
        UUID mocked = runs.create(request());
        JsonNode run = runs.execute(mocked);
        assertThat(countResults(mocked)).isEqualTo(2);
        assertThat(run.path("items").toString()).contains("NON_REAL_PREDICTION_REJECTED");
    }

    /** 源数据缺失仍保留计划个体及失败原因，不把没有预测误称为完整覆盖。 */
    @Test
    void missingInputsRemainVisibleAsPlannedBlockedItems() {
        doThrow(new IllegalArgumentException("NAV_UNAVAILABLE"))
                .when(inputs)
                .freeze(anyString(), anyString(), anyInt(), any());
        UUID runId = runs.create(request());
        JsonNode run = runs.execute(runId);
        assertThat(run.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(run.path("items")).hasSize(3);
        for (JsonNode item : run.path("items"))
            assertThat(item.path("error_code").asText()).isEqualTo("NAV_UNAVAILABLE");
        assertThat(countResults(runId)).isZero();
    }

    /** 默认矩阵所有资产期限均有三项，资料缺失也保留完整执行分母。 */
    @Test
    void fullMatrixPlansSixtyItemsWithoutInventingMissingModels() {
        doThrow(new IllegalArgumentException("INPUT_UNAVAILABLE"))
                .when(inputs)
                .freeze(anyString(), anyString(), anyInt(), any());
        UUID id = runs.create(store.json.createObjectNode());
        JsonNode run = runs.execute(id);
        assertThat(run.path("items")).hasSize(60);
        assertThat(run.path("items").findValuesAsText("scheme"))
                .contains("FUND_XGBOOST", "GOLD_XGBOOST");
        assertThat(countResults(id)).isZero();
    }

    /** XGBoost返回其他算法身份时拒绝冻结，另两个预测个体仍正常保存。 */
    @Test
    void wrongAlgorithmCannotMasqueradeAsXgboost() {
        ObjectNode wrong = xgbResult().deepCopy();
        ((ObjectNode) wrong.path("forecasts").path(0)).put("algorithm", "lightgbm");
        when(inputs.infer(any(), eq("FUND_XGBOOST"))).thenReturn(wrong);
        UUID id = runs.create(request());
        JsonNode run = runs.execute(id);
        assertThat(run.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.path("items").toString()).contains("QUANT_ALGORITHM_MISMATCH");
        assertThat(countResults(id)).isEqualTo(2);
    }

    /** 缺少XGBoost仅阻断该项，不阻断原LightGBM和独立DeepSeek。 */
    @Test
    void missingXgboostDoesNotBlockOtherPredictors() {
        doThrow(new IllegalArgumentException("MODEL_NOT_READY"))
                .when(inputs)
                .infer(any(), eq("FUND_XGBOOST"));
        UUID id = runs.create(request());
        JsonNode run = runs.execute(id);
        assertThat(run.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(run.path("items").toString()).contains("MODEL_NOT_READY");
        assertThat(
                        store.db.queryForObject(
                                "select status from forecast_run_item where run_id=? and scheme='FUND_XGBOOST'",
                                String.class,
                                id))
                .isEqualTo("BLOCKED");
        assertThat(countResults(id)).isEqualTo(2);
    }

    /** 请求可能已计费但结果丢失时保留预算占用，任务恢复不得再外呼。 */
    @Test
    void uncertainPaidCallIsNeverAutomaticallyRepeated() {
        when(deepSeek.predict(any(), any()))
                .thenReturn(store.json.valueToTree(java.util.Map.of("status", "ABSTAIN")));
        UUID runId = runs.create(request());
        runs.execute(runId);
        UUID itemId =
                store.db.queryForObject(
                        "select id from forecast_run_item where run_id=? and scheme='DEEPSEEK'",
                        UUID.class,
                        runId);
        ObjectNode quote = store.json.createObjectNode();
        quote.put("domain", "INDEPENDENT_MARKET_FORECAST");
        quote.put("request_fingerprint", "a".repeat(64));
        quote.put("input_sha256", "b".repeat(64));
        quote.put("evidence_snapshot_id", "c".repeat(64));
        quote.put("reserved_usd", "0.01");
        quote.put("enabled", true);
        quote.put("configured", true);
        quote.putArray("eligible_source_ids").add("evidence-one");
        quote.putObject("pricing");
        when(quant.post(eq("/analysis/forecast/quote"), any())).thenReturn(quote);
        when(quant.post(eq("/analysis/forecast/run"), any()))
                .thenThrow(new IllegalStateException("test-only transport interruption"));
        ForecastDeepSeek billing =
                new ForecastDeepSeek(store, quant, tx, new BigDecimal("100"), BigDecimal.ZERO);
        JsonNode first = billing.predict(itemId, store.json.createObjectNode());
        assertThat(first.path("error_code").asText()).isEqualTo("API_FAILED_BILLING_UNCERTAIN");
        assertThat(
                        billing.predict(itemId, store.json.createObjectNode())
                                .path("error_code")
                                .asText())
                .isEqualTo("BILLING_ALREADY_RESERVED_OR_UNKNOWN");
        assertThat(
                        store.db.queryForObject(
                                "select status from api_call_cost where id=?",
                                String.class,
                                itemId))
                .isEqualTo("UNCERTAIN");
        verify(quant, times(1)).post(eq("/analysis/forecast/run"), any());
    }

    /** 目标已错过开盘时先阻断，再进入报价/量化调用，不能付费后才报迟到。 */
    @Test
    void expiredOpportunityNeverCallsAnyPredictor() {
        doAnswer(
                        invocation -> {
                            ObjectNode frozen = snapshot(invocation.getArgument(3));
                            ((ObjectNode) frozen.path("context"))
                                    .put(
                                            "submission_deadline",
                                            Instant.now().minusSeconds(1).toString());
                            return frozen;
                        })
                .when(inputs)
                .freeze(anyString(), anyString(), anyInt(), any());
        JsonNode run = runs.execute(runs.create(request()));
        assertThat(run.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(run.path("items").toString()).contains("PREDICTION_LATE_FOR_TARGET");
        verify(inputs, never()).infer(any(), any());
        verify(deepSeek, never()).predict(any(), any());
    }

    /** 跨批次相同证据只引用旧预测；其生成时间、输入截止和详情都保持原样。 */
    @Test
    void repeatedEvidenceReusesOriginalResultWithoutRetiming() {
        UUID original = runs.create(request());
        runs.execute(original);
        UUID oldItem =
                store.db.queryForObject(
                        "select id from forecast_run_item where run_id=? and scheme='DEEPSEEK'",
                        UUID.class,
                        original);
        UUID oldResult =
                store.db.queryForObject(
                        "select result_id from forecast_run_item where id=?", UUID.class, oldItem);
        String originalDetails =
                store.db.queryForObject(
                        "select details::text from forecast_result where id=?",
                        String.class,
                        oldResult);
        ObjectNode quote = quote();
        store.db.update(
                "update forecast_run_item set quote=?::jsonb where id=?",
                store.encode(quote),
                oldItem);
        store.db.update(
                "insert into api_call_cost(id,business_key,status,reserved_cost,actual_cost,pricing_snapshot) "
                        + "values(?,?,'CONFIRMED',0.01,0.005,'{}')",
                oldItem,
                "test-reuse-" + oldItem);
        when(quant.post(eq("/analysis/forecast/quote"), any())).thenReturn(quote);
        ForecastDeepSeek billing =
                new ForecastDeepSeek(store, quant, tx, new BigDecimal("100"), BigDecimal.ZERO);
        when(deepSeek.predict(any(), any()))
                .thenAnswer(
                        invocation ->
                                billing.predict(
                                        invocation.getArgument(0), invocation.getArgument(1)));
        UUID next = runs.create(request());
        JsonNode run = runs.execute(next);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode reused =
                store.db.queryForObject(
                        "select row_to_json(i)::text from forecast_run_item i where run_id=? and scheme='DEEPSEEK'",
                        (rs, n) -> store.decode(rs.getString(1)),
                        next);
        assertThat(reused.path("status").asText()).isEqualTo("REUSED");
        assertThat(reused.path("result_id").asText()).isEqualTo(oldResult.toString());
        assertThat(countResults(next)).isEqualTo(2);
        assertThat(
                        store.db.queryForObject(
                                "select details::text from forecast_result where id=?",
                                String.class,
                                oldResult))
                .isEqualTo(originalDetails);
        JsonNode detail =
                forecasts.detail("unified", oldResult, "460300", "FUND_NAV_CHANGE", 20, "REAL");
        assertThat(detail.path("generated_at"))
                .isEqualTo(store.decode(originalDetails).path("generated_at"));
        verify(quant, never()).post(eq("/analysis/forecast/run"), any());
    }

    /** 写入需登录和CSRF，幂等排队不执行模型；随后的读取不能增加费用或任务。 */
    @Test
    void explicitApiQueuesOnceAndReadIsSideEffectFree() throws Exception {
        ObjectNode request = request();
        mvc.perform(
                        post("/api/forecast/runs")
                                .with(user("admin"))
                                .contentType("application/json")
                                .content(store.encode(request)))
                .andExpect(status().isForbidden());
        JsonNode first =
                store.decode(
                        mvc.perform(
                                        post("/api/forecast/runs")
                                                .with(user("admin"))
                                                .with(csrf())
                                                .contentType("application/json")
                                                .content(store.encode(request)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        JsonNode again =
                store.decode(
                        mvc.perform(
                                        post("/api/forecast/runs")
                                                .with(user("admin"))
                                                .with(csrf())
                                                .contentType("application/json")
                                                .content(store.encode(request)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(again.path("run_id")).isEqualTo(first.path("run_id"));
        assertThat(again.path("task_id")).isEqualTo(first.path("task_id"));
        int taskCount =
                store.db.queryForObject("select count(*) from task_execution", Integer.class);
        int feeCount = store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
        mvc.perform(get("/api/forecast/runs/" + first.path("run_id").asText()).with(user("admin")))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/forecast/runs")
                                .with(user("admin"))
                                .param("asset", "460300")
                                .param("target", "FUND_NAV_CHANGE")
                                .param("horizon", "20"))
                .andExpect(status().isOk());
        assertThat(store.db.queryForObject("select count(*) from task_execution", Integer.class))
                .isEqualTo(taskCount);
        assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                .isEqualTo(feeCount);
        verify(inputs, never()).infer(any(), any());
        verify(deepSeek, never()).predict(any(), any());
    }

    /** 报价只用于隔离费用身份测试，不连接真实供应商。 */
    private ObjectNode quote() {
        ObjectNode quote = store.json.createObjectNode();
        quote.put("domain", "INDEPENDENT_MARKET_FORECAST");
        quote.put("request_fingerprint", "d".repeat(64));
        quote.put("input_sha256", "e".repeat(64));
        quote.put("evidence_snapshot_id", "f".repeat(64));
        quote.put("reserved_usd", "0.01");
        quote.put("enabled", true);
        quote.put("configured", true);
        quote.putArray("eligible_source_ids").add("test-evidence");
        quote.putObject("pricing");
        return quote;
    }

    /** 每个测试请求有独立幂等身份，不与业务历史或其他测试共享任务。 */
    private ObjectNode request() {
        return store.json.valueToTree(
                java.util.Map.of(
                        "asset",
                        "460300",
                        "target",
                        "FUND_NAV_CHANGE",
                        "horizon",
                        20,
                        "mode",
                        "REAL",
                        "idempotency_key",
                        UUID.randomUUID().toString()));
    }

    /** 冻结夹具明确区分量化输入与新闻请求，不能误用为生产市场数据。 */
    private ObjectNode snapshot(Instant cutoff) {
        ObjectNode snapshot = store.json.createObjectNode();
        ObjectNode context = snapshot.putObject("context");
        context.put("asset", "460300");
        context.put("target", "FUND_NAV_CHANGE");
        context.put("horizon", 20);
        context.put("mode", "REAL");
        context.put("data_cutoff", cutoff.toString());
        context.put("anchor_date", anchor());
        context.put("anchor_value", "1");
        context.putNull("target_date");
        context.put("target_version", "fund-nav-change-20-60-v1");
        context.put("horizon_unit", "FUND_NAV_OBSERVATIONS");
        snapshot.putObject("quant_requests")
                .putObject("FUND_LIGHTGBM")
                .putObject("payload")
                .putArray("models")
                .addObject()
                .put("id", "test-quant-version");
        ((ObjectNode) snapshot.path("quant_requests"))
                .putObject("FUND_XGBOOST")
                .putObject("payload")
                .putArray("models")
                .addObject()
                .put("id", "test-xgb-version")
                .put("algorithm", "xgboost");
        snapshot.putObject("news_request").put("test_marker", "independent-news-input");
        return snapshot;
    }

    /** 量化测试响应只输出点值能力，不造概率。 */
    private JsonNode fundResult() {
        ObjectNode result = store.json.createObjectNode();
        ObjectNode forecast = result.putArray("forecasts").addObject();
        forecast.put("fund_code", "460300");
        forecast.put("nature", "REAL");
        forecast.put("anchor_date", anchor());
        forecast.put("target_version", "fund-nav-change-20-60-v1");
        forecast.put("model_version_id", "test-quant-version");
        forecast.putArray("points").addObject().put("horizon", 20).put("point_return", "0.01");
        return result;
    }

    /** 独立XGBoost测试工件与数值，确保不会把LightGBM结果复制成第三个体。 */
    private JsonNode xgbResult() {
        ObjectNode result = fundResult().deepCopy();
        ObjectNode forecast = (ObjectNode) result.path("forecasts").path(0);
        forecast.put("algorithm", "xgboost");
        forecast.put("model_version_id", "test-xgb-version");
        ((ObjectNode) forecast.path("points").path(0)).put("point_return", "0.03");
        return result;
    }

    /** AI测试响应单独给出判断，与量化夹具不共享结果对象。 */
    private JsonNode aiResult(String nature) {
        ObjectNode response = store.json.createObjectNode();
        response.put("status", "SUCCEEDED");
        response.put("nature", nature);
        response.put("asset", "460300");
        response.put("target", "FUND_NAV_CHANGE");
        response.put("horizon", 20);
        response.put("target_version", "fund-nav-change-20-60-v1");
        response.put("model_version", "test-deepseek");
        response.put("prompt_version", "test-v1");
        response.putObject("result")
                .put("status", "AVAILABLE")
                .put("summary", "独立资讯判断夹具")
                .putObject("output")
                .put("point_return", "0.02");
        return response;
    }

    /** 日期按测试运行时钟生成，不靠固定未来日期让前瞻验证失真。 */
    private String anchor() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
    }

    /** 按批次统计实际成功记录，不把失败项计为预测。 */
    private int countResults(UUID runId) {
        return store.db.queryForObject(
                "select count(*) from forecast_result where run_id=?", Integer.class, runId);
    }
}
