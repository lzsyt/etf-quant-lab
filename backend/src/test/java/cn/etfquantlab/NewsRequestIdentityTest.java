package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardRules;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.forward.ForwardView;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.IntelligenceController;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class NewsRequestIdentityTest {
    @Autowired private Store store;
    @Autowired private AnalysisService news;
    @Autowired private TransactionTemplate tx;
    @Autowired private ApplicationContext context;
    @MockitoBean private QuantClient quant;

    /** 所有付费分支均由Mock响应驱动，只能在隔离数据库执行。 */
    @BeforeEach
    void isolatedDatabaseOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 精确输入中的秒级截止时刻变化不扩张日额度，冻结输入与返回的当前请求身份分开。 */
    @Test
    void sameFingerprintReusesEvenWhenExactCutoffHashChanges() {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote("original");
        stub(quote, response(quote));
        ForwardNews forward = forward();
        JsonNode first = forward.analyze(cutoff, true);
        JsonNode original = forward.get(id(first));
        ObjectNode changedCutoff = quote.deepCopy().put("input_sha256", "b".repeat(64));
        when(quant.post(eq("/analysis/forward/quote"), any())).thenReturn(changedCutoff);
        JsonNode reused = forward.analyze(cutoff.plusSeconds(30), true, true);
        assertThat(reused.path("id")).isEqualTo(first.path("id"));
        assertThat(reused.path("validation_outcome").asText()).isEqualTo("REUSED_SUCCEEDED");
        assertThat(reused.path("requested_identity").path("input_sha256").asText())
                .isEqualTo("b".repeat(64));
        assertThat(forward.get(id(first))).isEqualTo(original);
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 无新闻记录不锁死当天；新增合格新闻形成新指纹后可使用尚未消耗的普通额度。 */
    @Test
    void missingNewsDoesNotPreventNewEligibleInput() {
        Instant cutoff = uniqueDay();
        ObjectNode empty = quote("empty");
        empty.putArray("eligible_source_ids");
        stub(empty, response(empty));
        JsonNode missing = forward().analyze(cutoff, true);
        JsonNode frozen = forward().get(id(missing));
        ObjectNode current = quote("now-has-news");
        stub(current, response(current));
        JsonNode analyzed = forward().analyze(cutoff.plusSeconds(1), true);
        assertThat(missing.path("error_code").asText()).isEqualTo("NO_RELEVANT_NEWS");
        assertThat(analyzed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(feeCount(id(missing))).isZero();
        assertThat(forward().get(id(missing))).isEqualTo(frozen);
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 配置门禁不产生费用，之后合法启用同一指纹可继续处理，不能复用旧缺失冒称已验证。 */
    @ParameterizedTest
    @ValueSource(strings = {"enabled", "configured"})
    void disabledProviderNeverReservesAndCanRecover(String flag) {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote(flag).put(flag, false);
        stub(quote, response(quote));
        JsonNode missing = forward().analyze(cutoff, true);
        assertThat(missing.path("error_code").asText())
                .isEqualTo("PROVIDER_DISABLED_OR_KEY_MISSING");
        assertThat(feeCount(id(missing))).isZero();
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
        quote.put(flag, true);
        JsonNode recovered = forward().analyze(cutoff.plusSeconds(1), true);
        assertThat(recovered.path("id")).isNotEqualTo(missing.path("id"));
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 缺少稳定请求身份不允许生成收费账本，避免回退到按日期或版本名猜测相同。 */
    @Test
    void incompleteIdentityFailsBeforeBilling() {
        ObjectNode quote = quote("invalid");
        quote.remove("request_fingerprint");
        stub(quote, response(quote));
        assertThatThrownBy(() -> forward().analyze(uniqueDay(), true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 旧失败不冒充新版，只有显式请求能额外验证一批；第三个版本和普通调度仍受限。 */
    @Test
    void explicitValidationAllowsOnlyOneChangedRequestAndKeepsOldFailure() {
        Instant cutoff = uniqueDay();
        ObjectNode old = quote("old");
        ObjectNode failed =
                response(old).put("status", "FAILED").put("error_code", "INVALID_OUTPUT");
        stub(old, failed);
        JsonNode first = forward().analyze(cutoff, true);
        JsonNode frozen = forward().get(id(first));
        ObjectNode current = quote("current");
        stub(current, response(current));
        JsonNode blocked = forward().analyze(cutoff, true);
        assertThat(blocked.path("status").asText()).isEqualTo("MISSING");
        assertThat(blocked.path("quote").path("request_fingerprint"))
                .isEqualTo(current.path("request_fingerprint"));
        assertThat(blocked.path("prior_analysis_id")).isEqualTo(first.path("id"));
        JsonNode validation = forward().analyze(cutoff, true, true);
        assertThat(validation.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(validation.path("quote").path("validation_mode").asText())
                .isEqualTo("EXPLICIT_VERSION_VALIDATION");
        assertThat(forward().analyze(cutoff, true, true).path("id"))
                .isEqualTo(validation.path("id"));
        ObjectNode third = quote("third");
        stub(third, response(third));
        JsonNode rejected = forward().analyze(cutoff, true, true);
        assertThat(rejected.path("error_code").asText())
                .isEqualTo("DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED");
        assertThat(feeCount(id(rejected))).isZero();
        assertThat(forward().get(id(first))).isEqualTo(frozen);
        verify(quant, times(2)).post(eq("/analysis/forward/run"), any());
    }

    /** 显式验证不是通用加次开关，尚无旧请求可比较时不收费。 */
    @Test
    void explicitValidationRequiresAnEarlierDifferentRequest() {
        ObjectNode quote = quote("first-explicit");
        stub(quote, response(quote));
        JsonNode result = forward().analyze(uniqueDay(), true, true);
        assertThat(result.path("error_code").asText())
                .isEqualTo("EXPLICIT_VALIDATION_REQUIRES_CHANGED_REQUEST");
        assertThat(feeCount(id(result))).isZero();
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 同指纹无论失败、仍在执行或费用不确定都复用，进程重建不重置去重资格。 */
    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "RUNNING", "UNCERTAIN"})
    void unfinishedOrFailedPaidIdentityCannotBeRetried(String state) {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote(state);
        stub(quote, response(quote));
        UUID existing =
                seedPaid(
                        cutoff,
                        quote,
                        state.equals("RUNNING") ? state : "FAILED",
                        state.equals("UNCERTAIN") ? state : "RESERVED",
                        "0.01");
        JsonNode result = forward().analyze(cutoff.plusSeconds(1), true, true);
        assertThat(result.path("id").asText()).isEqualTo(existing.toString());
        assertThat(result.path("reused").asBoolean()).isTrue();
        assertThat(feeCount(existing)).isEqualTo(1);
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 额外版本验证继续计入原每日预算，已确认、预留或不确定费用都不能绕过。 */
    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "RESERVED", "UNCERTAIN"})
    void explicitValidationSharesOriginalDailyDollarBudget(String feeStatus) {
        Instant cutoff = uniqueDay();
        seedPaid(cutoff, quote("budget-old"), "FAILED", feeStatus, "0.995");
        ObjectNode current = quote("budget-current");
        stub(current, response(current));
        JsonNode result = forward().analyze(cutoff, true, true);
        assertThat(result.path("error_code").asText()).isEqualTo("DAILY_BUDGET_EXCEEDED");
        assertThat(feeCount(id(result))).isZero();
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 已收费响应与报价身份不一致时禁止融合，仍完整记录实际响应、Token和费用。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "request_fingerprint",
                "prompt_version",
                "schema_version",
                "model_version",
                "input_sha256",
                "request_model"
            })
    void quoteRunMismatchPreservesBillingAndFallsBack(String field) {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote(field);
        ObjectNode response = response(quote).put(field, "MISMATCH");
        stub(quote, response);
        ForwardNews forward = forward();
        JsonNode result = forward.analyze(cutoff, true);
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("error_code").asText()).isEqualTo("INPUT_QUOTE_MISMATCH");
        assertThat(result.path("result").path(field).asText()).isEqualTo("MISMATCH");
        assertThat(result.path("result").path("quote_identity_verified").asBoolean()).isFalse();
        JsonNode cost = fee(id(result));
        assertThat(cost.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(cost.path("actual_cost").decimalValue()).isEqualByComparingTo("0.001");
        assertThat(cost.path("input_tokens").asInt()).isEqualTo(100);
        assertThat(forward.item(result, "510300", cutoff).path("error_code").asText())
                .isEqualTo("INPUT_QUOTE_MISMATCH");
    }

    /** 新协议证据身份必须完整，不能先收费再猜测报价属于哪个冻结证据快照。 */
    @ParameterizedTest
    @ValueSource(strings = {"evidence_version", "evidence_snapshot_id", "BOTH"})
    void missingEvidenceIdentityFailsBeforeBilling(String field) {
        ObjectNode quote = evidenceQuote("missing-" + field);
        if ("BOTH".equals(field)) {
            quote.remove(List.of("evidence_version", "evidence_snapshot_id"));
        } else {
            quote.remove(field);
        }
        stub(quote, response(quote));
        assertThatThrownBy(() -> forward().analyze(uniqueDay(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("证据身份");
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 返回的证据身份缺失或变化时不能进入融合，原响应和费用证据仍须保存。 */
    @ParameterizedTest
    @ValueSource(strings = {"evidence_version", "evidence_snapshot_id"})
    void evidenceIdentityMismatchOrOmissionPreservesCostAndFallsBack(String field) {
        for (boolean missing : List.of(false, true)) {
            Instant cutoff = uniqueDay();
            ObjectNode quote = evidenceQuote("mismatch-" + field + missing);
            ObjectNode response = response(quote);
            if (missing) {
                response.remove(field);
            } else {
                response.put(field, "UNEXPECTED_EVIDENCE_IDENTITY");
            }
            stub(quote, response);
            JsonNode result = forward().analyze(cutoff, true);
            assertThat(result.path("status").asText()).isEqualTo("FAILED");
            assertThat(result.path("error_code").asText()).isEqualTo("INPUT_QUOTE_MISMATCH");
            assertThat(result.path("result").path("quote_identity_mismatches").toString())
                    .contains(field);
            assertThat(result.path("result").has(field)).isEqualTo(!missing);
            assertThat(fee(id(result)).path("actual_cost").decimalValue())
                    .isEqualByComparingTo("0.001");
            assertThat(forward().item(result, "510300", cutoff).path("error_code").asText())
                    .isEqualTo("INPUT_QUOTE_MISMATCH");
        }
    }

    /** Python付费前发现快照变化时，Java保存零调用失败并阻止消费，不伪装已完成新版验证。 */
    @Test
    void preCallSnapshotMismatchKeepsZeroCostFailureAndCannotBeConsumed() {
        Instant cutoff = uniqueDay();
        ObjectNode quote = evidenceQuote("pre-call-mismatch");
        ObjectNode response = response(quote);
        response.put("status", "FAILED");
        response.put("error_code", "INPUT_SNAPSHOT_MISMATCH");
        response.put("attempts", 0);
        response.put("cost_usd", "0");
        response.put("input_tokens", 0);
        response.put("output_tokens", 0);
        stub(quote, response);
        JsonNode analysis = forward().analyze(cutoff, true);
        assertThat(analysis.path("status").asText()).isEqualTo("FAILED");
        assertThat(analysis.path("validation_outcome").asText()).isEqualTo("FAILED");
        assertThat(analysis.path("result").path("attempts").asInt()).isZero();
        assertThat(fee(id(analysis)).path("actual_cost").decimalValue()).isEqualByComparingTo("0");
        assertThat(fee(id(analysis)).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(forward().item(analysis, "510300", cutoff).path("error_code").asText())
                .isEqualTo("INPUT_SNAPSHOT_MISMATCH");
        assertThat(forward().analyze(cutoff, true).path("id")).isEqualTo(analysis.path("id"));
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 证据ID随结果原样保存；合法不足、有效情绪和high风险继续走原有融合或降级分支。 */
    @ParameterizedTest
    @ValueSource(strings = {"AVAILABLE", "HIGH_RISK", "INSUFFICIENT_DATA"})
    void evidenceProtocolPreservesSnapshotAndExistingFusionRules(String scenario) {
        Instant cutoff = uniqueDay();
        ObjectNode quote = evidenceQuote("consumer-" + scenario);
        ObjectNode response = response(quote);
        var items = ((ObjectNode) response.path("result")).putArray("items");
        for (String symbol : ForwardRules.SYMBOLS) {
            items.add(evidenceItem(symbol, scenario));
        }
        stub(quote, response);
        JsonNode analysis = forward().analyze(cutoff, true);
        assertThat(analysis.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(analysis.path("result").path("quote_identity_verified").asBoolean()).isTrue();
        assertThat(analysis.path("quote").path("evidence_catalog"))
                .isEqualTo(quote.path("evidence_catalog"));
        assertThat(analysis.path("result").has("evidence_catalog")).isFalse();
        assertThat(analysis.path("requested_identity").path("evidence_snapshot_id"))
                .isEqualTo(quote.path("evidence_snapshot_id"));
        JsonNode frozen = forward().get(id(analysis));
        for (String symbol : ForwardRules.SYMBOLS) {
            JsonNode item = forward().item(analysis, symbol, cutoff);
            assertThat(item.path("evidence_ids").get(0).asText()).isEqualTo("EV_" + "e".repeat(64));
            assertThat(item.has("facts")).isFalse();
            Map<String, Object> decision =
                    ForwardRules.decide("FUSION_EXPERIMENT", symbol, testProbability(symbol), item);
            if ("INSUFFICIENT_DATA".equals(scenario)) {
                assertThat(item.path("sentiment_score").isNull()).isTrue();
                assertThat(decision)
                        .containsEntry("effective_mode", "ML_FALLBACK")
                        .containsEntry("fallback_reason", "INSUFFICIENT_DATA")
                        .containsEntry("news_available", false);
            } else {
                assertThat(decision)
                        .containsEntry("effective_mode", "FUSION")
                        .containsEntry("news_available", true);
                assertThat((BigDecimal) decision.get("score")).isEqualByComparingTo("0.2");
                if ("HIGH_RISK".equals(scenario)) {
                    assertThat(decision)
                            .containsEntry("reason_code", "NEWS_RISK_VETO")
                            .containsEntry("eligible", false);
                } else {
                    assertThat(decision).containsEntry("eligible", true);
                }
            }
        }
        assertThat(forward().get(id(analysis))).isEqualTo(frozen);
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(quant).post(eq("/analysis/forward/run"), sent.capture());
        JsonNode payload = store.json.valueToTree(sent.getValue());
        assertThat(payload.path("expected_input_sha256")).isEqualTo(quote.path("input_sha256"));
        assertThat(payload.path("expected_evidence_snapshot_id"))
                .isEqualTo(quote.path("evidence_snapshot_id"));
        assertThat(frozen.path("input_snapshot").path("expected_evidence_snapshot_id"))
                .isEqualTo(quote.path("evidence_snapshot_id"));
    }

    /** 旧冻结分析无证据ID仍按旧核心字段读取，不因新协议升级而回写或拒绝旧结果。 */
    @Test
    void oldFrozenAnalysisWithoutEvidenceIdentityRemainsReadable() {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote("legacy-readable");
        ObjectNode response = response(quote);
        ObjectNode item = evidenceItem("510300", "AVAILABLE");
        item.remove("evidence_ids");
        item.putArray("facts")
                .addObject()
                .put("source_id", "ISOLATED_SOURCE")
                .put("quote", "旧版隔离事实摘录");
        ((ObjectNode) response.path("result")).putArray("items").add(item);
        UUID oldId = seedPaid(cutoff, quote, "SUCCEEDED", "CONFIRMED", "0.001");
        store.db.update(
                "update forward_analysis set result=?::jsonb where id=?",
                store.encode(response),
                oldId);
        JsonNode original = forward().get(oldId);
        assertThat(forward().item(original, "510300", cutoff))
                .isEqualTo(original.path("result").path("result").path("items").get(0));
        assertThat(forward().item(original, "510300", cutoff).path("facts")).hasSize(1);
        assertThat(forward().get(oldId)).isEqualTo(original);
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 当日显式额度已经使用时，新证据协议也只能等待，不改旧失败、不增加费用记录。 */
    @Test
    void evidenceUpgradeCannotExpandExhaustedDailyValidationAllowance() {
        Instant cutoff = uniqueDay();
        UUID ordinary = seedPaid(cutoff, quote("ordinary-old"), "FAILED", "RESERVED", "0.01");
        ObjectNode explicitQuote =
                quote("explicit-old").put("validation_mode", "EXPLICIT_VERSION_VALIDATION");
        UUID explicit = seedPaid(cutoff, explicitQuote, "FAILED", "CONFIRMED", "0.001");
        JsonNode ordinaryBefore = forward().get(ordinary);
        JsonNode explicitBefore = forward().get(explicit);
        JsonNode ordinaryFeeBefore = fee(ordinary);
        JsonNode explicitFeeBefore = fee(explicit);
        ObjectNode current = evidenceQuote("current-evidence");
        stub(current, response(current));
        JsonNode result = forward().analyze(cutoff, true, true);
        assertThat(result.path("status").asText()).isEqualTo("MISSING");
        assertThat(result.path("validation_outcome").asText()).isEqualTo("NOT_VALIDATED");
        assertThat(result.path("error_code").asText())
                .isEqualTo("DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED");
        assertThat(result.path("quote").path("evidence_snapshot_id"))
                .isEqualTo(current.path("evidence_snapshot_id"));
        assertThat(feeCount(id(result))).isZero();
        assertThat(forward().get(ordinary)).isEqualTo(ordinaryBefore);
        assertThat(forward().get(explicit)).isEqualTo(explicitBefore);
        assertThat(fee(ordinary)).isEqualTo(ordinaryFeeBefore);
        assertThat(fee(explicit)).isEqualTo(explicitFeeBefore);
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 已保存旧版没有指纹也能按明确版本变化验证，但不改旧失败或旧费用证据。 */
    @Test
    void legacyFailureAllowsProvenVersionChangeWithoutRewritingIt() {
        Instant cutoff = uniqueDay();
        ObjectNode legacy = quote("old-legacy");
        legacy.remove("request_fingerprint");
        UUID oldId = seedPaid(cutoff, legacy, "FAILED", "CONFIRMED", "0.001");
        JsonNode oldRow = forward().get(oldId);
        ObjectNode current = quote("current-legacy");
        stub(current, response(current));
        JsonNode result = forward().analyze(cutoff, true, true);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(forward().get(oldId)).isEqualTo(oldRow);
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 同任务跨北京自然日并发恢复只保留首个冻结请求，租约恢复不追加费用。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameTaskAcrossMidnightIsSerializedAndRestored() throws Exception {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote("cross-day");
        stub(quote, response(quote));
        String taskTrace = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(2);
        JsonNode first;
        try {
            var one = pool.submit(() -> analyzeWithTrace(cutoff, taskTrace));
            var two = pool.submit(() -> analyzeWithTrace(cutoff.plusSeconds(86400), taskTrace));
            first = one.get();
            assertThat(two.get().path("id")).isEqualTo(first.path("id"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(analyzeWithTrace(cutoff.plusSeconds(172800), taskTrace).path("id"))
                .isEqualTo(first.path("id"));
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from forward_analysis where trace_id=?",
                                Integer.class,
                                taskTrace))
                .isEqualTo(1);
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 新任务复用旧分析后即使未保存最终队列结果就崩溃，跨日仍由持久引用恢复原分析。 */
    @Test
    void reusingTaskRestoresOriginalAnalysisAcrossMidnight() {
        Instant cutoff = uniqueDay();
        ObjectNode quote = quote("task-a");
        stub(quote, response(quote));
        JsonNode original = forward().analyze(cutoff, true);
        JsonNode originalRow = forward().get(id(original));
        UUID taskId = UUID.randomUUID();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request)"
                        + " values(?,?,'NEWS_ANALYZE','RUNNING','{}')",
                taskId,
                "ISOLATED_" + taskId);
        JsonNode reused = analyzeWithTrace(cutoff.plusSeconds(1), taskId.toString());
        assertThat(reused.path("id")).isEqualTo(original.path("id"));
        assertThat(
                        store.db.queryForObject(
                                "select result->>'forward_analysis_id' from task_execution where id=?",
                                String.class,
                                taskId))
                .isEqualTo(original.path("id").asText());
        ObjectNode nextDay = quote("task-next-day");
        stub(nextDay, response(nextDay));
        JsonNode recovered = analyzeWithTrace(cutoff.plusSeconds(86400), taskId.toString());
        assertThat(recovered.path("id")).isEqualTo(original.path("id"));
        assertThat(forward().get(id(original))).isEqualTo(originalRow);
        verify(quant, times(2)).post(eq("/analysis/forward/quote"), any());
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 任务显式参数只走新闻分析；队列完成同时返回真实验证结论，再建任务复用同一费用。 */
    @Test
    void explicitTaskReportsOutcomeAndDoesNotInvokeDailyWorkflow() {
        Instant cutoff = Instant.now();
        seedPaid(cutoff, quote("task-old"), "FAILED", "CONFIRMED", "0.001");
        ObjectNode current = quote("task-current");
        stub(current, response(current).put("nature", "MOCK"));
        TaskService worker =
                new TaskService(
                        store,
                        context.getBean(MarketService.class),
                        context.getBean(ResearchService.class),
                        tx,
                        context.getBean(DailyService.class),
                        context.getBean(ResearchReportService.class),
                        context.getBean(PredictionService.class),
                        context.getBean(ForwardService.class),
                        news,
                        forward());
        try {
            String analysisId = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                UUID taskId =
                        (UUID)
                                worker.enqueue(
                                                "NEWS_ANALYZE",
                                                store.decode("{\"validate_version\":true}"),
                                                UUID.randomUUID().toString())
                                        .get("id");
                store.db.update(
                        "update task_execution set updated_at=(select min(updated_at)"
                                + "-interval '1 day' from task_execution) where id=?",
                        taskId);
                worker.consume();
                JsonNode task =
                        store.db.queryForObject(
                                "select row_to_json(t)::text from task_execution t where id=?",
                                (rs, row) -> store.decode(rs.getString(1)),
                                taskId);
                JsonNode result = task.path("result");
                assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
                assertThat(result.path("validation_outcome").asText())
                        .isEqualTo(attempt == 0 ? "MOCK_ONLY" : "REUSED_MOCK_ONLY");
                assertThat(result.path("decisions_generated").asBoolean()).isFalse();
                assertThat(result.path("reused").asBoolean()).isEqualTo(attempt > 0);
                assertThat(result.path("analysis").path("quote").path("validation_mode").asText())
                        .isEqualTo("EXPLICIT_VERSION_VALIDATION");
                if (analysisId == null) {
                    analysisId = result.path("analysis").path("id").asText();
                } else {
                    assertThat(result.path("analysis").path("id").asText()).isEqualTo(analysisId);
                }
            }
        } finally {
            worker.closeHeartbeat();
        }
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
        verify(quant, never()).post(eq("/news/collect"), any());
    }

    /** 前瞻页新闻与健康两条路径均隐藏全文和原提示词，摘要、版本和预算保留，原行不改。 */
    @Test
    void forwardProjectionRedactsNewsAndHealthWithoutChangingStoredEvidence() {
        String marker = UUID.randomUUID().toString();
        ObjectNode quote = quote(marker);
        quote.put("input_text", "PRIVATE_PROMPT");
        quote.putObject("input_budget_breakdown").put("total_reserved", 700);
        UUID analysisId = seedPaid(uniqueDay(), quote, "FAILED", "CONFIRMED", "0.001");
        JsonNode snapshot =
                store.decode(
                        "{\"news\":[{\"summary\":\"VISIBLE_SUMMARY\",\"metadata\":{"
                                + "\"content\":\"PRIVATE_BODY\",\"content_type\":\"FULL_TEXT\"}}]}");
        store.db.update(
                "update forward_analysis set input_snapshot=?::jsonb,created_at=now()+interval '1 day' where id=?",
                store.encode(snapshot),
                analysisId);
        JsonNode raw = forward().get(analysisId);
        String stage = "ISOLATED_" + marker;
        store.db.update(
                "insert into forward_health(stage,status,last_attempt_at,trace_id,details)"
                        + " values(?,'OK',now(),?,?::jsonb)",
                stage,
                marker,
                store.encode(Map.of("collection", snapshot)));
        ForwardService service = mock(ForwardService.class);
        when(service.accounts()).thenReturn(List.of());
        JsonNode overview = store.json.valueToTree(new ForwardView(store, service).overview());
        JsonNode newsRow = find(overview.path("news"), "id", analysisId.toString());
        JsonNode health = find(overview.path("health"), "stage", stage);
        assertThat(newsRow.toString()).doesNotContain("PRIVATE_BODY", "PRIVATE_PROMPT");
        assertThat(health.toString()).doesNotContain("PRIVATE_BODY", "PRIVATE_PROMPT");
        assertThat(newsRow.path("input_snapshot").path("news").get(0).path("summary").asText())
                .isEqualTo("VISIBLE_SUMMARY");
        assertThat(newsRow.path("quote").path("prompt_version"))
                .isEqualTo(quote.path("prompt_version"));
        assertThat(
                        newsRow.path("quote")
                                .path("input_budget_breakdown")
                                .path("total_reserved")
                                .asInt())
                .isEqualTo(700);
        assertThat(forward().get(analysisId)).isEqualTo(raw);
        assertThat(
                        store.db.queryForObject(
                                "select details::text from forward_health where stage=?",
                                String.class,
                                stage))
                .contains("PRIVATE_BODY");
    }

    /** 两个页面只展示获准来源的冻结报价摘录，不接受模型复制清单，原文证据不被投影改写。 */
    @Test
    void evidenceProjectionUsesFrozenApprovedSourcesAndPreservesInternalSnapshot() {
        String marker = UUID.randomUUID().toString();
        ObjectNode quote = evidenceQuote(marker);
        ObjectNode official = (ObjectNode) quote.path("evidence_catalog").get(0);
        official.put("text", "仅属于冻结官方证据的隔离原文。".repeat(40));
        official.put("field", "summary");
        official.put("source_content_type", "SUMMARY");
        official.put("standalone", false);
        official.put("context_required", true);
        official.put("published_at", "2026-10-25T01:00:00Z");
        official.put("fetched_at", "2026-10-25T02:00:00Z");
        ObjectNode unapproved = official.deepCopy();
        unapproved.put("id", "EV_" + "d".repeat(64));
        unapproved.put("source_id", "UNAPPROVED_SOURCE");
        unapproved.put("text", "UNAPPROVED_PRIVATE_EVIDENCE");
        ((com.fasterxml.jackson.databind.node.ArrayNode) quote.path("evidence_catalog"))
                .add(unapproved);
        UUID analysisId = seedPaid(uniqueDay(), quote, "FAILED", "CONFIRMED", "0.001");
        ObjectNode input = store.json.createObjectNode();
        var sources = input.putArray("news");
        sources.addObject()
                .put("id", "ISOLATED_SOURCE")
                .put("source_name", "CSRC")
                .put("source_url", "https://www.csrc.gov.cn/isolated/evidence.html")
                .put("summary", "允许显示的摘要");
        sources.addObject()
                .put("id", "UNAPPROVED_SOURCE")
                .put("source_name", "UNAPPROVED")
                .put("source_url", "https://unapproved.invalid/news")
                .put("summary", "公开摘要");
        ObjectNode response = response(quote).put("status", "FAILED");
        response.putArray("evidence_catalog")
                .addObject()
                .put("text", "MODEL_GENERATED_FAKE_CATALOG");
        response.putObject("quote")
                .putArray("evidence_catalog")
                .addObject()
                .put("source_id", "ISOLATED_SOURCE")
                .put("text", "NESTED_MODEL_FAKE_CATALOG");
        response.set("input_snapshot", input.deepCopy());
        store.db.update(
                "update forward_analysis set input_snapshot=?::jsonb,result=?::jsonb,"
                        + "created_at=now()+interval '1 day' where id=?",
                store.encode(input),
                store.encode(response),
                analysisId);
        JsonNode original = forward().get(analysisId);
        ForwardService service = mock(ForwardService.class);
        when(service.accounts()).thenReturn(List.of());
        JsonNode forwardView = store.json.valueToTree(new ForwardView(store, service).overview());
        JsonNode intelligence =
                store.json.valueToTree(
                        context.getBean(IntelligenceController.class)
                                .overview("", "", "", "", "", "", "", 10, 0));
        for (JsonNode view :
                List.of(
                        find(forwardView.path("news"), "id", analysisId.toString()),
                        find(intelligence.path("analyses"), "id", analysisId.toString()))) {
            assertThat(view.path("result").has("evidence_catalog")).isFalse();
            assertThat(view.path("result").path("quote").has("evidence_catalog")).isFalse();
            JsonNode catalog = view.path("quote").path("evidence_catalog");
            assertThat(catalog).hasSize(2);
            JsonNode approved = catalog.get(0);
            assertThat(approved.has("text")).isFalse();
            assertThat(approved.path("display_status").asText()).isEqualTo("DISPLAYABLE");
            assertThat(approved.path("display_text").asText())
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(240);
            assertThat(approved.path("display_truncated").asBoolean()).isTrue();
            assertThat(approved.path("id")).isEqualTo(official.path("id"));
            assertThat(approved.path("context_required").asBoolean()).isTrue();
            assertThat(approved.path("source_content_type").asText()).isEqualTo("SUMMARY");
            assertThat(approved.path("published_at")).isEqualTo(official.path("published_at"));
            assertThat(catalog.get(1).path("display_status").asText())
                    .isEqualTo("SOURCE_NOT_APPROVED");
            assertThat(view.toString())
                    .doesNotContain(
                            "UNAPPROVED_PRIVATE_EVIDENCE",
                            "MODEL_GENERATED_FAKE_CATALOG",
                            "NESTED_MODEL_FAKE_CATALOG");
        }
        assertThat(forward().get(analysisId)).isEqualTo(original);
    }

    /** 测试服务直接使用隔离库与Mock HTTP客户端，不读取真实调用开关。 */
    private ForwardNews forward() {
        return new ForwardNews(store, quant, news, tx, new BigDecimal("1.00"), true);
    }

    /** 请求身份固定且可控，日期只是隔离测试之间的日额度。 */
    private ObjectNode quote(String version) {
        ObjectNode result = store.json.createObjectNode();
        result.put("prompt_version", version);
        result.put("schema_version", "TEST_SCHEMA");
        result.put("provider", "deepseek");
        result.put("model", "TEST_MODEL");
        result.put("model_version", "TEST_MODEL_VERSION");
        result.put(
                "request_fingerprint",
                String.format("%064x", Integer.toUnsignedLong(version.hashCode())));
        result.put("input_sha256", "a".repeat(64));
        result.put("reserved_usd", "0.01");
        result.put("enabled", true);
        result.put("configured", true);
        result.putObject("pricing");
        result.putArray("eligible_source_ids").add("ISOLATED_SOURCE");
        return result;
    }

    /** 证据清单为隔离的Python已校验响应夹具，Java只存档与核对快照身份。 */
    private ObjectNode evidenceQuote(String version) {
        ObjectNode result = quote(version);
        result.put("schema_version", "etf-symbol-evidence-v2.1.2");
        result.put("evidence_version", "news-evidence-v1");
        result.put("evidence_snapshot_id", "c".repeat(64));
        ObjectNode evidence = result.putArray("evidence_catalog").addObject();
        evidence.put("id", "EV_" + "e".repeat(64));
        evidence.put("source_id", "ISOLATED_SOURCE");
        evidence.put("text", "隔离的冻结原文事实，不属于真实模型分析。");
        return result;
    }

    /** 逐ETF响应保留原核心字段，只以证据ID关联事实，不让Java重新分析新闻。 */
    private ObjectNode evidenceItem(String symbol, String scenario) {
        boolean insufficient = "INSUFFICIENT_DATA".equals(scenario);
        ObjectNode item = store.json.createObjectNode();
        item.put("symbol", symbol);
        item.put("horizon", 1);
        item.put("status", insufficient ? scenario : "AVAILABLE");
        item.putArray("relevant_source_ids").add("ISOLATED_SOURCE");
        item.putArray("evidence_ids").add("EV_" + "e".repeat(64));
        item.put("rationale", "隔离测试结论");
        item.put("direction", insufficient ? "unknown" : "positive");
        if (insufficient) {
            item.putNull("sentiment_score");
            item.putNull("risk_level");
        } else {
            item.put("sentiment_score", new BigDecimal("0.2"));
            item.put("risk_level", "HIGH_RISK".equals(scenario) ? "high" : "low");
        }
        return item;
    }

    /** 固定P=0.6仅用于实际规则的内存消费断言，不训练或保存模型预测。 */
    private ObjectNode testProbability(String symbol) {
        ObjectNode ml = store.json.createObjectNode();
        ml.put("status", "SUCCEEDED");
        ml.put("symbol", symbol);
        ml.put("algorithm", "lightgbm");
        ml.put("feature_version", "ohlcv-v2");
        ml.put("label_version", ForwardRules.LABEL);
        ml.put("protocol_version", ForwardRules.PROTOCOL);
        ml.put("target_semantics", ForwardRules.TARGET);
        ml.put("nature", "REAL");
        ml.put("price_basis", "RAW");
        ml.put("model_role", "FORWARD_PRODUCTION");
        ml.put("horizon", 1);
        ml.put("model_id", "IN_MEMORY_TEST_ONLY");
        ml.put("probability", new BigDecimal("0.6"));
        ml.put("raw_probability", new BigDecimal("0.6"));
        return ml;
    }

    /** 模型结果保留请求身份及可核对费用，REAL仅为隔离分支夹具，不向运行环境展示。 */
    private ObjectNode response(JsonNode quote) {
        ObjectNode result = (ObjectNode) quote.deepCopy();
        result.remove("evidence_catalog");
        result.put("request_model", quote.path("model").asText());
        result.put("status", "SUCCEEDED");
        result.put("nature", "REAL");
        result.put("cost_usd", "0.001");
        result.put("input_tokens", 100);
        result.put("output_tokens", 30);
        result.put("duration_ms", 5);
        result.put("billing_uncertain", false);
        result.putObject("result").putArray("items");
        return result;
    }

    /** 报价和实际响应全部通过Mockito给出，禁止外部采集或模型请求。 */
    private void stub(JsonNode quote, JsonNode response) {
        when(quant.post(eq("/analysis/forward/quote"), any())).thenReturn(quote);
        when(quant.post(eq("/analysis/forward/run"), any())).thenReturn(response);
    }

    /** 已存在费用夹具按指定北京时间日入账，覆盖共享预算和旧版恢复路径。 */
    private UUID seedPaid(
            Instant cutoff, JsonNode quote, String status, String feeStatus, String cost) {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into forward_analysis(id,business_key,input_cutoff,status,trace_id,quote,input_snapshot)"
                        + " values(?,?,?,?,?,?::jsonb,'{}')",
                id,
                "ISOLATED_" + id,
                Timestamp.from(cutoff),
                status,
                UUID.randomUUID().toString(),
                store.encode(quote));
        store.db.update(
                "insert into api_call_cost(id,business_key,status,reserved_cost,actual_cost,"
                        + "pricing_snapshot,created_at)"
                        + " values(?,?,?,?,?,'{}',?)",
                id,
                "ISOLATED_" + id,
                feeStatus,
                new BigDecimal(cost),
                new BigDecimal(cost),
                Timestamp.from(cutoff));
        return id;
    }

    /** 模拟任务恢复时设置同一个持久trace，每个线程结束后清除上下文。 */
    private JsonNode analyzeWithTrace(Instant cutoff, String trace) {
        MDC.put("trace_id", trace);
        try {
            return forward().analyze(cutoff, true);
        } finally {
            MDC.remove("trace_id");
        }
    }

    /** 获取当前费用证据，避免仅根据模型返回推断是否已计费。 */
    private JsonNode fee(UUID id) {
        return store.db.queryForObject(
                "select row_to_json(c)::text from api_call_cost c where id=?",
                (rs, row) -> store.decode(rs.getString(1)),
                id);
    }

    /** 缺失分支必须没有生成费用预留。 */
    private int feeCount(UUID id) {
        return store.db.queryForObject(
                "select count(*) from api_call_cost where id=?", Integer.class, id);
    }

    /** 将结果中的既有主键转换为查询参数。 */
    private UUID id(JsonNode result) {
        return UUID.fromString(result.path("id").asText());
    }

    /** 从有限页面投影中找到本次夹具，缺失应立即让测试失败。 */
    private JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("缺少隔离夹具：" + value);
    }

    /** 隔离测试使用独立日期，不影响真实自然日的费用状态。 */
    private Instant uniqueDay() {
        return LocalDate.of(2040, 1, 1)
                .plusDays(Math.floorMod(UUID.randomUUID().hashCode(), 150000))
                .atTime(12, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    }
}
