package cn.etfquantlab.fund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** 基金解释的读取门禁：旧协议未经主体核验，不能继续冒充有效结论；不回写冻结证据。 */
public final class FundNewsIdentity {
    private static final Map<String, String> ASSET_CLASSES =
            Map.of("460300", "EQUITY", "007169", "BOND", "008701", "GOLD");

    /** 仅提供无外部调用、无数据库写入的投影。 */
    private FundNewsIdentity() {}

    /** 保留失败及资料不足状态，仅对宣称有效的基金解释核对新版身份合同。 */
    public static JsonNode project(JsonNode snapshot, String code) {
        if (!ASSET_CLASSES.containsKey(code) || !snapshot.isObject()) {
            return snapshot;
        }
        boolean available =
                "VALID".equals(snapshot.path("analysis_status").asText())
                        || "AVAILABLE".equals(snapshot.path("status").asText());
        if (!available) {
            return snapshot;
        }
        String reason = null;
        JsonNode identity = snapshot.path("fund_identity");
        if ((snapshot.hasNonNull("fund_code") && !code.equals(snapshot.path("fund_code").asText()))
                || (identity.isObject()
                        && (!code.equals(identity.path("fund_code").asText())
                                || !ASSET_CLASSES
                                        .get(code)
                                        .equals(identity.path("asset_class").asText())))) {
            reason = "FUND_IDENTITY_MISMATCH";
        }
        // 明确的跨基金来源优先显示错配；没有新版身份的历史记录只标未核验，不能断言全部错误。
        for (JsonNode document : snapshot.path("documents")) {
            String subject =
                    document.path("subject_fund_code")
                            .asText(document.path("metadata").path("fund_code").asText());
            // 旧保存快照没有subject字段，但这个既有采集器标签已明确属于008701。
            if (subject.isBlank()
                    && "CHINAAMC_008701".equals(document.path("source_name").asText())) {
                subject = "008701";
            }
            if (!subject.isBlank() && !code.equals(subject)) {
                reason = "FUND_IDENTITY_MISMATCH";
            }
        }
        if (reason == null
                && (!"fund-news-evidence-v2".equals(snapshot.path("schema_version").asText())
                        || !identity.isObject()
                        || identity.path("name").asText().isBlank()
                        || identity.path("investment_mechanism").asText().isBlank())) {
            reason = "FUND_IDENTITY_NOT_VERIFIED";
        }
        if (reason == null) {
            return snapshot;
        }
        ObjectNode result = snapshot.deepCopy();
        String status = "FUND_IDENTITY_MISMATCH".equals(reason) ? "INVALID" : "UNVERIFIED";
        result.put("analysis_status", status);
        result.put("status", status);
        result.put("display_status", status);
        result.put("forecast_usage", "NOT_USED");
        result.put("stance", "UNKNOWN");
        result.putNull("sentiment_score");
        result.put("error_code", reason);
        result.put("summary", "该基金解释未通过当前身份校验，原始记录保留，不作为有效分析展示。");
        // 页面只展示原因与资料，不继续呈现旧机制为当前结论；数据库原文与数值预测不变。
        for (String field : List.of("facts", "inferences", "mechanisms", "risk_flags")) {
            result.putArray(field);
        }
        result.putArray("reason_codes").add(reason);
        result.putArray("uncertainties").add("基金身份与材料归属需要重新核验，读取不会触发付费重试。");
        return result;
    }

    /** 冻结预测仅替换响应中的新闻展示，不修改预测数值、日期及原始持久记录。 */
    public static JsonNode forecast(JsonNode record, String code) {
        JsonNode news = record.path("news_snapshot");
        JsonNode projected = project(news, code);
        if (projected == news) {
            return record;
        }
        ObjectNode result = record.deepCopy();
        result.set("news_snapshot", projected);
        result.put("news_usage", "NOT_USED");
        return result;
    }
}
