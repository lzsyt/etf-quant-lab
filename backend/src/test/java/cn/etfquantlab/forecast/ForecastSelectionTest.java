package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 人工冻结样本只用于选优规则测试，不进入业务预测或真实准确率。 */
class ForecastSelectionTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 真正共同且时间分离的留出验证通过后，才能出现优先参考个体。 */
    @Test
    void selectsOnEarlierWindowsAndVerifiesOnLaterUnseenWindows() {
        List<JsonNode> rows = opportunities(40, false, false);
        JsonNode recommendation = recommend(rows);
        assertThat(recommendation.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(recommendation.path("scheme").asText()).isEqualTo("FUND_LIGHTGBM");
        assertThat(recommendation.path("selection_samples").asInt()).isEqualTo(30);
        assertThat(recommendation.path("validation_samples").asInt()).isEqualTo(10);
        assertThat(recommendation.path("metric").asText()).isEqualTo("MAE");
        assertThat(recommendation.path("window").path("selection_end").asText())
                .isLessThan(recommendation.path("window").path("validation_start").asText());
    }

    /** 后续表现变差时不能偷看留出集后改选另一个模型，也不能照旧发布早期赢家。 */
    @Test
    void validationRejectsEarlierWinnerWithoutReselectingOnHoldout() {
        JsonNode result = recommend(opportunities(40, true, false));
        assertThat(result.path("candidate_scheme").asText()).isEqualTo("FUND_LIGHTGBM");
        assertThat(result.path("status").asText()).isEqualTo("NO_VALIDATED_ADVANTAGE");
        assertThat(result.has("scheme")).isFalse();
    }

    /** 四十条重复或重叠的长周期结果不构成四十个独立成熟机会。 */
    @Test
    void duplicatedAndOverlappingWindowsCannotSatisfyMinimum() {
        List<JsonNode> rows = opportunities(40, false, true);
        rows.addAll(new ArrayList<>(rows));
        JsonNode result = recommend(rows);
        assertThat(result.path("status").asText()).isEqualTo("INSUFFICIENT_SAMPLES");
        assertThat(result.path("nonoverlapping_samples").asInt()).isLessThan(40);
    }

    /** 各个体单独超过门槛但缺失共同机会时，仍然不能选优。 */
    @Test
    void independentTotalsDoNotReplaceSharedOpportunityCount() {
        List<JsonNode> rows = opportunities(45, false, false);
        rows.removeIf(
                row ->
                        "DEEPSEEK".equals(row.path("scheme").asText())
                                && row.path("anchor_date").asText().compareTo("2024-01-21") < 0);
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    /** 相同固定规则可以跨制品滚动积累，展示当前制品但不将其伪装成单文件历史成绩。 */
    @Test
    void stablePolicyCanContinueAcrossModelArtifactRefreshes() {
        List<JsonNode> rows = opportunities(40, false, false);
        ObjectNode latest = (ObjectNode) rows.get(rows.size() - 2).deepCopy();
        latest.put("model_version", "new-unvalidated");
        latest.put("generated_at", "2024-06-01T10:00:00Z");
        latest.put("evaluation_eligible", false);
        rows.add(latest);
        assertThat(recommend(rows).path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(recommend(rows).path("model_version").asText()).isEqualTo("new-unvalidated");
        latest.put("selection_policy", "new-training-or-input-rule");
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    /** 留出开始时尚未公布的净值不能供先前选优，日期到期不等于当时知道答案。 */
    @Test
    void latePublishedActualCannotLeakIntoSelectionBeforeHoldout() {
        List<JsonNode> rows = opportunities(40, false, false);
        rows.forEach(row -> ((ObjectNode) row).put("actual_available_at", "2024-12-31T10:00:00Z"));
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INSUFFICIENT_PIT_EVIDENCE");
    }

    /** 长周期真实端点跨度明确，不能靠短日期测试误以为几个星期就能取得可靠优先。 */
    @Test
    void fundTwentyObservationWindowsNeedRealLongTermCoverage() {
        List<JsonNode> rows = opportunities(40, false, false);
        for (JsonNode row : rows) {
            LocalDate anchor = LocalDate.parse(row.path("anchor_date").asText());
            ((ObjectNode) row).put("actual_target_date", anchor.plusDays(28).toString());
        }
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    /** 研究质量失败的模型可以看误差，但不能获得默认优先推荐。 */
    @Test
    void failedQualityGateCannotWinRecommendation() {
        List<JsonNode> rows = opportunities(40, false, false);
        rows.stream()
                .filter(row -> "FUND_LIGHTGBM".equals(row.path("scheme").asText()))
                .forEach(row -> ((ObjectNode) row).put("model_validation_status", "FAILED"));
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INSUFFICIENT_CANDIDATES");
    }

    /** 概率型使用Brier和简单概率基线，不从点收益伪造概率。 */
    @Test
    void probabilitiesUseBrierAgainstFixedAndHistoricalFrequencyBaselines() {
        List<JsonNode> rows = opportunities(40, false, false);
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) rows.get(index);
            ObjectNode output = (ObjectNode) row.path("output");
            output.remove("point_return");
            boolean up = row.path("actual_return").decimalValue().signum() > 0;
            boolean first = "FUND_LIGHTGBM".equals(row.path("scheme").asText());
            output.put("probability_up", new BigDecimal(first ? (up ? "0.9" : "0.1") : "0.5"));
        }
        JsonNode result = recommend(rows);
        assertThat(result.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("metric").asText()).isEqualTo("BRIER");
    }

    /** 不同输出能力可以同组展示，但没有共同数值能力就不强行比较赢家。 */
    @Test
    void incompatibleCapabilitiesHaveNoRecommendation() {
        List<JsonNode> rows = opportunities(40, false, false);
        rows.stream()
                .filter(row -> "DEEPSEEK".equals(row.path("scheme").asText()))
                .forEach(
                        row -> {
                            ObjectNode output = (ObjectNode) row.path("output");
                            output.remove("point_return");
                            output.put("probability_up", new BigDecimal("0.6"));
                            ((ObjectNode) row).put("output_type", "PROBABILITY");
                        });
        assertThat(ForecastEvaluation.groups(rows)).hasSize(1);
        assertThat(recommend(rows).path("status").asText()).isEqualTo("INCOMPARABLE");
    }

    /** 构造两种独立方案在同一截止和同一目标上的冻结结果，留出段可模拟失效。 */
    private List<JsonNode> opportunities(int count, boolean reverseLater, boolean overlap) {
        List<JsonNode> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            LocalDate anchor = LocalDate.of(2024, 1, 1).plusDays(index * 2L);
            LocalDate target = anchor.plusDays(overlap ? 20 : 1);
            BigDecimal actual = new BigDecimal(index % 2 == 0 ? "0.1" : "-0.1");
            for (String scheme : List.of("FUND_LIGHTGBM", "DEEPSEEK")) {
                ObjectNode row = json.createObjectNode();
                row.put("asset", "460300");
                row.put("target", "FUND_NAV_CHANGE");
                row.put("horizon", 20);
                row.put("mode", "REAL");
                row.put("target_version", "fund-nav-change-20-60-v1");
                row.put("output_type", "RETURN_POINT");
                row.put("role", "PREDICTOR");
                row.put("scheme", scheme);
                row.put("model_version", scheme + "-v1");
                row.put("selection_policy", scheme + "-fixed-training-rules-v1");
                row.put("generated_at", anchor + "T10:00:00Z");
                row.put("data_cutoff", anchor + "T09:00:00Z");
                row.put("actual_available_at", target + "T15:00:00Z");
                row.put("anchor_date", anchor.toString());
                row.put("actual_target_date", target.toString());
                row.put("sample_key", anchor + "|" + target + "|" + anchor + "T09:00:00Z");
                row.put("evaluation_eligible", true);
                row.put("actual_return", actual);
                boolean good = "FUND_LIGHTGBM".equals(scheme);
                if (index >= 30 && reverseLater) {
                    good = !good;
                }
                row.putObject("output").put("point_return", good ? actual : BigDecimal.ZERO);
                rows.add(row);
            }
        }
        return rows;
    }

    /** 从公开统一评价结果读取推荐，避免直接调用私有计算绕开真实分组。 */
    private JsonNode recommend(List<JsonNode> rows) {
        return json.valueToTree(ForecastEvaluation.groups(rows)).path(0).path("recommendation");
    }
}
