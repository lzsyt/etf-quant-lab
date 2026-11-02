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
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
class PredictionTest {
    @Autowired private Store store;
    @Autowired private PredictionService predictions;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private QuantClient quant;

    /** 预测和预算测试只能落到隔离数据库。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 两个并发请求无法创建重复的冻结预测。 */
    @Test
    void concurrentPredictionFreezeKeepsOneOriginal() throws Exception {
        String strategy = "test-" + UUID.randomUUID();
        Map<String, Object> record =
                Map.of(
                        "symbol",
                        "510300",
                        "strategy",
                        strategy,
                        "horizon",
                        5,
                        "signal_date",
                        "2026-10-23",
                        "model_version",
                        "TEST",
                        "direction",
                        "UP",
                        "probability",
                        new BigDecimal("0.6"));
        for (String day :
                List.of("2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30")) {
            store.db.update(
                    "insert into trading_calendar(trade_date,source)"
                            + " values(?::date,'ISOLATED_TEST') on conflict do nothing",
                    day);
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> predictions.freeze(List.of(record), Instant.now()));
            var second = pool.submit(() -> predictions.freeze(List.of(record), Instant.now()));
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from prediction where strategy_id=?",
                                Integer.class,
                                strategy))
                .isEqualTo(1);
    }

    /** 默认零预算即使存在Key也不能调用供应商。 */
    @Test
    void zeroBudgetBlocksBeforeExternalCall() {
        Instant cutoff = seedNews();
        mockQuote();
        var service = new AnalysisService(store, quant, tx, BigDecimal.ZERO, BigDecimal.ZERO);
        var result = service.analyze(cutoff);
        assertThat(result.path("error_code").asText()).isEqualTo("DAILY_BUDGET_EXCEEDED");
        verify(quant, never()).post(eq("/analysis/run"), any());
    }

    /** 同业务键并发时只收费一次，费用在请求发出前已经持久预留。 */
    @Test
    void budgetReservationSurvivesConcurrentRequests() throws Exception {
        Instant cutoff = seedNews();
        mockQuote();
        when(quant.post(eq("/analysis/run"), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            store.db.queryForObject(
                                                    "select count(*) from api_call_cost where status='RESERVED'",
                                                    Integer.class))
                                    .isPositive();
                            return store.decode(
                                    "{\"status\":\"SUCCEEDED\",\"billing_uncertain\":false,\"cost_usd\":\"0.001\","
                                            + "\"input_tokens\":100,\"output_tokens\":50,\"duration_ms\":10}");
                        });
        var service = new AnalysisService(store, quant, tx, new BigDecimal("1"), BigDecimal.ZERO);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.analyze(cutoff));
            var second = pool.submit(() -> service.analyze(cutoff));
            assertThat(first.get().path("id").asText()).isEqualTo(second.get().path("id").asText());
        } finally {
            pool.shutdownNow();
        }
        verify(quant, times(1)).post(eq("/analysis/run"), any());
    }

    /** 测试专用来源，不调用外部新闻；ID随机以免影响其他测试。 */
    private Instant seedNews() {
        Instant now = Instant.now();
        store.db.update(
                "insert into news_item(id,title,summary,source_url,source_name,published_at,fetched_at,"
                        + "nature,publication_verified,content_sha256) values(?,'TEST','TEST',"
                        + "'https://www.pbc.gov.cn/test','TEST',?,?,'REAL',true,?)",
                UUID.randomUUID().toString(),
                Timestamp.from(now.minusSeconds(3600)),
                Timestamp.from(now.minusSeconds(60)),
                "a".repeat(64));
        return now;
    }

    /** 仅模拟报价协议，不存在真实模型Key或消费。 */
    private void mockQuote() {
        when(quant.post(eq("/analysis/quote"), any()))
                .thenReturn(
                        store.json.valueToTree(
                                Map.of(
                                        "enabled",
                                        true,
                                        "configured",
                                        true,
                                        "model",
                                        UUID.randomUUID().toString(),
                                        "model_version",
                                        "TEST",
                                        "prompt_version",
                                        "TEST",
                                        "reserved_usd",
                                        "0.01",
                                        "pricing",
                                        Map.of("source", "TEST"))));
    }
}
