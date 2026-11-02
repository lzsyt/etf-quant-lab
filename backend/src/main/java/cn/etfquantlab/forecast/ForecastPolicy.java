package cn.etfquantlab.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 固定预测方案的身份：允许同规则滚动训练，不把制品ID和训练时刻混入方案版本。 */
final class ForecastPolicy {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** 纯函数工具不读取数据库、工件或外部配置。 */
    private ForecastPolicy() {}

    /** 只使用本次冻结模型元数据；关键规则不可追溯时返回空，禁止借其他版本的成绩推荐。 */
    static String identity(JsonNode snapshot, JsonNode response, String scheme) {
        ObjectNode policy = JSON.createObjectNode();
        policy.put("identity_version", "ROLLING_FORECAST_POLICY_V1");
        policy.put("scheme", scheme);
        if (!copyRequired(policy, snapshot.path("context"), "target_version")) return "";
        if (List.of("DEEPSEEK", "GPT").contains(scheme)) {
            if (!copyRequired(
                    policy, response, "model_version", "prompt_version", "schema_version")) {
                return "";
            }
            copyOptional(policy, response, "request_model", "evidence_version");
            String legacyEffort = "GPT".equals(scheme) ? "low" : "none";
            String effort =
                    response.path("model_settings").path("reasoning_effort").asText(legacyEffort);
            // 保留旧默认方案身份；不同思考强度不能借用默认档位的历史评价。
            if (!legacyEffort.equals(effort)) policy.put("reasoning_effort", effort);
        } else {
            JsonNode request = snapshot.path("quant_requests").path(scheme);
            JsonNode model = request.path("model");
            if (!model.isObject()) model = request.path("payload").path("models").path(0);
            if (!model.isObject()) return "";
            if (model.path("metadata").isObject()) model = model.path("metadata");
            if (!copyRequired(policy, model, "feature_version")) return "";
            if (List.of("FUND_LIGHTGBM", "FUND_XGBOOST").contains(scheme)) {
                // 旧基金工件没有完整参数合同，必须依赖明确的源码版本，不能只按算法名合并。
                if (!copyRequired(
                        policy,
                        model,
                        "model_family",
                        "selection_policy",
                        "code_version",
                        "target_version")) {
                    return "";
                }
            } else if (List.of("GOLD_LIGHTGBM", "GOLD_XGBOOST").contains(scheme)) {
                if (!copyRequired(
                                policy,
                                model,
                                "algorithm",
                                "training_version",
                                "label_version",
                                "calibration_version")
                        || !model.path("source_code")
                                .path("sha256")
                                .asText()
                                .matches("[a-f0-9]{64}")) {
                    return "";
                }
                policy.set("source_code", model.path("source_code"));
                copyOptional(policy, model, "features", "benchmark", "unit", "price_field");
            } else {
                if (!copyRequired(policy, model, "algorithm", "training_version")) return "";
                if (List.of("LGBM_BASE", "XGB_BASE", "CLOSE_LIGHTGBM", "CLOSE_XGBOOST")
                                .contains(scheme)
                        && (!model.path("source_code_sha256").isObject()
                                || model.path("source_code_sha256").isEmpty())) return "";
                JsonNode parameters = model.path("hyperparameters");
                if (parameters.isObject() && !parameters.isEmpty()) {
                    ObjectNode rules = parameters.deepCopy();
                    // 预先声明的候选选择规则属于方案；本轮选中的校准器是数据驱动结果。
                    if (rules.path("calibration_candidates").isArray()
                            && rules.hasNonNull("selection_rule")) {
                        rules.remove("selected_calibration");
                    }
                    policy.set("hyperparameters", rules);
                } else if (!copyRequired(policy, model, "model_parameter_version")) {
                    return "";
                }
                copyOptional(
                        policy,
                        model,
                        "source_code_sha256",
                        "features",
                        "label_version",
                        "protocol_version",
                        "target_semantics",
                        "benchmark_symbol",
                        "price_basis");
            }
        }
        try {
            // 转成普通Map后递归排序对象键；数组顺序仍代表特征顺序和预声明候选优先级。
            byte[] canonical = JSON.writeValueAsBytes(JSON.convertValue(policy, Object.class));
            return "ROLLING_V1_"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception invalid) {
            return "";
        }
    }

    /** 关键文本必须明确已知，缺失、空白或占位版本不能形成可推荐方案。 */
    private static boolean copyRequired(ObjectNode target, JsonNode source, String... names) {
        for (String name : names) {
            JsonNode value = source.path(name);
            if (!value.isTextual()
                    || value.asText().isBlank()
                    || List.of(
                                    "UNKNOWN",
                                    "DEV",
                                    "LOCAL_UNRELEASED",
                                    "UNRELEASED",
                                    "LEGACY-V1-INCOMPLETE")
                            .contains(value.asText().toUpperCase(java.util.Locale.ROOT))) {
                return false;
            }
            target.set(name, value);
        }
        return true;
    }

    /** 可选但已有的规则证据参与指纹，数据快照、训练日期及拟合结果不在字段白名单中。 */
    private static void copyOptional(ObjectNode target, JsonNode source, String... names) {
        for (String name : names) {
            if (source.hasNonNull(name)) target.set(name, source.path(name));
        }
    }
}
