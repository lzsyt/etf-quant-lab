package cn.etfquantlab.forecast;

import cn.etfquantlab.fund.FundNewsIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 将独立预测和旧领域的冻结解释投影成业务可读内容，不输出提示词或供应商原始响应。 */
final class ForecastAnalysis {
    /** 仅投影已存档字段，不持有任何采集或调用能力。 */
    private ForecastAnalysis() {}

    /** 优先使用当前记录自己的分析，旧记录只能读取其明确关联的冻结解释。 */
    static ObjectNode frozen(ObjectMapper json, JsonNode record) {
        JsonNode raw = record.path("analysis");
        if (!raw.isObject() || raw.isEmpty()) {
            raw = record.path("evidence").path("frozen_analysis");
        }
        if (!raw.isObject() || raw.isEmpty()) {
            raw = record.path("evidence").path("record").path("details").path("news_snapshot");
        }
        if (!raw.isObject() || raw.isEmpty()) {
            raw = record.path("evidence").path("analysis");
        }
        if ((!raw.isObject() || raw.isEmpty())
                && List.of("DEEPSEEK", "GPT", "GPT_DISAGREEMENT")
                        .contains(record.path("scheme").asText())) {
            ObjectNode independent = json.createObjectNode();
            independent.set("result", record.path("evidence").path("result"));
            independent.set("input_snapshot", record.path("evidence").path("input"));
            independent.set("evidence_catalog", record.path("evidence").path("catalog"));
            raw = independent;
        }
        if (raw.isObject()
                && List.of("DEEPSEEK", "GPT", "GPT_DISAGREEMENT")
                        .contains(record.path("scheme").asText())) {
            ObjectNode enriched = raw.deepCopy();
            if (record.path("evidence").path("catalog").isArray()) {
                enriched.set("evidence_catalog", record.path("evidence").path("catalog"));
            }
            if (record.path("evidence").path("input").isObject()) {
                enriched.set("input_snapshot", record.path("evidence").path("input"));
            }
            raw = enriched;
        }
        ObjectNode result = project(json, raw, record.path("asset").asText());
        if (!result.hasNonNull("generated_at"))
            result.set("generated_at", record.path("generated_at"));
        if (!result.hasNonNull("input_cutoff"))
            result.set("input_cutoff", record.path("data_cutoff"));
        if ("MISSING".equals(result.path("status").asText())
                && "PREDICTOR".equals(record.path("role").asText())
                && !List.of("DEEPSEEK", "GPT").contains(record.path("scheme").asText())) {
            result.put("status", "QUANT_INPUT_ONLY");
            result.put("analysis_status", "QUANT_INPUT_ONLY");
            result.put(
                    "summary",
                    "该结果由"
                            + record.path("scheme").asText()
                            + "模型基于冻结行情推理；模型版本："
                            + record.path("model_version").asText()
                            + "。原记录没有独立资讯解释时，不补造新闻结论。");
            result.putArray("facts")
                    .add("预测锚点：" + record.path("anchor_date").asText())
                    .add("目标协议：" + record.path("target_version").asText())
                    .add("模型验证状态：" + record.path("model_validation_status").asText("UNKNOWN"));
            quantFacts(result.withArray("facts"), record);
        }
        result.put("context", "FROZEN_FORECAST_EVIDENCE");
        if ("REVIEW".equals(record.path("role").asText())) {
            result.put("context", "DISAGREEMENT_ANALYSIS");
            result.set("predictions", record.path("evidence").path("input").path("predictions"));
        }
        return result;
    }

    /** 量化依据展示真实冻结特征，训练期重要性不冒充本次预测的因果贡献。 */
    private static void quantFacts(ArrayNode facts, JsonNode record) {
        JsonNode evidence = record.path("evidence");
        JsonNode raw = evidence.path("result");
        if (!raw.isObject()) raw = evidence.path("record").path("details");
        JsonNode values = raw.path("feature_values");
        if (!values.isObject()) values = raw.path("input_snapshot").path("features");
        if (!values.isObject()) values = raw.path("ml").path("feature_values");
        addFeatureFacts(facts, values, "本次冻结特征值（不是涨跌因果证明）：");
        JsonNode model = evidence.path("input").path("model");
        if (!model.isObject())
            model = evidence.path("input").path("payload").path("models").path(0);
        if (model.path("metadata").isObject()) model = model.path("metadata");
        addFeatureFacts(facts, model.path("global_feature_importance"), "训练期全局特征重要性（不等于本次预测贡献）：");
    }

    /** 已有特征值有界展示，未知名称保留原名，缺失项不补零。 */
    private static void addFeatureFacts(ArrayNode facts, JsonNode values, String heading) {
        if (!values.isObject() || values.isEmpty()) return;
        facts.add(heading);
        var fields = values.fields();
        int count = 0;
        while (fields.hasNext() && count++ < 12) {
            var field = fields.next();
            String name =
                    field.getKey()
                            .replace("return_", "收益窗口_")
                            .replace("volatility_", "波动窗口_")
                            .replace("vol_", "波动窗口_")
                            .replace("drawdown_", "回撤窗口_")
                            .replace("distance_", "均线距离窗口_");
            if (field.getValue().isValueNode()) facts.add(name + "：" + field.getValue().asText());
        }
    }

