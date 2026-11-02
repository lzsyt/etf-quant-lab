package cn.etfquantlab.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对可比的冻结研究样本评分，选优另需时间留出验证；不产生交易指令。 */
public final class ForecastEvaluation {
    /** 纯计算工具不持有数据库或调用外部模型的能力。 */
    private ForecastEvaluation() {}

    /** 同资产、目标、期限、性质、协议版本和能力分组，模型制品版本不混算。 */
    public static List<Map<String, Object>> groups(List<JsonNode> records) {
        Map<List<String>, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (JsonNode record : records) {
            List<String> key =
                    List.of(
                            record.path("asset").asText(),
                            record.path("target").asText(),
                            record.path("horizon").asText(),
                            record.path("mode").asText(),
                            record.path("target_version").asText(),
                            "PREDICTOR".equals(record.path("role").asText())
                                    ? "PREDICTOR"
                                    : record.path("output_type").asText());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<JsonNode> group : grouped.values()) {
            result.add(group(group));
        }
        return result;
    }

    /** 每个机会只保留该版本最新冻结记录，匹配评分使用所有个体的共同机会交集。 */
    private static Map<String, Object> group(List<JsonNode> rows) {
        Map<List<String>, List<JsonNode>> individuals = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            individuals
                    .computeIfAbsent(
                            List.of(
                                    row.path("scheme").asText(),
                                    row.path("model_version").asText()),
                            ignored -> new ArrayList<>())
                    .add(row);
        }
        Map<List<String>, Map<String, JsonNode>> samples = new LinkedHashMap<>();
        for (var individual : individuals.entrySet()) {
            Map<String, JsonNode> eligible = new LinkedHashMap<>();
            for (JsonNode row : individual.getValue()) {
                if (scorable(row)) {
                    String key = row.path("sample_key").asText();
                    JsonNode previous = eligible.get(key);
                    if (previous == null
                            || Instant.parse(row.path("generated_at").asText())
                                    .isAfter(
                                            Instant.parse(
                                                    previous.path("generated_at").asText()))) {
                        eligible.put(key, row);
                    }
                }
            }
            samples.put(individual.getKey(), eligible);
        }
        Set<String> matched = new HashSet<>();
        if (samples.size() > 1) {
            matched.addAll(samples.values().iterator().next().keySet());
            for (Map<String, JsonNode> individual : samples.values()) {
                matched.retainAll(individual.keySet());
            }
            // 同机会的实际收益也必须一致，避免后续数据修订让不同个体使用不同答案。
            matched.removeIf(key -> !sameActual(samples, key));
            matched = latestCommonWindows(matched, samples.values().iterator().next());
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (var individual : individuals.entrySet()) {
            Map<String, JsonNode> all = samples.get(individual.getKey());
            Map<String, JsonNode> eligible = latestWindows(all);
            long eligibleRecords =
                    individual.getValue().stream().filter(ForecastEvaluation::scorable).count();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scheme", individual.getKey().get(0));
            summary.put("model_version", individual.getKey().get(1));
            summary.put("samples", individual.getValue().size());
            summary.put("evaluated_samples", eligible.size());
            summary.put("excluded_samples", individual.getValue().size() - eligibleRecords);
            summary.put("duplicate_samples", eligibleRecords - eligible.size());
            summary.put("metrics", metrics(new ArrayList<>(eligible.values())));
            summary.put("matched_samples", matched.size());
            summary.put("matched_metrics", metrics(matched.stream().map(all::get).toList()));
            summaries.add(summary);
        }
        JsonNode first = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field :
                List.of("asset", "target", "horizon", "mode", "target_version", "output_type")) {
            result.put(field, first.path(field).asText());
        }
        if (rows.stream().map(r -> r.path("output_type").asText()).distinct().count() > 1) {
            result.put("output_type", "MIXED_PREDICTION");
        }
        result.put("individuals", summaries);
        // 模型换代后的版本不可能始终共存，因此另提供跨方案的两两同机会比较。
        List<Map<String, Object>> comparisons = new ArrayList<>();
        List<List<String>> versions = new ArrayList<>(samples.keySet());
        boolean comparisonsTruncated = false;
        for (int leftIndex = 0; leftIndex < versions.size(); leftIndex++) {
            List<String> left = versions.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < versions.size(); rightIndex++) {
                List<String> right = versions.get(rightIndex);
                if (left.get(0).equals(right.get(0))) {
                    continue;
                }
                Set<String> shared = new HashSet<>(samples.get(left).keySet());
                shared.retainAll(samples.get(right).keySet());
                Map<List<String>, Map<String, JsonNode>> pair =
                        Map.of(left, samples.get(left), right, samples.get(right));
                shared.removeIf(key -> !sameActual(pair, key));
                shared = latestCommonWindows(shared, samples.get(left));
                if (shared.isEmpty()) {
                    continue;
                }
                if (comparisons.size() == 200) {
                    comparisonsTruncated = true;
                    break;
                }
                comparisons.add(
                        Map.of(
                                "left",
                                comparisonSide(left, samples.get(left), shared),
                                "right",
                                comparisonSide(right, samples.get(right), shared),
                                "matched_samples",
                                shared.size(),
                                "reason",
                                "同协议、同输入截止和实际结果的共同样本；不自动推荐"));
            }
            if (comparisonsTruncated) {
                break;
            }
        }
        result.put("comparisons", comparisons);
        result.put("comparisons_truncated", comparisonsTruncated);
        result.put("matched_samples", matched.size());
        result.put("recommendation", ForecastSelection.select(rows));
        result.put(
                "reason",
                individuals.size() < 2
                        ? "同协议尚无两个独立版本可比较，暂无推荐"
                        : comparisons.isEmpty()
                                ? "没有跨方案相同目标与截止时点的匹配样本，暂无推荐"
                                : "提供共同机会评分；优先参考另需满足不重叠样本、时间留出验证和简单基线优势");
        return result;
    }

    /** 两侧指标严格使用同一机会集合，各自全部样本指标仍单独保留。 */
    private static Map<String, Object> comparisonSide(
            List<String> identity, Map<String, JsonNode> rows, Set<String> keys) {
        return Map.of(
                "scheme",
                identity.get(0),
                "model_version",
                identity.get(1),
                "metrics",
                metrics(keys.stream().map(rows::get).toList()));
    }

    /** 原生合法性、独立角色和至少一种有效数值同时满足，才进入数值评价。 */
    static boolean scorable(JsonNode row) {
        if (!row.path("evaluation_eligible").asBoolean(false)
                || !"PREDICTOR".equals(row.path("role").asText())
                || row.path("sample_key").asText().isBlank()
                || decimal(row.path("actual_return")) == null) {
            return false;
        }
        JsonNode output = row.path("output");
        return decimal(output.path("point_return")) != null
                || probability(output.path("probability_up")) != null;
    }

    /** 同一行情目标多次刷新只计一个机会，保留最新的合法冻结结果。 */
    static Map<String, JsonNode> latestWindows(Map<String, JsonNode> input) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode row : input.values()) {
            String key = windowKey(row);
            JsonNode old = result.get(key);
            if (old == null
                    || Instant.parse(row.path("generated_at").asText())
                            .isAfter(Instant.parse(old.path("generated_at").asText()))) {
                result.put(key, row);
            }
        }
        return result;
    }

    /** 匹配先取共同截止，再按行情目标去重，避免调用频繁个体获得更多样本权重。 */
    private static Set<String> latestCommonWindows(Set<String> keys, Map<String, JsonNode> rows) {
        Map<String, JsonNode> subset = new LinkedHashMap<>();
        keys.forEach(key -> subset.put(key, rows.get(key)));
        Set<String> result = new HashSet<>();
        latestWindows(subset).values().forEach(row -> result.add(row.path("sample_key").asText()));
        return result;
    }

    /** 原生日期完整时按真实起止机会识别，否则仅保留已验证的显式样本键。 */
    static String windowKey(JsonNode row) {
        String anchor = row.path("anchor_date").asText();
        String target = row.path("actual_target_date").asText();
        return anchor.isBlank() || target.isBlank()
                ? row.path("sample_key").asText()
                : anchor + "|" + target;
    }

    /** 匹配机会的答案必须相同；实际值缺失不会被默认成零。 */
    private static boolean sameActual(
            Map<List<String>, Map<String, JsonNode>> samples, String key) {
        BigDecimal expected = null;
        for (Map<String, JsonNode> individual : samples.values()) {
            BigDecimal actual = decimal(individual.get(key).path("actual_return"));
            if (expected != null && expected.compareTo(actual) != 0) {
                return false;
            }
            expected = actual;
        }
        return true;
    }

    /** 点值、概率与价格区间分别评分并报告各自分母，绝不相互推导缺失能力。 */
    private static Map<String, Object> metrics(List<JsonNode> rows) {
        BigDecimal error = BigDecimal.ZERO;
        BigDecimal baseline = BigDecimal.ZERO;
        BigDecimal brier = BigDecimal.ZERO;
        BigDecimal width = BigDecimal.ZERO;
        int pointCount = 0;
        int pointCorrect = 0;
        int probabilityCount = 0;
        int probabilityCorrect = 0;
        int intervalCount = 0;
        int covered = 0;
        for (JsonNode row : rows) {
            BigDecimal actual = decimal(row.path("actual_return"));
            JsonNode output = row.path("output");
            BigDecimal point = decimal(output.path("point_return"));
            BigDecimal p = probability(output.path("probability_up"));
            if (point != null) {
                pointCount++;
                error = error.add(point.subtract(actual).abs());
                baseline = baseline.add(actual.abs());
                if ((point.signum() > 0) == (actual.signum() > 0)) {
                    pointCorrect++;
                }
            }
            if (p != null) {
                probabilityCount++;
                brier =
                        brier.add(
                                p.subtract(actual.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO)
                                        .pow(2));
                if ((p.compareTo(new BigDecimal("0.5")) >= 0) == (actual.signum() > 0)) {
                    probabilityCorrect++;
                }
            }
            BigDecimal lower = decimal(output.path("interval_lower"));
            BigDecimal upper = decimal(output.path("interval_upper"));
            BigDecimal price = decimal(row.path("evaluation").path("target_price"));
            if (lower != null && upper != null && price != null && lower.compareTo(upper) <= 0) {
                intervalCount++;
                width = width.add(upper.subtract(lower));
                if (price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0) {
                    covered++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mae", mean(error, pointCount));
        result.put("zero_return_mae", mean(baseline, pointCount));
        result.put("brier", mean(brier, probabilityCount));
        result.put(
                "direction_accuracy",
                pointCount > 0
                        ? mean(BigDecimal.valueOf(pointCorrect), pointCount)
                        : mean(BigDecimal.valueOf(probabilityCorrect), probabilityCount));
        result.put(
                "probability_direction_accuracy",
                mean(BigDecimal.valueOf(probabilityCorrect), probabilityCount));
        result.put("interval_coverage", mean(BigDecimal.valueOf(covered), intervalCount));
        result.put("interval_mean_width", mean(width, intervalCount));
        result.put("point_samples", pointCount);
        result.put("probability_samples", probabilityCount);
        result.put("interval_samples", intervalCount);
        return result;
    }

    /** 分母为零时返回未知，不渲染为零准确率或零误差。 */
    private static BigDecimal mean(BigDecimal sum, int count) {
        return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
    }

    /** 概率超出闭区间即不可评价，不能裁剪成看似有效的模型输出。 */
    private static BigDecimal probability(JsonNode value) {
        BigDecimal number = decimal(value);
        return number != null && number.signum() >= 0 && number.compareTo(BigDecimal.ONE) <= 0
                ? number
                : null;
    }

    /** 字符串收益与JSON数值都使用Decimal；缺失、非法、NaN与布尔值保持未知。 */
    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
