package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ForecastServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ForecastService service = new ForecastService(new Store(db, json));

    /** 资产目录与组合校验是纯读取，不因访问页面触发数据库或模型任务。 */
    @Test
    void catalogPreservesNativeCapabilitiesAndRejectsCrossDomainCombinations() {
        JsonNode catalog = json.valueToTree(service.assets());
        assertThat(catalog.path("assets")).hasSize(7);
        assertThat(catalog.path("participants").path(1).path("role").asText())
                .isEqualTo("PREDICTOR");
        assertThat(catalog.path("participants").path(2).path("status").asText())
                .isEqualTo("DISABLED");
        assertThat(catalog.path("participants").path(3).path("role").asText()).isEqualTo("REVIEW");
        assertThatThrownBy(() -> service.selection("460300", "ETF_FORWARD_OPEN", 1, "REAL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.selection("007169", "FUND_NAV_CHANGE", 20, "DEMO"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.selection("510300", "ETF_FORWARD_OPEN", 5, "REAL"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }

    /** GPT开启后仍只声明独立预测可用，旧分歧角色始终停用。 */
    @Test
    void enabledGptDoesNotEnableDisagreementRole() {
        ReflectionTestUtils.setField(service, "gptEnabled", true);
        JsonNode participants = json.valueToTree(service.assets()).path("participants");
        assertThat(participants.path(2).path("status").asText()).isEqualTo("INDEPENDENT_FORECAST");
        assertThat(participants.path(3).path("status").asText()).isEqualTo("DISABLED");
        verifyNoInteractions(db);
    }

    /** 黄金区间为人民币价格，实际到期日取实际结果且缺失概率保持未知。 */
    @Test
    void goldKeepsPriceIntervalActualMaturityAndMissingProbability() throws Exception {
        JsonNode row =
                json.readTree(
                        """
                {"record":{"id":"gold-id","model_id":"gold-model","reference_date":"2024-01-02",
                  "generated_at":"2024-01-02T12:00:00Z","details":{
                  "data_cutoff":"2024-01-02T12:00:00Z","label_version":"SGE_CLOSE_RETURN_63_126_V1",
                  "target_date":"2024-04-01","median_return":"0.02","probability_up":null,
                  "interval_lower":"400.00","interval_upper":"450.00",
                  "input_snapshot":{"observations_sha256":"frozen-prices"},
                  "quality":{"status":"EXPERIMENTAL_NO_STABLE_ADVANTAGE"}}},
                 "actual":{"status":"MATURED","actual_target_date":"2024-04-09",
                  "actual_return":"0.03","target_price":"430.00"}}
                """);
        JsonNode record =
                service.normalize(
                        "gold",
                        row,
                        service.selection("Au99.99", "GOLD_BENCHMARK_RETURN", 63, "REAL"));
        assertThat(record.path("target_date").asText()).isEqualTo("2024-04-01");
        assertThat(record.path("actual_target_date").asText()).isEqualTo("2024-04-09");
        assertThat(record.path("output").path("probability_up").isNull()).isTrue();
        assertThat(record.path("output").path("interval_unit").asText()).isEqualTo("CNY_PER_GRAM");
        assertThat(record.path("evaluation_eligible").asBoolean()).isTrue();
        assertThat(record.path("model_validation_status").asText())
                .isEqualTo("EXPERIMENTAL_NO_STABLE_ADVANTAGE");
        JsonNode metrics =
                json.valueToTree(ForecastEvaluation.groups(List.of(record)))
                        .path(0)
                        .path("individuals")
                        .path(0)
                        .path("metrics");
        assertThat(metrics.path("mae").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(metrics.path("brier").isNull()).isTrue();
        assertThat(metrics.path("interval_coverage").decimalValue()).isEqualByComparingTo("1");
    }

    /** 两个原生领域新增XGBoost记录必须单独归类，不能混入旧LightGBM成绩。 */
    @Test
    void nativeXgboostPredictionsKeepTheirOwnScheme() {
        for (String source : List.of("fund", "gold")) {
            ObjectNode row = json.createObjectNode();
            ObjectNode record = row.putObject("record");
            record.put("id", "test-native-xgb");
            record.putObject("details").put("algorithm", "xgboost");
            ForecastService.Selection selected =
                    "fund".equals(source)
                            ? service.selection("460300", "FUND_NAV_CHANGE", 20, "REAL")
                            : service.selection("Au99.99", "GOLD_BENCHMARK_RETURN", 63, "REAL");
            assertThat(service.normalize(source, row, selected).path("scheme").asText())
                    .isEqualTo("fund".equals(source) ? "FUND_XGBOOST" : "GOLD_XGBOOST");
            ((ObjectNode) record.path("details")).remove("algorithm");
            assertThat(service.normalize(source, row, selected).path("scheme").asText())
                    .isEqualTo("fund".equals(source) ? "FUND_LIGHTGBM" : "GOLD_LIGHTGBM");
        }
    }

    /** 旧LLM方向来自情绪，即使带有实际收益也不冒充独立数值预测。 */
    @Test
    void legacySentimentRemainsExplanationOutsideProbabilityRankings() throws Exception {
        JsonNode row =
                json.readTree(
                        """
                {"record":{"id":"old-id","strategy_id":"llm","model_version":"deepseek-chat",
                  "created_at":"2024-01-02T12:00:00Z","input_cutoff":"2024-01-02T12:00:00Z",
                  "signal_date":"2024-01-02","target_date":"2024-01-03","probability":0.8,
                  "details":{"price_basis":"RAW","evidence_version":"v1.1","input_snapshot_id":"old-input","raw_score":0.4}},
                 "actual":{"actual_return":0.03,"target_date":"2024-01-03","price_basis":"RAW",
                  "details":{"timely_before_next_open":true}}}
                """);
        JsonNode record =
                service.normalize(
                        "legacy", row, service.selection("510300", "ETF_LEGACY_CLOSE", 1, "REAL"));
        assertThat(record.path("role").asText()).isEqualTo("EXPLANATION");
        assertThat(record.path("evaluation").path("status").asText()).isEqualTo("EVALUATED");
        assertThat(record.path("output").path("probability_up").isNull()).isTrue();
        assertThat(record.path("output").path("score").decimalValue()).isEqualByComparingTo("0.4");
        JsonNode individual =
                json.valueToTree(ForecastEvaluation.groups(List.of(record)))
                        .path(0)
                        .path("individuals")
                        .path(0);
        assertThat(individual.path("evaluated_samples").asInt()).isZero();
        assertThat(individual.path("metrics").path("direction_accuracy").isNull()).isTrue();
    }

    /** 已知日期基金评价缺少actual_target_date时使用原冻结目标日，验证失败仍显示研究误差。 */
    @Test
    void fundResearchFailureIsVisibleAndScoresOnlyLegalMaturity() throws Exception {
        ObjectNode row =
                (ObjectNode)
                        json.readTree(
                                """
                {"record":{"id":"fund-id","generated_at":"2024-01-02T12:00:00Z","details":{
                  "model_version_id":"fund-model","target_version":"fund-nav-change-20-60-v1",
                  "horizon_unit":"FUND_NAV_OBSERVATIONS","capability":"RETURN_POINT",
                  "input_cutoff_at":"2024-01-02T12:00:00Z","anchor_date":"2024-01-02",
                  "data_snapshot_id":"fund-input","validation_status":"FAILED",
                  "points":[{"horizon":20,"target_date":"2024-02-01","point_return":"0"}]}},
                 "actual":{"evaluation_status":"EVALUATED","actual_return":"0.01"}}
                """);
        var selection = service.selection("460300", "FUND_NAV_CHANGE", 20, "REAL");
        JsonNode record = service.normalize("fund", row, selection);
        assertThat(record.path("evaluation_eligible").asBoolean()).isTrue();
        assertThat(record.path("model_validation_status").asText()).isEqualTo("FAILED");
        assertThat(record.path("actual_target_date").asText()).isEqualTo("2024-02-01");
        ((ObjectNode) row.path("record")).put("generated_at", "2024-02-02T12:00:00Z");
        assertThat(
                        service.normalize("fund", row, selection)
                                .path("evaluation_eligible")
                                .asBoolean())
                .isFalse();
        verifyNoInteractions(db);
    }
}
