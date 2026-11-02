package cn.etfquantlab.forward;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 四组预注册规则独立于旧策略，缺失与零仓位在类型和值上严格区分。 */
public final class ForwardRules {
    public static final String PROTOCOL = "OPEN_NET_REBALANCE_V1";
    public static final String LABEL = "OPEN_TO_OPEN_H1_V1";
    public static final String TARGET = "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP";
    public static final String VERSION = "forward-v2.0.0";
    public static final List<String> SYMBOLS = List.of("510300", "510500", "159915");
    public static final List<String> STRATEGIES =
            List.of("LGBM_BASE", "XGB_BASE", "LGBM_MATCHED_CONTROL", "FUSION_EXPERIMENT");

    /** 工具类不持有运行状态。 */
    private ForwardRules() {}

    /** 新闻有效性由逐ETF结构与来源证据决定；有效high保持有效，不能借降级绕过。 */
    public static Map<String, Object> decide(
            String strategy, String symbol, JsonNode ml, JsonNode news) {
        if (!STRATEGIES.contains(strategy) || !SYMBOLS.contains(symbol)) {
            throw new IllegalArgumentException("未知前瞻实验策略或标的");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("strategy", strategy);
        row.put("strategy_version", VERSION);
        row.put("label_version", LABEL);
        row.put("protocol_version", PROTOCOL);
        row.put("target_semantics", TARGET);
        row.put("horizon", 1);
        row.put("ml", ml);
        row.put("news", news);
        row.put("target_weight", null);
        row.put("eligible", false);
        row.put("effective_mode", "ML");
        boolean available =
                "AVAILABLE".equals(news.path("status").asText())
                        && news.path("horizon").asInt() == 1
                        && symbol.equals(news.path("symbol").asText())
                        && news.path("sentiment_score").isNumber()
                        && Double.isFinite(news.path("sentiment_score").asDouble())
                        && Math.abs(news.path("sentiment_score").asDouble()) <= 1
                        && List.of("low", "medium", "high")
                                .contains(news.path("risk_level").asText())
                        && news.path("relevant_source_ids").isArray()
                        && !news.path("relevant_source_ids").isEmpty();
        if (available) {
            for (JsonNode source : news.path("relevant_source_ids")) {
                available = available && source.isTextual() && !source.asText().isBlank();
            }
        }
        row.put("news_available", available);
        String missing =
                news.path("error_code").asText(news.path("status").asText("NO_RELEVANT_NEWS"));
        if ("AVAILABLE".equals(news.path("status").asText()) && !available) {
            missing = "INVALID_OUTPUT";
        }
        if (missing.isBlank()) {
            missing = "NO_RELEVANT_NEWS";
        }
        row.put("news_reason", available ? "AVAILABLE" : missing);
        String modelFailure = modelFailure(strategy, symbol, ml);
        if (modelFailure != null) {
            row.put("reason_code", modelFailure);
            row.put("reason", "模型缺失、过期或数据异常；无有效目标，保持既有持仓并执行硬风控");
            return row;
        }
        BigDecimal p = ml.path("probability").decimalValue();
        row.put("probability", p);
        row.put("raw_probability", ml.path("raw_probability").decimalValue());
        row.put("model_id", ml.path("model_id").asText());
        boolean eligible = p.compareTo(new BigDecimal("0.55")) >= 0;
        String formula = "P >= 0.55";
        String reason = eligible ? "ELIGIBLE" : "BELOW_THRESHOLD";
        boolean newsBranch =
                List.of("LGBM_MATCHED_CONTROL", "FUSION_EXPERIMENT").contains(strategy);
        if (newsBranch && available) {
            BigDecimal score =
                    new BigDecimal("0.7")
                            .multiply(p.multiply(new BigDecimal("2")).subtract(BigDecimal.ONE));
            formula = "0.7 × (2 × P − 1)";
            if ("FUSION_EXPERIMENT".equals(strategy)) {
                score =
                        score.add(
                                new BigDecimal("0.3")
                                        .multiply(news.path("sentiment_score").decimalValue()));
                formula += " + 0.3 × S";
                row.put("effective_mode", "FUSION");
            }
            row.put("score", score);
            eligible = score.compareTo(new BigDecimal("0.1")) > 0;
            reason = eligible ? "ELIGIBLE" : "BELOW_THRESHOLD";
            if ("FUSION_EXPERIMENT".equals(strategy)
                    && "high".equals(news.path("risk_level").asText())) {
                eligible = false;
                reason = "NEWS_RISK_VETO";
            }
        } else if (newsBranch) {
            row.put("effective_mode", "ML_FALLBACK");
            row.put("fallback_reason", missing);
        }
        row.put("formula", formula);
        row.put("eligible", eligible);
        row.put("target_weight", BigDecimal.ZERO);
        row.put("reason_code", reason);
        row.put(
                "reason",
                switch (reason) {
                    case "ELIGIBLE" -> "达到固定资格阈值，按冻结目标及交易规则执行";
                    case "NEWS_RISK_VETO" -> "有效新闻高风险否决，目标为零；合法时点减仓并遵守T+1";
                    default -> "未达固定买入阈值";
                });
        return row;
    }

    /** 除算法外必须匹配完整生产模型身份，旧标签、研究工件和测试输入不能进入新实验。 */
    private static String modelFailure(String strategy, String symbol, JsonNode ml) {
        if (!"SUCCEEDED".equals(ml.path("status").asText())) {
            return ml.path("error_code").asText("MODEL_MISSING_OR_STALE");
        }
        String algorithm = "XGB_BASE".equals(strategy) ? "xgboost" : "lightgbm";
        if (!symbol.equals(ml.path("symbol").asText())
                || !algorithm.equals(ml.path("algorithm").asText())
                || !"ohlcv-v2".equals(ml.path("feature_version").asText())
                || !LABEL.equals(ml.path("label_version").asText())
                || !PROTOCOL.equals(ml.path("protocol_version").asText())
                || !TARGET.equals(ml.path("target_semantics").asText())
                || !"REAL".equals(ml.path("nature").asText())
                || !"RAW".equals(ml.path("price_basis").asText())
                || !"FORWARD_PRODUCTION".equals(ml.path("model_role").asText())
                || !ml.path("horizon").isIntegralNumber()
                || ml.path("horizon").asInt() != 1
                || ml.path("model_id").asText().isBlank()) {
            return "MODEL_IDENTITY_MISMATCH";
        }
        if (!validProbability(ml.path("probability"))
                || !validProbability(ml.path("raw_probability"))) {
            return "MODEL_PROBABILITY_INVALID";
        }
        return null;
    }

    /** 概率必须是有限数且在闭区间内，缺失原始概率不能被默认为零。 */
    private static boolean validProbability(JsonNode probability) {
        return probability.isNumber()
                && Double.isFinite(probability.asDouble())
                && probability.asDouble() >= 0
                && probability.asDouble() <= 1;
    }

    /** 缺失目标保持仓位并预留其占用，其余合格标的在剩余40%以内等分。 */
    public static void allocate(List<Map<String, Object>> rows, Map<String, BigDecimal> weights) {
        BigDecimal held =
                rows.stream()
                        .filter(row -> row.get("target_weight") == null)
                        .map(
                                row ->
                                        weights.getOrDefault(
                                                row.get("symbol").toString(), BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("eligible"))).count();
        BigDecimal weight =
                count == 0
                        ? BigDecimal.ZERO
                        : new BigDecimal("0.4")
                                .subtract(held)
                                .max(BigDecimal.ZERO)
                                .divide(BigDecimal.valueOf(count), MathContext.DECIMAL64)
                                .min(new BigDecimal("0.2"));
        for (Map<String, Object> row : rows) {
            row.put(
                    "current_weight",
                    weights.getOrDefault(row.get("symbol").toString(), BigDecimal.ZERO));
            if (Boolean.TRUE.equals(row.get("eligible"))) {
                row.put("target_weight", weight);
            }
        }
    }
}
