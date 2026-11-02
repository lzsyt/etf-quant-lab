package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class NewsSavedAnalysisReplayTest {
    /** 显式提供已导出分析行时纯内存重放；默认跳过，不连接数据库或调用任何模型。 */
    @Test
    void savedAnalysisIsConsumableWithoutGeneratingDecisions() throws Exception {
        String fixture = System.getProperty("etf.news-replay-file", "");
        assumeTrue(!fixture.isBlank(), "未提供真实分析文件，默认不执行可选消费重放");
        ObjectMapper json = new ObjectMapper();
        JsonNode analysis = json.readTree(Files.readString(Path.of(fixture)));
        assertThat(analysis.path("id").asText()).isNotBlank();
        assertThat(analysis.path("status").asText()).isIn("SUCCEEDED", "FAILED", "MISSING");
        Instant cutoff = Instant.parse(analysis.path("input_cutoff").asText());
        if ("SUCCEEDED".equals(analysis.path("status").asText())) {
            assertThat(analysis.path("result").path("nature").asText()).isEqualTo("REAL");
            assertThat(analysis.path("result").path("quote_identity_verified").asBoolean())
                    .isTrue();
        }
        JdbcTemplate database = mock(JdbcTemplate.class);
        Store store = new Store(database, json);
        ForwardNews news = new ForwardNews(store, null, null, null, BigDecimal.ZERO, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String symbol : ForwardRules.SYMBOLS) {
            JsonNode item = news.item(analysis, symbol, cutoff);
            JsonNode ml = testProbability(json, symbol);
            Map<String, Object> decision =
                    ForwardRules.decide("FUSION_EXPERIMENT", symbol, ml, item);
            if (Boolean.TRUE.equals(decision.get("news_available"))) {
                assertThat(item.path("status").asText()).isEqualTo("AVAILABLE");
                BigDecimal expected =
                        new BigDecimal("0.14")
                                .add(
                                        new BigDecimal("0.3")
                                                .multiply(
                                                        item.path("sentiment_score")
                                                                .decimalValue()));
                assertThat((BigDecimal) decision.get("score")).isEqualByComparingTo(expected);
                assertThat(decision.get("effective_mode")).isEqualTo("FUSION");
                if ("high".equals(item.path("risk_level").asText())) {
                    assertThat(decision.get("reason_code")).isEqualTo("NEWS_RISK_VETO");
                    assertThat(decision.get("eligible")).isEqualTo(false);
                }
            } else {
                assertThat(decision.get("effective_mode")).isEqualTo("ML_FALLBACK");
                assertThat(decision.get("fallback_reason")).isNotNull();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("analysis_item_status", item.path("status").asText());
            row.put("effective_mode", decision.get("effective_mode"));
            row.put("reason_code", decision.get("reason_code"));
            row.put("fallback_reason", decision.get("fallback_reason"));
            row.put("score", decision.get("score"));
            rows.add(row);
        }
        verifyNoInteractions(database);
        System.out.println(
                json.writeValueAsString(
                        Map.of(
                                "mode",
                                "SAVED_ANALYSIS_IN_MEMORY_REPLAY",
                                "analysis_id",
                                analysis.path("id").asText(),
                                "probability_source",
                                "TEST_ONLY_FIXED_P_0.6",
                                "decisions_generated",
                                false,
                                "rows",
                                rows)));
    }

    /** 仅给纯函数提供固定测试概率及其必要身份，不保存也不冒充真实模型预测。 */
    private JsonNode testProbability(ObjectMapper json, String symbol) {
        ObjectNode ml = json.createObjectNode();
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
}
