package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 独立审查预测公平性；使用冻结记录夹具，不连接数据库或外部模型。 */
class ForecastFairnessTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ForecastService service = new ForecastService(new Store(null, json));

    /** 相同绝对截止时刻的不同表示必须匹配，不因UTC与上海时区文本不同失去样本。 */
    @Test
    void equivalentCutoffTimeZonesRemainComparable() {
        JsonNode lightgbm = forward("LGBM_BASE", "2025-01-02T08:00:00Z", "input-a");
        JsonNode xgboost = forward("XGB_BASE", "2025-01-02T16:00:00+08:00", "input-a");
        assertThat(lightgbm.path("evaluation_eligible").asBoolean()).isTrue();
        assertThat(xgboost.path("evaluation_eligible").asBoolean()).isTrue();
        assertThat(lightgbm.path("sample_key")).isEqualTo(xgboost.path("sample_key"));
        assertThat(
                        group(lightgbm, xgboost)
                                .path("comparisons")
                                .path(0)
                                .path("matched_samples")
                                .asInt())
                .isEqualTo(1);
    }

    /** 截止时点必须相同；不同算法使用各自特征快照不妨碍同机会公平比较。 */
    @Test
    void differentCutoffDoesNotMatchButDifferentModelInputsCanMatch() {
        JsonNode lightgbm = forward("LGBM_BASE", "2025-01-02T08:00:00Z", "input-a");
        JsonNode later = forward("XGB_BASE", "2025-01-02T08:00:00.001Z", "input-a");
        assertThat(group(lightgbm, later).path("comparisons").isEmpty()).isTrue();
        JsonNode independent = forward("XGB_BASE", "2025-01-02T08:00:00Z", "input-b");
        assertThat(group(lightgbm, independent).path("comparisons")).hasSize(1);
    }

    /** 同秒毫秒级晚到也属于未来输入，不能按ISO字符串顺序判断合法性。 */
    @Test
    void inputOneMillisecondAfterGenerationIsNotEligible() {
        ObjectNode row = fundRow("2025-01-02T08:00:00.001Z", "2025-01-02T08:00:00Z");
        assertThat(fund(row).path("evaluation_eligible").asBoolean()).isFalse();
    }

    /** 已知冻结目标的旧评价不一定重复保存实际日期，合法终点应按原目标回查。 */
    @Test
    void fundEvaluationCanUseItsKnownFrozenTargetDate() {
        ObjectNode row = fundRow("2025-01-02T08:00:00Z", "2025-01-02T08:01:00Z");
        ((ObjectNode) row.path("actual")).remove("actual_target_date");
        ((ObjectNode) row.path("record").path("details").path("points").path(0))
                .put("target_date", "2025-02-04");
        JsonNode record = fund(row);
        assertThat(record.path("actual_target_date").asText()).isEqualTo("2025-02-04");
        assertThat(record.path("evaluation_eligible").asBoolean()).isTrue();
    }

    /** 未知未来日期不能用预计日代替；只有实际净值观察到期后才能评价。 */
    @Test
    void missingActualTargetAndUnmaturedForecastStayExcluded() {
        ObjectNode row = fundRow("2025-01-02T08:00:00Z", "2025-01-02T08:01:00Z");
        ObjectNode actual = (ObjectNode) row.path("actual");
        actual.remove("actual_target_date");
        actual.put("evaluation_status", "WAITING_OBSERVATIONS");
        JsonNode record = fund(row);
        assertThat(record.path("evaluation_eligible").asBoolean()).isFalse();
        assertThat(group(record).path("individuals").path(0).path("evaluated_samples").asInt())
                .isZero();
        assertThat(group(record).path("individuals").path(0).path("metrics").path("mae").isNull())
                .isTrue();
    }

    /** 历史已到期结果存在，也不能让后来生成的预测进入真实前瞻成绩。 */
    @Test
    void forecastsGeneratedAfterActualTargetAreExcluded() {
        ObjectNode row = fundRow("2025-02-04T08:00:00Z", "2025-02-04T08:01:00Z");
        assertThat(fund(row).path("evaluation_eligible").asBoolean()).isFalse();
    }

    /** 记录缺少可追溯模型版本时不具备独立方案身份，不能进入评价。 */
    @Test
    void blankModelIdentityCannotEnterEvaluation() {
        ObjectNode row = fundRow("2025-01-02T08:00:00Z", "2025-01-02T08:01:00Z");
        ((ObjectNode) row.path("record").path("details")).put("model_version_id", "");
        assertThat(fund(row).path("evaluation_eligible").asBoolean()).isFalse();
    }

    /** 模型轮换不能令历史所有版本求交后归零；只比较同机会的不同算法版本。 */
    @Test
    void rotatingModelVersionsKeepTheirOwnMatchedPairs() {
        JsonNode result =
                group(
                        sample("LGBM_BASE", "lgbm-old", "old-opportunity", "0.8", "0.1"),
                        sample("XGB_BASE", "xgb-old", "old-opportunity", "0.7", "0.1"),
                        sample("LGBM_BASE", "lgbm-new", "new-opportunity", "0.6", "-0.1"),
                        sample("XGB_BASE", "xgb-new", "new-opportunity", "0.4", "-0.1"));
        assertThat(result.path("comparisons")).hasSize(2);
        for (JsonNode comparison : result.path("comparisons")) {
            assertThat(comparison.path("matched_samples").asInt()).isEqualTo(1);
            assertThat(comparison.path("left").path("scheme").asText())
                    .isNotEqualTo(comparison.path("right").path("scheme").asText());
            assertThat(comparison.path("left").path("metrics").path("brier").isNumber()).isTrue();
        }
    }

    /** 同算法升级前后是版本对照，不作为两个独立预测个体产生赢家比较。 */
    @Test
    void versionsOfSameSchemeAreNotIndependentCompetitors() {
        JsonNode result =
                group(
                        sample("LGBM_BASE", "old", "one-opportunity", "0.8", "0.1"),
                        sample("LGBM_BASE", "new", "one-opportunity", "0.9", "0.1"));
        assertThat(result.path("individuals")).hasSize(2);
        assertThat(result.path("comparisons").isEmpty()).isTrue();
        assertThat(result.path("recommendation").path("status").asText())
                .isEqualTo("INSUFFICIENT_CANDIDATES");
    }

    /** 同日期但数据修订后的实际答案不同，不能使用不相等的答案计算配对胜负。 */
    @Test
    void differentActualRevisionsDoNotFormMatchedComparisons() {
        JsonNode result =
                group(
                        sample("LGBM_BASE", "a", "one-opportunity", "0.8", "0.1"),
                        sample("XGB_BASE", "b", "one-opportunity", "0.8", "0.2"));
        assertThat(result.path("comparisons").isEmpty()).isTrue();
    }

    /** 同机会重复冻结只计一次，最新记录按实际时刻选取，保留毫秒精度。 */
    @Test
    void duplicateSamplesUseLatestInstantWithoutInflatingCount() {
        ObjectNode older = sample("LGBM_BASE", "one", "one-opportunity", "0.2", "0.1");
        ObjectNode newer = sample("LGBM_BASE", "one", "one-opportunity", "0.8", "0.1");
        older.put("generated_at", "2025-01-02T08:00:01Z");
        newer.put("generated_at", "2025-01-02T08:00:01.001Z");
        JsonNode individual = group(older, newer).path("individuals").path(0);
        assertThat(individual.path("evaluated_samples").asInt()).isEqualTo(1);
        assertThat(individual.path("duplicate_samples").asInt()).isEqualTo(1);
        assertThat(individual.path("metrics").path("brier").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.04"));
    }

    /** 无概率、非法概率和越界概率不转换为0，也不计作命中或失败。 */
    @Test
    void missingOrInvalidProbabilityDoesNotBecomeZero() {
        for (String probability : List.of("NaN", "1.01", "-0.01", "")) {
            ObjectNode row = sample("LGBM_BASE", "one", "one-opportunity", probability, "0.1");
            JsonNode individual = group(row).path("individuals").path(0);
            assertThat(individual.path("evaluated_samples").asInt()).isZero();
            assertThat(individual.path("metrics").path("brier").isNull()).isTrue();
        }
        ObjectNode row = sample("LGBM_BASE", "one", "one-opportunity", "0", "0.1");
        ((ObjectNode) row.path("output")).putNull("probability_up");
        assertThat(group(row).path("individuals").path(0).path("evaluated_samples").asInt())
                .isZero();
    }

    /** 不同目标、期限、模式和资产即使样本键相同也必须分组，不能合并概率成绩。 */
    @Test
    void protocolAssetHorizonAndModeRemainSeparateGroups() {
        ObjectNode original = sample("LGBM_BASE", "one", "one-opportunity", "0.8", "0.1");
        for (Map.Entry<String, String> difference :
                Map.of(
                                "target",
                                "ETF_LEGACY_CLOSE",
                                "asset",
                                "510500",
                                "horizon",
                                "5",
                                "mode",
                                "DEMO",
                                "target_version",
                                "OLD_LABEL")
                        .entrySet()) {
            ObjectNode other = original.deepCopy();
            other.put(difference.getKey(), difference.getValue());
            assertThat(ForecastEvaluation.groups(List.of(original, other))).hasSize(2);
        }
    }

    /** 策略分数即便误带概率字段，也不能冒充独立模型参与概率比较。 */
    @Test
    void strategyExperimentIsNeverScoredAsIndependentProbability() {
        ObjectNode strategy = sample("FUSION_EXPERIMENT", "one", "one-opportunity", "0.8", "0.1");
        strategy.put("role", "STRATEGY_EXPERIMENT");
        strategy.put("output_type", "STRATEGY_SCORE");
        assertThat(group(strategy).path("individuals").path(0).path("evaluated_samples").asInt())
                .isZero();
    }

    /** 用真实字段形状构造前瞻冻结记录，检验投影使用模型的输入截止与快照身份。 */
    private JsonNode forward(String strategy, String cutoff, String input) {
        ObjectNode row = json.createObjectNode();
        ObjectNode record = row.putObject("record");
        record.put("id", strategy);
        record.put("experiment_id", "experiment-one");
        record.put("symbol", "510300");
        record.put("signal_date", "2025-01-02");
        record.put("entry_date", "2025-01-03");
        record.put("evaluation_date", "2025-01-06");
        record.put("generated_at", "2025-01-02T08:01:00Z");
        ObjectNode details = record.putObject("details");
        details.put("strategy", strategy);
        details.put("horizon", 1);
        details.put("label_version", "OPEN_TO_OPEN_H1_V1");
        details.put("protocol_version", "OPEN_NET_REBALANCE_V1");
        details.put("target_semantics", "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP");
        ObjectNode ml = details.putObject("ml");
        ml.put("model_id", strategy + "-v1");
        ml.put("status", "SUCCEEDED");
        ml.put("nature", "REAL");
        ml.put("symbol", "510300");
        ml.put("horizon", 1);
        ml.put("algorithm", "LGBM_BASE".equals(strategy) ? "lightgbm" : "xgboost");
        ml.put("model_role", "FORWARD_PRODUCTION");
        ml.put("feature_version", "ohlcv-v2");
        ml.put("label_version", "OPEN_TO_OPEN_H1_V1");
        ml.put("protocol_version", "OPEN_NET_REBALANCE_V1");
        ml.put("target_semantics", "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP");
        ml.put("price_basis", "RAW");
        ml.put("probability", new BigDecimal("0.8"));
        ml.put("raw_probability", new BigDecimal("0.75"));
        ml.put("input_cutoff_at", cutoff);
        ml.put("generated_at", "2025-01-02T08:00:59Z");
        ml.put("input_sha256", input);
        ObjectNode actual = row.putObject("actual");
        actual.put("status", "EVALUATED");
        actual.put("actual_return", "0.1");
        actual.put("label_version", "OPEN_TO_OPEN_H1_V1");
        actual.put("target_semantics", "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP");
        return service.normalize(
                "forward", row, service.selection("510300", "ETF_FORWARD_OPEN", 1, "REAL"));
    }

    /** 基金夹具只保存可信原生合同，目标日期为空时模拟按后续净值观察成熟。 */
    private ObjectNode fundRow(String cutoff, String generated) {
        ObjectNode row = json.createObjectNode();
        ObjectNode record = row.putObject("record");
        record.put("id", "fund-one");
        record.put("fund_code", "460300");
        record.put("horizon", 20);
        record.put("generated_at", generated);
        ObjectNode details = record.putObject("details");
        details.put("fund_code", "460300");
        details.put("nature", "REAL");
        details.put("capability", "RETURN_POINT");
        details.put("target_version", "fund-nav-change-20-60-v1");
        details.put("horizon_unit", "FUND_NAV_OBSERVATIONS");
        details.put("input_cutoff_at", cutoff);
        details.put("anchor_date", "2025-01-02");
        details.put("anchor_value", "1.0");
        details.put("model_version_id", "fund-model-one");
        details.put("data_snapshot_id", "fund-input-one");
        ObjectNode point = details.putArray("points").addObject();
        point.put("horizon", 20);
        point.putNull("target_date");
        point.put("point_return", "0.08");
        ObjectNode actual = row.putObject("actual");
        actual.put("evaluation_status", "EVALUATED");
        actual.put("actual_target_date", "2025-02-04");
        actual.put("actual_return", "0.10");
        return row;
    }

    /** 调用实际基金归一化分支，避免只靠人工设置eligible掩盖时点校验错误。 */
    private JsonNode fund(ObjectNode row) {
        return service.normalize(
                "fund", row, service.selection("460300", "FUND_NAV_CHANGE", 20, "REAL"));
    }

    /** 评价夹具明确所有分组维度，便于独立构造版本轮换及坏样本。 */
    private ObjectNode sample(
            String scheme, String version, String key, String probability, String actual) {
        ObjectNode result = json.createObjectNode();
        result.put("asset", "510300");
        result.put("target", "ETF_FORWARD_OPEN");
        result.put("horizon", 1);
        result.put("mode", "REAL");
        result.put("target_version", "OPEN_TO_OPEN_H1_V1/OPEN_NET_REBALANCE_V1");
        result.put("output_type", "PROBABILITY");
        result.put("scheme", scheme);
        result.put("model_version", version);
        result.put("sample_key", key);
        result.put("evaluation_eligible", true);
        result.put("role", "PREDICTOR");
        result.put("actual_return", actual);
        result.put("generated_at", "2025-01-02T08:01:00Z");
        result.putObject("output").put("probability_up", probability);
        return result;
    }

    /** 将纯计算返回值转成JSON便于核对面向前端的数值和空状态合同。 */
    private JsonNode group(JsonNode... records) {
        return json.valueToTree(ForecastEvaluation.groups(List.of(records))).path(0);
    }
}
