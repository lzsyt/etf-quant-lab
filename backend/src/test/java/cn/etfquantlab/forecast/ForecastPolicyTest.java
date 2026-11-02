package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ForecastPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 默认档位保留旧方案身份；不同思考强度不能混用历史成绩。 */
    @Test
    void reasoningEffortSeparatesPoliciesWithoutInvalidatingLegacyDefaults() {
        for (String scheme : java.util.List.of("GPT", "DEEPSEEK")) {
            ObjectNode response =
                    json.createObjectNode()
                            .put("model_version", "model")
                            .put("prompt_version", "prompt")
                            .put("schema_version", "schema");
            ObjectNode snapshot = snapshot(scheme);
            String original = ForecastPolicy.identity(snapshot, response, scheme);
            ObjectNode selection =
                    response.putObject("model_settings")
                            .put("model", "model")
                            .put("reasoning_effort", scheme.equals("GPT") ? "low" : "none");
            assertThat(ForecastPolicy.identity(snapshot, response, scheme)).isEqualTo(original);
            selection.put("reasoning_effort", "high");
            assertThat(ForecastPolicy.identity(snapshot, response, scheme)).isNotEqualTo(original);
        }
    }

    /** 制品滚动重训保留同方案，代码或验证规则变化必须重新积累方案证据。 */
    @Test
    void fundArtifactsSharePolicyOnlyWithKnownUnchangedRules() {
        ObjectNode snapshot = snapshot("FUND_LIGHTGBM");
        ObjectNode model =
                (ObjectNode) snapshot.path("quant_requests").path("FUND_LIGHTGBM").path("model");
        model.put("feature_version", "fund-features-v1");
        model.put("model_family", "FUND_NAV_LGBM_REGRESSOR");
        model.put("target_version", "fund-nav-change-20-60-v1");
        model.put("selection_policy", "VALIDATION_MAE_BEATS_ZERO_AND_TRAIN_MEAN_V1");
        model.put("code_version", "a123456");
        String first = ForecastPolicy.identity(snapshot, json.createObjectNode(), "FUND_LIGHTGBM");
        assertThat(first).startsWith("ROLLING_V1_");
        ObjectNode request = (ObjectNode) snapshot.path("quant_requests").path("FUND_LIGHTGBM");
        request.remove("model");
        request.putObject("payload").putArray("models").add(model);
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "FUND_LIGHTGBM"))
                .isEqualTo(first);
        model.put("id", "new-artifact");
        model.put("fit_end_at", "2026-10-29T00:00:00Z");
        model.put("training_cutoff", "2026-10-28");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "FUND_LIGHTGBM"))
                .isEqualTo(first);
        model.put("code_version", "b123456");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "FUND_LIGHTGBM"))
                .isNotEqualTo(first);
        model.put("code_version", "UNKNOWN");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "FUND_LIGHTGBM"))
                .isEmpty();
    }

    /** 黄金登记有metadata层；源码和校准协议都能分隔规则，不随制品ID变化。 */
    @Test
    void goldUsesFrozenSourceAndCalibrationProtocol() {
        ObjectNode snapshot = snapshot("GOLD_LIGHTGBM");
        ObjectNode model =
                (ObjectNode) snapshot.path("quant_requests").path("GOLD_LIGHTGBM").path("model");
        ObjectNode metadata = model.putObject("metadata");
        metadata.put("feature_version", "gold-v1");
        metadata.put("algorithm", "LightGBM_small_fixed");
        metadata.put("training_version", "expanding-v1");
        metadata.put("label_version", "SGE_CLOSE_RETURN_63_126_V1");
        metadata.put("calibration_version", "platt-residual-v1");
        metadata.putObject("source_code").put("sha256", "a".repeat(64));
        String first = ForecastPolicy.identity(snapshot, json.createObjectNode(), "GOLD_LIGHTGBM");
        assertThat(first).isNotEmpty();
        model.put("model_id", "different-trained-artifact");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "GOLD_LIGHTGBM"))
                .isEqualTo(first);
        metadata.put("calibration_version", "platt-residual-v2");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "GOLD_LIGHTGBM"))
                .isNotEqualTo(first);
        metadata.remove("source_code");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "GOLD_LIGHTGBM"))
                .isEmpty();
    }

    /** 原先声明的校准选择可随训练数据改变，选择规则和候选集合改变则是新方案。 */
    @Test
    void etfDataDrivenCalibrationChoiceDoesNotChangeFixedSelectionRules() {
        ObjectNode snapshot = snapshot("LGBM_BASE");
        ObjectNode model =
                (ObjectNode) snapshot.path("quant_requests").path("LGBM_BASE").path("model");
        model.put("feature_version", "ohlcv-v2");
        model.put("algorithm", "lightgbm");
        model.put("training_version", "open-target-calibration-candidates-v1");
        model.putObject("source_code_sha256").put("forward_models.py", "a".repeat(64));
        ObjectNode rules = model.putObject("hyperparameters");
        rules.putArray("calibration_candidates").add("RAW").add("PLATT_20");
        rules.put("selection_rule", "VALIDATION_BRIER_THEN_LOG_LOSS_THEN_PREDECLARED_ORDER");
        rules.put("selected_calibration", "RAW");
        rules.put("production_window", 504);
        String first = ForecastPolicy.identity(snapshot, json.createObjectNode(), "LGBM_BASE");
        assertThat(first).isNotEmpty();
        rules.put("selected_calibration", "PLATT_20");
        model.put("model_parameter_version", "different-choice-dependent-hash");
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "LGBM_BASE"))
                .isEqualTo(first);
        rules.put("production_window", 252);
        assertThat(ForecastPolicy.identity(snapshot, json.createObjectNode(), "LGBM_BASE"))
                .isNotEqualTo(first);
    }

    /** DeepSeek提示、模型及结构版本都属于预测个体身份，输入快照不属于方案版本。 */
    @Test
    void deepSeekVersionsSeparateIndependentPolicies() {
        ObjectNode snapshot = snapshot("DEEPSEEK");
        ObjectNode response = json.createObjectNode();
        response.put("model_version", "fixed-deepseek-version");
        response.put("prompt_version", "independent-forecast-v1");
        response.put("schema_version", "independent-forecast-output-v1");
        String first = ForecastPolicy.identity(snapshot, response, "DEEPSEEK");
        assertThat(first).isNotEmpty();
        response.put("input_sha256", "new-data");
        response.put("generated_at", "2026-10-29T00:00:00Z");
        assertThat(ForecastPolicy.identity(snapshot, response, "DEEPSEEK")).isEqualTo(first);
        response.put("prompt_version", "independent-forecast-v2");
        assertThat(ForecastPolicy.identity(snapshot, response, "DEEPSEEK")).isNotEqualTo(first);
        response.remove("schema_version");
        assertThat(ForecastPolicy.identity(snapshot, response, "DEEPSEEK")).isEmpty();
    }

    /** XGBoost按同一领域合同形成可追溯规则身份，并与LightGBM明确分开。 */
    @Test
    void xgboostNativePoliciesRemainDistinctAndTraceable() {
        for (String scheme : java.util.List.of("FUND_XGBOOST", "GOLD_XGBOOST")) {
            ObjectNode frozen = snapshot(scheme);
            ObjectNode model =
                    (ObjectNode) frozen.path("quant_requests").path(scheme).path("model");
            model.put("feature_version", "native-features-v1");
            if (scheme.startsWith("FUND")) {
                model.put("model_family", "FUND_NAV_XGB_REGRESSOR");
                model.put("selection_policy", "VALIDATION_MAE_BEATS_ZERO_AND_TRAIN_MEAN_V1");
                model.put("code_version", "xgboost-completion-v1");
                model.put("target_version", "fund-nav-change-20-60-v1");
            } else {
                model.put("algorithm", "XGBoost_small_fixed");
                model.put("training_version", "gold-expanding-3fold-final504-v1");
                model.put("label_version", "SGE_CLOSE_RETURN_63_126_V1");
                model.put("calibration_version", "gold-purged-platt-residual-v1");
                model.putObject("source_code").put("sha256", "b".repeat(64));
            }
            String xgb = ForecastPolicy.identity(frozen, json.createObjectNode(), scheme);
            assertThat(xgb).startsWith("ROLLING_V1_");
            String lgbScheme = scheme.replace("XGBOOST", "LIGHTGBM");
            ((ObjectNode) frozen.path("quant_requests"))
                    .set(lgbScheme, frozen.path("quant_requests").path(scheme));
            assertThat(ForecastPolicy.identity(frozen, json.createObjectNode(), lgbScheme))
                    .isNotEmpty()
                    .isNotEqualTo(xgb);
        }
    }

    /** 稳定输入骨架不含工件路径和真实数据库对象。 */
    private ObjectNode snapshot(String scheme) {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("context").put("target_version", "NATIVE_TARGET_V1");
        snapshot.putObject("quant_requests").putObject(scheme).putObject("model");
        return snapshot;
    }
}
