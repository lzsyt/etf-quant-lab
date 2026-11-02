package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.gold.GoldNews;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class GoldNewsTest {
    @Autowired private Store store;
    @Autowired private AnalysisService news;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;
    private final Instant cutoff = Instant.parse("2038-01-13T13:00:00Z");

    /** 隔离库事务回滚，任何提供方响应均由Mockito提供。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        MDC.put("trace_id", UUID.randomUUID().toString());
    }

    /** 测试完成清理线程标记，避免干扰其他ETF用例。 */
    @AfterEach
    void cleanTrace() {
        MDC.remove("trace_id");
    }

    /** 构造仅黄金专属身份；不含真实凭据或真实新闻。 */
    private ObjectNode quote() {
        ObjectNode value = store.json.createObjectNode();
        value.put("domain", "GOLD_LONG_TERM");
        value.put("request_fingerprint", "a".repeat(64));
        value.put("input_sha256", "b".repeat(64));
        value.put("evidence_snapshot_id", "c".repeat(64));
        value.put("prompt_version", "gold-evidence-explanation-v1");
        value.put("schema_version", "gold-evidence-v1");
        value.put("model_version", "test");
        value.put("model", "deepseek-flash");
        value.put("evidence_version", "news-evidence-v1");
        value.put("enabled", true);
        value.put("configured", true);
        value.put("reserved_usd", "0.02");
        value.putArray("eligible_source_ids").add("test-source");
        value.putObject("pricing");
        when(quant.post(eq("/analysis/gold/quote"), any())).thenReturn(value);
        ObjectNode response = value.deepCopy();
        response.put("request_model", "deepseek-flash");
        response.put("status", "SUCCEEDED");
        response.put("nature", "MOCK");
        response.put("cost_usd", "0.01");
        response.put("billing_uncertain", false);
        when(quant.post(eq("/analysis/gold/run"), any())).thenReturn(response);
        return value;
    }

    /** 现有总预算已被ETF占用时，黄金不能获得自己的另一美元额度。 */
    @Test
    void sharedEtfBudgetBlocksGold() {
        quote();
        store.db.update(
                "insert into api_call_cost(id,business_key,status,reserved_cost,created_at,pricing_snapshot)"
                        + " values(?,?,'RESERVED',0.99,?,'{}')",
                UUID.randomUUID(),
                "ETF_TEST",
                Timestamp.from(cutoff));
        JsonNode result =
                new GoldNews(store, quant, news, tx, BigDecimal.ONE, true).analyze(cutoff);
        assertThat(result.path("error_code").asText()).isEqualTo("DEEPSEEK_DAILY_BUDGET_EXCEEDED");
        verify(quant, never()).post(eq("/analysis/gold/run"), any());
    }

    /** 队列恢复跨天仍读取同一任务结果，不重复外呼、不重复扣费。 */
    @Test
    void sameTaskAfterRestartReusesFrozenResult() {
        quote();
        GoldNews service = new GoldNews(store, quant, news, tx, BigDecimal.ONE, true);
        JsonNode first = service.analyze(cutoff);
        JsonNode recovered = service.analyze(cutoff.plusSeconds(86400));
        assertThat(recovered.path("id")).isEqualTo(first.path("id"));
        assertThat(recovered.path("input_snapshot")).isEqualTo(first.path("input_snapshot"));
        verify(quant, times(1)).post(eq("/analysis/gold/run"), any());
    }

    /** 无合格新闻和纯GET均不得调用提供方，空值不变成中性概率。 */
    @Test
    void missingNewsAndReadsNeverCharge() {
        quote().putArray("eligible_source_ids");
        GoldNews service = new GoldNews(store, quant, news, tx, BigDecimal.ONE, true);
        assertThat(service.analyze(cutoff).path("error_code").asText())
                .isEqualTo("NO_QUALIFIED_GOLD_NEWS");
        service.latest();
        service.latest();
        verify(quant, never()).post(eq("/analysis/gold/run"), any());
    }

    /** 黄金入口拒绝ETF报价身份，避免复用历史h1结果或费用关联。 */
    @Test
    void etfDomainCannotEnterGoldLedger() {
        quote().put("domain", "ETF");
        GoldNews service = new GoldNews(store, quant, news, tx, BigDecimal.ONE, true);
        assertThatThrownBy(() -> service.analyze(cutoff))
                .isInstanceOf(IllegalArgumentException.class);
        verify(quant, never()).post(eq("/analysis/gold/run"), any());
    }

    /** 原日终尚未完成时黄金解释让出优先级，不阻塞ETF费用和正常业务。 */
    @Test
    void originalEtfQueueKeepsPriority() {
        quote();
        store.db.update(
                "insert into task_execution(id,business_key,task_type,status,request,updated_at)"
                        + " values(?,?,'FORWARD_EOD','QUEUED','{}',?)",
                UUID.randomUUID(),
                "ETF_GOLD_PRIORITY_TEST",
                Timestamp.from(cutoff));
        JsonNode result =
                new GoldNews(store, quant, news, tx, BigDecimal.ONE, true).analyze(cutoff);
        assertThat(result.path("error_code").asText()).isEqualTo("ETF_PRIORITY_PENDING");
        verify(quant, never()).post(eq("/analysis/gold/run"), any());
    }
}
