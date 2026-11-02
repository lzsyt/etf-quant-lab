package cn.etfquantlab.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定规则的历史证据选优；候选选择和时间留出验证分开，不参与证券委托。 */
final class ForecastSelection {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int SELECTION_SIZE = 30;
    private static final int VALIDATION_SIZE = 10;
    private static final BigDecimal ADVANTAGE = new BigDecimal("0.95");

    /** 无状态纯计算，不读取未来行情、不调用预测服务。 */
    private ForecastSelection() {}

    /** 固定训练与输入协议可滚动更新制品，制品成绩仍由上层逐版本单独报告。 */
    static Map<String, Object> select(List<JsonNode> records) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_version", "ROLLING_POLICY_COMMON_PURGED_30_10_V1");
        result.put("minimum_selection_samples", SELECTION_SIZE);
        result.put("minimum_validation_samples", VALIDATION_SIZE);
        result.put("selection_samples", 0);
        result.put("validation_samples", 0);
        result.put("scope", "历史样本优先参考；不代表未来收益保证或交易指令");
        if (!"REAL".equals(records.get(0).path("mode").asText())) {
            return stop(result, "INCOMPARABLE", "演示数据不产生真实预测个体推荐");
        }
        Map<String, JsonNode> newest = new LinkedHashMap<>();
        Map<List<String>, Map<String, JsonNode>> samples = new LinkedHashMap<>();
        for (JsonNode row : records) {
            if (!"PREDICTOR".equals(row.path("role").asText())) {
                continue;
            }
            String scheme = row.path("scheme").asText();
            JsonNode old = newest.get(scheme);
            if (old == null || time(row).isAfter(time(old))) {
                newest.put(scheme, row);
            }
            String policy = row.path("selection_policy").asText();
            if (policy.isBlank()) continue;
            Map<String, JsonNode> policySamples =
                    samples.computeIfAbsent(
                            List.of(scheme, policy), ignored -> new LinkedHashMap<>());
            if (ForecastEvaluation.scorable(row)) {
                String key = row.path("sample_key").asText();
                JsonNode previous = policySamples.get(key);
                if (previous == null || time(row).isAfter(time(previous)))
                    policySamples.put(key, row);
            }
        }
        List<List<String>> candidates = new ArrayList<>();
        for (JsonNode row : newest.values()) {
            String policy = row.path("selection_policy").asText();
            if (!policy.isBlank()
                    && !"FAILED".equals(row.path("model_validation_status").asText())) {
                candidates.add(List.of(row.path("scheme").asText(), policy));
            }
        }
        candidates.sort(Comparator.comparing(identity -> String.join("|", identity)));
        result.put("candidate_count", candidates.size());
        if (candidates.size() < 2) {
            return stop(
                    result,
                    "INSUFFICIENT_CANDIDATES",
                    "至少需要两个具备可追踪稳定训练与输入规则的独立方案；旧记录缺少规则身份或验证失败时仅保留研究成绩");
        }
        Set<String> shared = new HashSet<>(samples.get(candidates.get(0)).keySet());
        for (List<String> candidate : candidates) {
            shared.retainAll(samples.get(candidate).keySet());
        }
        Map<String, JsonNode> reference = samples.get(candidates.get(0));
        shared.removeIf(
                key ->
                        candidates.stream()
                                .anyMatch(
                                        candidate ->
                                                number(
                                                                        samples.get(candidate)
                                                                                .get(key),
                                                                        "actual_return")
                                                                .compareTo(
                                                                        number(
                                                                                reference.get(key),
                                                                                "actual_return"))
                                                        != 0));
        Map<String, JsonNode> common = new LinkedHashMap<>();
        shared.forEach(key -> common.put(key, reference.get(key)));
        List<JsonNode> dated =
                ForecastEvaluation.latestWindows(common).values().stream()
                        .filter(
                                row ->
                                        date(row, "anchor_date") != null
                                                && date(row, "actual_target_date") != null)
                        .sorted(
                                Comparator.comparing((JsonNode row) -> date(row, "anchor_date"))
                                        .reversed())
                        .toList();
        // 从最近机会向前取样，上一段的实际终点不得越过下一段起点；长周期重叠标签不重复计票。
        List<String> windows = new ArrayList<>();
        LocalDate nextStart = LocalDate.MAX;
        for (JsonNode row : dated) {
            LocalDate start = date(row, "anchor_date");
            LocalDate end = date(row, "actual_target_date");
            if (end.isAfter(start) && !end.isAfter(nextStart)) {
                windows.add(row.path("sample_key").asText());
                nextStart = start;
            }
        }
        result.put("matched_samples", common.size());
        result.put("nonoverlapping_samples", windows.size());
        if (windows.size() < SELECTION_SIZE + VALIDATION_SIZE) {
            return stop(
                    result,
                    "INSUFFICIENT_SAMPLES",
                    "固定规则方案共同且不重叠的成熟机会不足40个：需30个选择样本和随后10个留出样本；长周期需持续较长时间观察，不能用重叠日期充数");
        }
        List<String> validation = new ArrayList<>(windows.subList(0, VALIDATION_SIZE));
        java.util.Collections.reverse(validation);
        Instant holdoutCutoff =
                instant(reference.get(validation.get(0)).path("data_cutoff").asText());
        if (holdoutCutoff == null) {
            return stop(result, "INSUFFICIENT_PIT_EVIDENCE", "留出窗口缺少冻结信息截止，不能证明当时已具备选择条件");
        }
        // 净值公布、补抓和修订可能晚于交易日期。选择标签必须在首条留出预测截止前已实际可得。
        List<String> selection = new ArrayList<>();
        for (String key : windows.subList(VALIDATION_SIZE, windows.size())) {
            boolean known =
                    candidates.stream()
                            .allMatch(
                                    candidate -> {
                                        Instant available =
                                                instant(
                                                        samples.get(candidate)
                                                                .get(key)
                                                                .path("actual_available_at")
                                                                .asText());
                                        return available != null
                                                && !available.isAfter(holdoutCutoff);
                                    });
            if (known) selection.add(key);
            if (selection.size() == SELECTION_SIZE) break;
        }
        if (selection.size() < SELECTION_SIZE) {
            result.put("selection_samples", selection.size());
            return stop(
                    result, "INSUFFICIENT_PIT_EVIDENCE", "去除留出开始后才公布、补抓或缺少可得时间的实际结果后，选择样本不足30个");
        }
        java.util.Collections.reverse(selection);
        windows = new ArrayList<>(selection);
        windows.addAll(validation);
        result.put("selection_samples", selection.size());
        result.put("validation_samples", validation.size());
        result.put(
                "window",
                Map.of(
                        "selection_start",
                                date(reference.get(selection.get(0)), "anchor_date").toString(),
                        "selection_end",
                                date(
                                                reference.get(selection.get(selection.size() - 1)),
                                                "actual_target_date")
                                        .toString(),
                        "validation_start",
                                date(reference.get(validation.get(0)), "anchor_date").toString(),
                        "validation_end",
                                date(
                                                reference.get(
                                                        validation.get(validation.size() - 1)),
                                                "actual_target_date")
                                        .toString()));
        boolean allPoint = complete(candidates, samples, windows, "point_return");
        boolean allProbability = complete(candidates, samples, windows, "probability_up");
        if (!allPoint && !allProbability) {
            return stop(result, "INCOMPARABLE", "共同样本没有所有候选都具备的点收益或概率能力，不能混用指标选优");
        }
        String field = allPoint ? "point_return" : "probability_up";
        result.put("metric", allPoint ? "MAE" : "BRIER");
        List<String> winner = null;
        BigDecimal best = null;
        for (List<String> candidate : candidates) {
            BigDecimal loss = loss(samples.get(candidate), selection, field, null);
            if (best == null || loss.compareTo(best) < 0) {
                best = loss;
                winner = candidate;
            }
        }
        // 留出段只验证已选个体，不能看到留出成绩后改选另一个赢家。
        BigDecimal heldOut = loss(samples.get(winner), validation, field, null);
        BigDecimal baseline =
                loss(
                        reference,
                        validation,
                        field,
                        allPoint ? BigDecimal.ZERO : new BigDecimal("0.5"));
        if (!allPoint) {
            long up =
                    selection.stream()
                            .filter(key -> number(reference.get(key), "actual_return").signum() > 0)
                            .count();
            BigDecimal frequency =
                    BigDecimal.valueOf(up).divide(BigDecimal.valueOf(selection.size()), MC);
            baseline = baseline.min(loss(reference, validation, field, frequency));
        }
        result.put("candidate_scheme", winner.get(0));
        result.put(
                "candidate_model_version",
                newest.get(winner.get(0)).path("model_version").asText());
        result.put("selection_policy", winner.get(1));
        Map<String, JsonNode> winnerSamples = samples.get(winner);
        result.put(
                "model_versions_count",
                windows.stream()
                        .map(key -> winnerSamples.get(key).path("model_version").asText())
                        .distinct()
                        .count());
        result.put("selection_loss", best);
        result.put("validation_loss", heldOut);
        result.put("baseline_validation_loss", baseline);
        List<Map<String, Object>> scores = new ArrayList<>();
        boolean superior =
                baseline.signum() > 0 && heldOut.compareTo(baseline.multiply(ADVANTAGE)) < 0;
        for (List<String> candidate : candidates) {
            BigDecimal score = loss(samples.get(candidate), validation, field, null);
            scores.add(
                    Map.of(
                            "scheme",
                            candidate.get(0),
                            "selection_policy",
                            candidate.get(1),
                            "selection_loss",
                            loss(samples.get(candidate), selection, field, null),
                            "validation_loss",
                            score));
            if (!candidate.equals(winner)
                    && (score.signum() == 0 || heldOut.compareTo(score.multiply(ADVANTAGE)) >= 0)) {
                superior = false;
            }
        }
        result.put("candidate_scores", scores);
        if (!superior) {
            return stop(result, "NO_VALIDATED_ADVANTAGE", "选择段赢家未在随后留出段同时比简单基线和其他候选至少降低5%误差，暂不推荐");
        }
        result.put("scheme", winner.get(0));
        result.put("model_version", newest.get(winner.get(0)).path("model_version").asText());
        return stop(
                result,
                "AVAILABLE",
                "固定规则滚动方案在30个共同机会中选优，并在随后10个不重叠机会中通过基线与候选优势验证；当前制品仅标识正在使用的模型，不宣称该文件已独立验证40次");
    }

    /** 一个指标必须在所有候选的整个比较窗口内都有效，否则不用它排名。 */
    private static boolean complete(
            List<List<String>> candidates,
            Map<List<String>, Map<String, JsonNode>> samples,
            List<String> keys,
            String field) {
        for (List<String> candidate : candidates) {
            for (String key : keys) {
                BigDecimal value = number(samples.get(candidate).get(key).path("output"), field);
                if (value == null
                        || ("probability_up".equals(field)
                                && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 固定点基线为零收益；概率基线取0.5和选择段上涨频率中留出表现较强的一项。 */
    private static BigDecimal loss(
            Map<String, JsonNode> rows, List<String> keys, String field, BigDecimal baseline) {
        BigDecimal total = BigDecimal.ZERO;
        for (String key : keys) {
            JsonNode row = rows.get(key);
            BigDecimal value = baseline == null ? number(row.path("output"), field) : baseline;
            BigDecimal actual = number(row, "actual_return");
            BigDecimal error =
                    "point_return".equals(field)
                            ? value.subtract(actual).abs()
                            : value.subtract(actual.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO)
                                    .pow(2);
            total = total.add(error);
        }
        return total.divide(BigDecimal.valueOf(keys.size()), MC);
    }

    /** 不完整时间或字段不强行推断；由上层成熟样本适配器先做资格检查。 */
    private static LocalDate date(JsonNode row, String field) {
        try {
            return LocalDate.parse(row.path(field).asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 缺失生成时点的版本不能抢占合法新版本。 */
    private static Instant time(JsonNode row) {
        try {
            return Instant.parse(row.path("generated_at").asText());
        } catch (RuntimeException invalid) {
            return Instant.MIN;
        }
    }

    /** 缺少真实可得时刻的标签不能被日期默认补成当时已知。 */
    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 金额和概率均保持十进制精度，非法内容不转成零。 */
    private static BigDecimal number(JsonNode row, String field) {
        try {
            return new BigDecimal(row.path(field).asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 每个未通过门槛的返回值都提供可显示的原因，不用空对象隐藏不确定性。 */
    private static Map<String, Object> stop(
            Map<String, Object> result, String status, String reason) {
        result.put("status", status);
        result.put("reason", reason);
        return result;
    }
}
