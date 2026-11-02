package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.fund.FundNews;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class FundNewsEvidenceTest {
    @Autowired private Store store;
    @Autowired private AnalysisService analysis;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;
    private FundNews funds;

    /** 所有收费均为Mock响应与可回滚隔离库夹具，禁止在生产运行。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        funds = new FundNews(store, quant, analysis, tx, new BigDecimal("1.00"), true);
    }

    /** 旧周键下已收费的成功、失败和未知记录按证据跨日复用，不按状态放行重试。 */
    @Test
    void legacyPaidFingerprintIsReusedAcrossDaysRegardlessOfOutcome() {
        ObjectNode quote = quote("a");
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        int index = 0;
        for (String code : List.of("460300", "007169", "008701")) {
            String status = List.of("SUCCEEDED", "FAILED", "RUNNING").get(index);
            String billing = List.of("CONFIRMED", "UNCERTAIN", "RESERVED").get(index++);
            UUID id = legacy(code, quote, status, billing);
            JsonNode result = call(code, Instant.now().plusSeconds(86400));
            assertThat(result.path("id").asText()).isEqualTo(id.toString());
            assertThat(result.path("status").asText()).isEqualTo(status);
        }
        verify(quant, never()).post(eq("/analysis/fund/run"), any());
    }

    /** 白天不再被旧ETF失败任务或未收费周限记录阻挡，新证据仍要完整报价校验。 */
    @Test
    void unpaidLegacyLimitDoesNotBlockNewEvidenceDuringDaytime() {
        ObjectNode quote = quote("b");
        UUID old = legacy("460300", quote, "MISSING", null);
        store.db.update(
                "update fund_news_call set error_code='FUND_WEEKLY_CALL_LIMIT' where id=?", old);
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request) "
                        + "values(?,?,'EOD','FAILED','{}')",
                UUID.randomUUID(),
                "failed-etf-fixture");
        mockSuccess(quote);
        Instant morning =
                LocalDate.now(ZoneId.of("Asia/Shanghai"))
                        .atTime(9, 0)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant();
        JsonNode result = call("460300", morning);
        assertThat(result.path("id").asText()).isNotEqualTo(old.toString());
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("business_key").asText()).contains(":evidence-v1:");
        verify(quant, times(1)).post(eq("/analysis/fund/run"), any());
    }

    /** 证据/提示词改变的指纹可以新分析，同一任务即使随后输入变化也不重放。 */
    @Test
    void changedFingerprintCanRunButSameTraceCannotReplay() {
        ObjectNode old = quote("c");
        legacy("008701", old, "FAILED", "CONFIRMED");
        ObjectNode fresh = quote("d");
        mockSuccess(fresh);
        String trace = UUID.randomUUID().toString();
        MDC.put("trace_id", trace);
        try {
            JsonNode result = funds.analyze("008701", Instant.now());
            assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
            when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote("e"));
            assertThat(funds.analyze("008701", Instant.now()).path("id"))
                    .isEqualTo(result.path("id"));
        } finally {
            MDC.remove("trace_id");
        }
        verify(quant, times(1)).post(eq("/analysis/fund/quote"), any());
        verify(quant, times(1)).post(eq("/analysis/fund/run"), any());
    }

    /** ETF/黄金既有费用及未知收费预留共同占用1美元额度，不能用基金新指纹绕过。 */
    @Test
    void sharedDailyBudgetIncludesConfirmedReservedAndUncertainCosts() {
        for (String status : List.of("CONFIRMED", "RESERVED", "UNCERTAIN")) {
            store.db.update(
                    "insert into api_call_cost(id,business_key,status,reserved_cost,actual_cost,pricing_snapshot) "
                            + "values(?,?,?,0.3,0.3,'{}')",
                    UUID.randomUUID(),
                    "other-domain:" + status,
                    status);
        }
        ObjectNode quote = quote("f").put("reserved_usd", "0.20");
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        int before = store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
        JsonNode result = call("007169", Instant.now());
        assertThat(result.path("error_code").asText()).isEqualTo("DEEPSEEK_DAILY_BUDGET_EXCEEDED");
        assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                .isEqualTo(before);
        verify(quant, never()).post(eq("/analysis/fund/run"), any());
    }

    /** 资料为空时只保留缺失原因，不预留费用。 */
    @Test
    void noQualifiedEvidenceNeverCallsPaidProvider() {
        ObjectNode quote = quote("1");
        quote.putArray("eligible_source_ids");
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        JsonNode result = call("008701", Instant.now());
        assertThat(result.path("error_code").asText()).isEqualTo("NO_QUALIFIED_FUND_NEWS");
        verify(quant, never()).post(eq("/analysis/fund/run"), any());
    }

    /** 构造完整报价身份，避免用不完整Mock绕过输入身份合同。 */
    private ObjectNode quote(String marker) {
        ObjectNode result =
                store.json
                        .createObjectNode()
                        .put("domain", "FUND_NAV_RESEARCH")
                        .put("request_fingerprint", marker.repeat(64))
                        .put("input_sha256", marker.repeat(64))
                        .put("evidence_snapshot_id", marker.repeat(64))
                        .put("reserved_usd", "0.01")
                        .put("enabled", true)
                        .put("configured", true)
                        .put("model", "isolated-model")
                        .put("model_version", "isolated-version")
                        .put("prompt_version", "isolated-prompt")
                        .put("schema_version", "isolated-schema")
                        .put("evidence_version", "isolated-evidence");
        result.putArray("eligible_source_ids").add("isolated-source");
        return result;
    }

    /** 成功响应引用原报价身份，价格仅为隔离测试值。 */
    private void mockSuccess(ObjectNode quote) {
        when(quant.post(eq("/analysis/fund/quote"), any())).thenReturn(quote);
        when(quant.post(eq("/analysis/fund/run"), any()))
                .thenReturn(
                        quote.deepCopy()
                                .put("status", "SUCCEEDED")
                                .put("cost_usd", "0.001")
                                .put("billing_uncertain", false)
                                .put("request_model", "isolated-model"));
    }

    /** 模拟新持久任务并清理trace，防止测试之间相互复用。 */
    private JsonNode call(String code, Instant cutoff) {
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            return funds.analyze(code, cutoff);
        } finally {
            MDC.remove("trace_id");
        }
    }

    /** 用旧业务键与独立费用记录证明升级前记录也受幂等保护。 */
    private UUID legacy(String code, ObjectNode quote, String status, String billing) {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into fund_news_call(id,fund_code,business_key,trace_id,status,quote,input_snapshot,created_at) "
                        + "values(?,?,?, ?,?,?::jsonb,'{}',?)",
                id,
                code,
                "FUND_NAV_RESEARCH:"
                        + code
                        + ":explanation:2026-10-26:"
                        + quote.path("request_fingerprint").asText(),
                UUID.randomUUID().toString(),
                status,
                store.encode(quote),
                Timestamp.from(Instant.now().minusSeconds(60)));
        if (billing != null) {
            store.db.update(
                    "insert into api_call_cost(id,business_key,status,reserved_cost,actual_cost,pricing_snapshot) "
                            + "values(?,?,?,0.01,0.001,'{}')",
                    id,
                    "legacy:" + id,
                    billing);
        }
        return id;
    }
}