    /** 兼容旧供应商多层result封装；仅选择当前资产，不合并其他资产的判断。 */
    static ObjectNode project(ObjectMapper json, JsonNode raw, String asset) {
        JsonNode content = raw;
        for (int depth = 0; depth < 5 && content.path("result").isObject(); depth++) {
            content = content.path("result");
        }
        if (content.path("analysis").isObject()) {
            content = content.path("analysis");
        }
        if (content.path("items").isArray()) {
            JsonNode match = null;
            for (JsonNode item : content.path("items")) {
                if (asset.equals(
                        item.path("symbol")
                                .asText(
                                        item.path("asset")
                                                .asText(item.path("fund_code").asText())))) {
                    match = item;
                    break;
                }
            }
            content = match == null ? json.createObjectNode() : match;
        }
        // 旧基金解释只读降级；独立数值预测没有fund_code，不走新闻协议门禁。
        if (content.has("fund_code") || raw.has("analysis_status")) {
            ObjectNode fundContent = content.deepCopy();
            JsonNode envelope = raw.path("result").path("analysis");
            if (envelope.isObject()) {
                for (String field : List.of("schema_version", "fund_identity", "documents")) {
                    if (!fundContent.has(field) && envelope.has(field)) {
                        fundContent.set(field, envelope.path(field));
                    }
                }
            }
            content = FundNewsIdentity.project(fundContent, asset);
        }
        ObjectNode result = json.createObjectNode();
        result.put(
                "status",
                content.path("status")
                        .asText(
                                raw.path("analysis_status")
                                        .asText(raw.path("status").asText("MISSING"))));
        result.put("analysis_status", result.path("status").asText());
        JsonNode modelSettings = raw.path("model_settings");
        if (modelSettings.isObject()) {
            result.set("model_settings", modelSettings);
        } else if (raw.path("details").path("model_settings").isObject()) {
            result.set("model_settings", raw.path("details").path("model_settings"));
        }
        result.put(
                "summary",
                content.path("summary")
                        .asText(content.path("rationale").asText("暂无可读分析；请核对执行状态与证据是否充足")));
        result.put(
                "stance",
                content.path("stance").asText(content.path("direction").asText("UNKNOWN")));
        result.put(
                "generated_at",
                raw.path("generated_at").asText(raw.path("created_at").asText(null)));
        result.put(
                "input_cutoff",
                raw.path("input_cutoff")
                        .asText(
                                raw.path("input_cutoff_at")
                                        .asText(raw.path("data_cutoff").asText(null))));
        for (String field :
                List.of(
                        "facts",
                        "inferences",
                        "positive_factors",
                        "counter_factors",
                        "uncertainties",
                        "invalidation_conditions",
                        "evidence_ids",
                        "reason_codes")) {
            ArrayNode values = result.putArray(field);
            if (content.path(field).isArray()) {
                content.path(field).forEach(values::add);
            } else if (content.path(field).isTextual()) {
                values.add(content.path(field).asText());
            }
        }
        ArrayNode mechanisms = result.putArray("mechanisms");
        for (JsonNode mechanism : content.path("mechanisms")) {
            addText(mechanisms, mechanism);
            if (mechanism.hasNonNull("mechanism"))
                mechanisms.add(mechanism.path("mechanism").asText());
            addText(result.withArray("counter_factors"), mechanism.path("counter_factor"));
            addText(result.withArray("uncertainties"), mechanism.path("uncertainty"));
            addText(result.withArray("invalidation_conditions"), mechanism.path("invalidation"));
        }
        addText(mechanisms, content.path("impact_logic"));
        addText(result.withArray("uncertainties"), content.path("uncertainty"));
        addText(result.withArray("invalidation_conditions"), content.path("invalidation"));
        addText(result.withArray("reason_codes"), raw.path("error_code"));
        ArrayNode documents = result.putArray("documents");
        JsonNode sources = raw.path("documents");
        if (!sources.isArray()) sources = raw.path("evidence_catalog");
        if (!sources.isArray()) sources = raw.path("input_snapshot").path("news");
        java.util.Set<String> cited = new java.util.HashSet<>();
        result.path("evidence_ids").forEach(id -> cited.add(id.asText()));
        for (JsonNode source : sources) {
            if (sources == raw.path("evidence_catalog")
                    && !cited.isEmpty()
                    && !cited.contains(
                            source.path("id").asText(source.path("evidence_id").asText())))
                continue;
            if (documents.size() == 20) break;
            ObjectNode item = documents.addObject();
            for (String field :
                    List.of(
                            "id",
                            "evidence_id",
                            "source_id",
                            "source_name",
                            "title",
                            "url",
                            "source_url",
                            "published_at",
                            "first_observed_at",
                            "provider")) {
                if (source.hasNonNull(field)) item.set(field, source.path(field));
            }
            if (!item.hasNonNull("title")) {
                for (JsonNode news : raw.path("input_snapshot").path("news")) {
                    if (source.path("source_id").asText().equals(news.path("id").asText())) {
                        item.set("title", news.path("title"));
                        item.set("source_name", news.path("source_name"));
                        break;
                    }
                }
            }
            String excerpt =
                    source.path("excerpt")
                            .asText(source.path("text").asText(source.path("summary").asText()));
            item.put("excerpt", excerpt.substring(0, Math.min(240, excerpt.length())));
        }
        return result;
    }

    /** 空白不构成因素或不确定性，不在页面制造无效占位。 */
    private static void addText(ArrayNode target, JsonNode value) {
        if (value.isTextual() && !value.asText().isBlank()) target.add(value.asText());
    }
}
