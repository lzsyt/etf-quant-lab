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
import cn.etfquantlab.forecast.ForecastDeepSeek;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.fund.FundNews;
import cn.etfquantlab.gold.GoldNews;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实隔离库验证两个供应商各一美元，所有外呼由测试替身响应且事务回滚。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ProviderBudgetTest {
    @Autowired private Store store;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;

    /** 清理仅限已断言的测试库事务，确保边界金额不受其他测试残留影响。 */
    @BeforeEach
    void isolatedLedger() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("delete from api_call_cost");
        MDC.put("trace_id", UUID.randomUUID().toString());
    }

    /** 不将测试任务标记泄漏到后续用例。 */
    @AfterEach
    void clearTrace() {
        MDC.remove("trace_id");
    }

    /** 一个供应商已用满时自身必须阻断，另一供应商仍能正常预留并调用。 */
    @ParameterizedTest
    @CsvSource({
        "GPT,CONFIRMED", "GPT,RESERVED", "GPT,UNCERTAIN",
        "DEEPSEEK,CONFIRMED", "DEEPSEEK,RESERVED", "DEEPSEEK,UNCERTAIN"
    })
    void independentDollarLimits(String full, String status) {
        fee(full, status, Instant.now());
        ForecastDeepSeek billing =
                new ForecastDeepSeek(store, quant, tx, BigDecimal.ONE, BigDecimal.ONE);
        String other = full.equals("GPT") ? "DEEPSEEK" : "GPT";
        String fullRoute = route(full);
        String otherRoute = route(other);
        stub(fullRoute, quote("INDEPENDENT_MARKET_FORECAST"));
        stub(otherRoute, quote("INDEPENDENT_MARKET_FORECAST"));
        assertThat(
                        billing.predict(item(full), store.json.createObjectNode(), full)
                                .path("error_code")
                                .asText())
                .isEqualTo(full + "_DAILY_BUDGET_EXCEEDED");
        assertThat(
                        billing.predict(item(other), store.json.createObjectNode(), other)
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        verify(quant, never()).post(eq(fullRoute + "/run"), any());
        verify(quant, times(1)).post(eq(otherRoute + "/run"), any());
    }

    /** 历史分歧费用仍占GPT额度；独立预测可执行，但分歧入口在报价前拒绝。 */
    @Test
    void gptRolesShareTheirDollarAndNeverRepeatChargedItems() {
        fee("GPT_DISAGREEMENT", "CONFIRMED", Instant.now());
        store.db.update("update api_call_cost set actual_cost=0.99");
        stub("/analysis/gpt", quote("INDEPENDENT_MARKET_FORECAST"));
        ForecastDeepSeek billing =
                new ForecastDeepSeek(store, quant, tx, BigDecimal.ONE, BigDecimal.ONE);
        UUID id = item("GPT");
        assertThat(
                        billing.predict(id, store.json.createObjectNode(), "GPT")
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        billing.predict(id, store.json.createObjectNode(), "GPT")
                                .path("error_code")
                                .asText())
                .isEqualTo("BILLING_ALREADY_RESERVED_OR_UNKNOWN");
        // 新证据使用独立报价指纹，明确走预算判断；相同证据仍应优先由原去重规则拒绝。
        stub("/analysis/gpt", quote("INDEPENDENT_MARKET_FORECAST"));
        assertThat(
                        billing.predict(item("GPT"), store.json.createObjectNode(), "GPT")
                                .path("error_code")
                                .asText())
                .isEqualTo("GPT_DAILY_BUDGET_EXCEEDED");
        int costsBefore =
                store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
        assertThat(
                        billing.predict(
                                        item("GPT_DISAGREEMENT"),
                                        store.json.createObjectNode(),
                                        "GPT_DISAGREEMENT")
                                .path("error_code")
                                .asText())
                .isEqualTo("GPT_DISAGREEMENT_DISABLED");
        assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                .isEqualTo(costsBefore);
        verify(quant, times(1)).post(eq("/analysis/gpt/run"), any());
        verify(quant, never()).post(eq("/analysis/gpt-review/quote"), any());
        verify(quant, never()).post(eq("/analysis/gpt-review/run"), any());
    }

    /** 北京时间前一日费用不占用当日额度，默认未配置GPT预算则不允许新收费。 */
    @Test
    void previousShanghaiDayAndDisabledGptBudget() {
        Instant beforeDay =
                Instant.now()
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toLocalDate()
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .minusSeconds(1);
        fee("GPT", "UNCERTAIN", beforeDay);
        stub("/analysis/gpt", quote("INDEPENDENT_MARKET_FORECAST"));
        ForecastDeepSeek disabled =
                new ForecastDeepSeek(store, quant, tx, BigDecimal.ONE, BigDecimal.ZERO);
        UUID id = item("GPT");
        assertThat(
                        disabled.predict(id, store.json.createObjectNode(), "GPT")
                                .path("error_code")
                                .asText())
                .isEqualTo("GPT_DAILY_BUDGET_EXCEEDED");
        ForecastDeepSeek enabled =
                new ForecastDeepSeek(store, quant, tx, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(
                        enabled.predict(id, store.json.createObjectNode(), "GPT")
                                .path("status")
                                .asText())
                .isEqualTo("SUCCEEDED");
    }

    /** 四个既有DeepSeek入口均不消耗GPT额度，保持其原有领域与频次约束。 */
    @ParameterizedTest
    @ValueSource(strings = {"ETF", "FORWARD", "FUND", "GOLD"})
    void everyLegacyDeepseekEntryIgnoresGptCosts(String domain) {
        Instant cutoff = Instant.parse("2039-01-13T13:00:00Z");
        fee("GPT", "UNCERTAIN", cutoff);
        String source = UUID.randomUUID().toString();
        store.db.update(
                "insert into news_item(id,title,summary,source_url,source_name,published_at,fetched_at,"
                        + "nature,publication_verified,content_sha256) values(?,'隔离新闻','测试','https://example.invalid','TEST',?,?,'REAL',true,?)",
                source,
                Timestamp.from(cutoff.minusSeconds(60)),
                Timestamp.from(cutoff.minusSeconds(30)),
                "a".repeat(64));
        ObjectNode quote =
                quote(
                        switch (domain) {
                            case "FUND" -> "FUND_NAV_RESEARCH";
                            case "GOLD" -> "GOLD_LONG_TERM";
                            default -> domain;
                        });
        quote.putArray("eligible_source_ids").add(source);
        String route =
                "/analysis"
                        + (domain.equals("ETF")
                                ? ""
                                : "/" + domain.toLowerCase(java.util.Locale.ROOT));
        stub(route, quote);
        AnalysisService analysis =
                new AnalysisService(store, quant, tx, BigDecimal.ONE, BigDecimal.ONE);
        JsonNode result =
                switch (domain) {
                    case "ETF" -> analysis.analyze(cutoff);
                    case "FORWARD" ->
                            new ForwardNews(store, quant, analysis, tx, BigDecimal.ONE, true)
                                    .analyze(cutoff, true);
                    case "FUND" ->
                            new FundNews(store, quant, analysis, tx, BigDecimal.ONE, true)
                                    .analyze("460300", cutoff);
                    case "GOLD" ->
                            new GoldNews(store, quant, analysis, tx, BigDecimal.ONE, true)
                                    .analyze(cutoff);
                    default -> throw new IllegalArgumentException("TEST_DOMAIN");
                };
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        verify(quant, times(1)).post(eq(route + "/run"), any());
    }

    /** 隔离执行项保留供应商归属，模拟上线前已经存在的关联而不新增账单字段。 */
    private UUID item(String scheme) {
        UUID run = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into forecast_run(id,business_key,status,request) values(?,?,'RUNNING','{}')",
                run,
                run.toString());
        store.db.update(
                "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,status,input_snapshot) "
                        + "values(?,?,'460300','FUND_NAV_CHANGE',20,?,'RUNNING','{}')",
                id,
                run,
                scheme);
        return id;
    }

    /** 历史非GPT账单无需关联统一执行项；确认费用用实际值，其他状态按预留计算。 */
    private void fee(String scheme, String status, Instant at) {
        UUID id = scheme.equals("DEEPSEEK") ? UUID.randomUUID() : item(scheme);
        store.db.update(
                "insert into api_call_cost(id,business_key,status,reserved_cost,actual_cost,pricing_snapshot,created_at) "
                        + "values(?,?,?,?,1,'{}',?)",
                id,
                id.toString(),
                status,
                status.equals("CONFIRMED") ? new BigDecimal("2") : BigDecimal.ONE,
                Timestamp.from(at));
    }

    /** 测试报价身份随机避免跨测试复用，不包含任何真实提供方配置。 */
    private ObjectNode quote(String domain) {
        String identity = UUID.randomUUID().toString().replace("-", "").repeat(2);
        ObjectNode value = store.json.createObjectNode();
        value.put("provider", "deepseek");
        value.put("domain", domain)
                .put("request_fingerprint", identity)
                .put("input_sha256", identity)
                .put("evidence_snapshot_id", identity)
                .put("enabled", true)
                .put("configured", true)
                .put("reserved_usd", "0.01")
                .put("prompt_version", "TEST")
                .put("schema_version", "TEST")
                .put("evidence_version", "TEST")
                .put("model_version", "TEST")
                .put("model", identity);
        value.putArray("eligible_source_ids").add("TEST");
        value.putObject("pricing");
        return value;
    }

    /** 真实事务费用预留后才返回模拟响应；任何外部HTTP均未发生。 */
    private void stub(String route, ObjectNode quote) {
        when(quant.post(eq(route + "/quote"), any())).thenReturn(quote);
        when(quant.post(eq(route + "/run"), any()))
                .thenReturn(
                        quote.deepCopy()
                                .put("status", "SUCCEEDED")
                                .put("cost_usd", "0.01")
                                .put("billing_uncertain", false)
                                .put("nature", "MOCK")
                                .put("request_model", quote.path("model").asText()));
    }

    /** 供应商路由与生产协议一致，便于断言受限一方完全没有真实调用机会。 */
    private String route(String scheme) {
        return scheme.equals("GPT") ? "/analysis/gpt" : "/analysis/forecast";
    }
}
