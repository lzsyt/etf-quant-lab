package cn.etfquantlab.forward;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ForwardView {
    private final Store store;
    private final ForwardService forward;

    /** 页面只查询事实库，Python或DeepSeek暂不可用不影响已有证据查看。 */
    public ForwardView(Store store, ForwardService forward) {
        this.store = store;
        this.forward = forward;
    }

    /** 三个分区共用同批次快照，历史账户和模拟样本不会混入新实验排名。 */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", forward.enabled());
        result.put("experiment", forward.experiment());
        result.put("next_schedule", nextSchedule());
        List<JsonNode> health =
                store.db.query(
                        "select row_to_json(h)::text from forward_health h order by stage",
                        (rs, row) -> store.decode(rs.getString(1)));
        health.forEach(row -> ((ObjectNode) row).put("next_scheduled_at", nextSchedule()));
        health.forEach(this::removeNewsContent);
        result.put("health", health);
        List<JsonNode> news =
                store.db.query(
                        "select row_to_json(a)::text from forward_analysis a order by created_at desc limit 7",
                        (rs, row) -> store.decode(rs.getString(1)));
        news.forEach(this::projectNewsEvidence);
        result.put("news", news);
        result.put(
                "models",
                store.db.query(
                        "select (metadata - 'oos_predictions')::text from "
                                + "forward_model order by symbol,algorithm,available_at",
                        (rs, row) -> store.decode(rs.getString(1))));
        List<JsonNode> accounts = forward.accounts();
        for (JsonNode account : accounts) {
            List<JsonNode> settlements =
                    store.db.query(
                            "select row_to_json(s)::text from forward_settlement s "
                                    + "where account_id=? order by trade_date",
                            (rs, row) -> store.decode(rs.getString(1)),
                            account.path("id").asText());
            List<JsonNode> curve =
                    settlements.stream().map(row -> row.path("details").path("snapshot")).toList();
            ((ObjectNode) account).set("settlements", store.json.valueToTree(settlements));
            ((ObjectNode) account).set("curve", store.json.valueToTree(curve));
            ((ObjectNode) account)
                    .set("metrics", store.json.valueToTree(metrics(account, settlements)));
        }
        result.put("accounts", accounts);
        List<JsonNode> decisions =
                store.db.query(
                        "select (to_jsonb(d)||jsonb_build_object('plan',p.details,"
                                + "'actual',a.details,'execution',s.details))::text"
                                + " from forward_decision d join forward_plan p on p.id=d.plan_id"
                                + " left join forward_actual a on a.decision_id=d.id"
                                + " left join forward_settlement s on "
                                + "s.account_id=d.account_id and s.trade_date=d.entry_date"
                                + " where d.experiment_id=? and d.signal_date=(select "
                                + "max(signal_date) from forward_decision where "
                                + "experiment_id=?)"
                                + " order by d.account_id,d.symbol",
                        (rs, row) -> store.decode(rs.getString(1)),
                        ForwardService.EXPERIMENT,
                        ForwardService.EXPERIMENT);
        result.put("decisions", decisions);
        result.put("paired", paired());
        return result;
    }

    /** 只从顶层分析的冻结quote还原公开目录，模型结果中嵌套伪造的quote也不能成为来源。 */
    private void projectNewsEvidence(JsonNode node) {
        JsonNode catalog = node.path("quote").path("evidence_catalog");
        ArrayNode publicEvidence =
                catalog.isArray()
                        ? publicEvidence(catalog, node.path("input_snapshot").path("news"))
                        : null;
        removeNewsContent(node);
        if (publicEvidence != null) {
            ((ObjectNode) node.path("quote")).set("evidence_catalog", publicEvidence);
        }
    }

    /** 对外只返回新闻摘要与审计元数据，完整正文、原始提示和未投影目录仅留冻结库。 */
    private void removeNewsContent(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(List.of("content", "input_text", "evidence_catalog"));
            object.elements().forEachRemaining(this::removeNewsContent);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::removeNewsContent);
        }
    }

    /** 证据ID不变，完整片段仅留冻结库；公开短片段只能来自同一快照中已准入的官方来源。 */
    private ArrayNode publicEvidence(JsonNode catalog, JsonNode sources) {
        ArrayNode result = store.json.createArrayNode();
        for (JsonNode evidence : catalog) {
            if (!evidence.isObject()) {
                continue;
            }
            ObjectNode item = evidence.deepCopy();
            String text = item.path("text").asText("");
            item.remove(List.of("text", "display_text", "display_truncated", "display_status"));
            boolean approved = false;
            for (JsonNode source : sources) {
                if (source.path("id").asText().equals(item.path("source_id").asText())) {
                    approved = approvedOfficialSource(source);
                    break;
                }
            }
            if (!approved) {
                item.put("display_status", "SOURCE_NOT_APPROVED");
            } else if (text.isBlank()) {
                item.put("display_status", "EVIDENCE_TEXT_MISSING");
            } else {
                int length = text.codePointCount(0, text.length());
                item.put(
                        "display_text",
                        text.substring(0, text.offsetByCodePoints(0, Math.min(length, 240))));
                item.put("display_truncated", length > 240);
                item.put("display_status", "DISPLAYABLE");
            }
            result.add(item);
        }
        return result;
    }

    /** 同时核对冻结来源名与官方域名，模型输出中的来源标签不能授予原文展示权限。 */
    private boolean approvedOfficialSource(JsonNode source) {
        String domain =
                switch (source.path("source_name").asText()) {
                    case "CSRC" -> "csrc.gov.cn";
                    case "NBS" -> "stats.gov.cn";
                    case "MOF" -> "mof.gov.cn";
                    default -> "";
                };
        try {
            URI uri = URI.create(source.path("source_url").asText());
            String host = uri.getHost();
            return !domain.isEmpty()
                    && host != null
                    && ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && (host.equalsIgnoreCase(domain)
                            || host.toLowerCase(java.util.Locale.ROOT).endsWith("." + domain));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 只展示下一已确认开市日17:30，日历覆盖缺失时返回空值。 */
    public String nextSchedule() {
        Instant now = Instant.now();
        List<LocalDate> days =
                store.db.query(
                        "select trade_date from trading_calendar where is_open"
                                + " and (trade_date+time '17:30') at time zone "
                                + "'Asia/Shanghai'>? order by trade_date limit 1",
                        (rs, row) -> rs.getDate(1).toLocalDate(),
                        java.sql.Timestamp.from(now));
        return days.isEmpty()
                ? null
                : days.get(0)
                        .atTime(17, 30)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toString();
    }

    /** 前瞻账户指标只用已结算交易日，无数据不画演示曲线且不放大短期年化。 */
    private Map<String, Object> metrics(JsonNode account, List<JsonNode> settlements) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        BigDecimal initial = decimal(account.path("initial_capital"));
        BigDecimal equity = decimal(account.path("state").path("equity"));
        BigDecimal peak = initial;
        BigDecimal drawdown = BigDecimal.ZERO;
        BigDecimal positionSum = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal equitySum = initial;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal slippage = BigDecimal.ZERO;
        int fills = 0;
        for (JsonNode settlement : settlements) {
            JsonNode snapshot = settlement.path("details").path("snapshot");
            BigDecimal value = decimal(snapshot.path("equity"));
            peak = peak.max(value);
            drawdown =
                    drawdown.max(
                            BigDecimal.ONE.subtract(value.divide(peak, MathContext.DECIMAL64)));
            equitySum = equitySum.add(value);
            if (value.signum() > 0) {
                positionSum =
                        positionSum.add(
                                decimal(snapshot.path("position_value"))
                                        .divide(value, MathContext.DECIMAL64));
            }
            for (JsonNode fill : settlement.path("details").path("fills")) {
                fills++;
                fees = fees.add(decimal(fill.path("fee")));
                slippage = slippage.add(decimal(fill.path("slippage_cost")));
                gross =
                        gross.add(
                                decimal(fill.path("quantity"))
                                        .multiply(decimal(fill.path("price"))));
            }
        }
        metrics.put(
                "total_return",
                equity.divide(initial, MathContext.DECIMAL64).subtract(BigDecimal.ONE));
        metrics.put("max_drawdown", drawdown);
        metrics.put("fees", fees);
        metrics.put("commissions", fees);
        metrics.put("slippage_cost", slippage);
        metrics.put("transaction_cost", fees.add(slippage));
        metrics.put("fill_count", fills);
        metrics.put("effective_days", settlements.size());
        metrics.put(
                "average_position",
                settlements.isEmpty()
                        ? null
                        : positionSum.divide(
                                BigDecimal.valueOf(settlements.size()), MathContext.DECIMAL64));
        metrics.put(
                "turnover",
                gross.divide(
                        equitySum.divide(
                                BigDecimal.valueOf(settlements.size() + 1L), MathContext.DECIMAL64),
                        MathContext.DECIMAL64));
        metrics.put("evaluation_status", settlements.size() < 150 ? "尚不足以评价" : "已有前瞻样本，仍需统计检验");
        metrics.put("return_boundary", "公司行动覆盖未完全核实；非已验证完整总回报");
        return metrics;
    }

    /** C/D按同一日期和同一ETF配对，同时按独立交易日期报告样本量。 */
    private Map<String, Object> paired() {
        List<JsonNode> rows =
                store.db.query(
                        "select row_to_json(d)::text from forward_decision d where experiment_id=?"
                                + " and details->>'strategy' in ('LGBM_MATCHED_CONTROL',"
                                + "'FUSION_EXPERIMENT') order by signal_date,symbol",
                        (rs, row) -> store.decode(rs.getString(1)),
                        ForwardService.EXPERIMENT);
        Map<String, JsonNode> controls = new HashMap<>();
        Set<String> dates = new HashSet<>();
        Set<String> validDates = new HashSet<>();
        Set<String> fallbackDates = new HashSet<>();
        Map<String, Set<String>> reasons = new HashMap<>();
        List<Object> differences = new ArrayList<>();
        int buys = 0;
        int reductions = 0;
        int vetoes = 0;
        for (JsonNode row : rows) {
            if ("LGBM_MATCHED_CONTROL".equals(row.path("details").path("strategy").asText())) {
                controls.put(
                        row.path("signal_date").asText() + ":" + row.path("symbol").asText(), row);
            }
        }
        for (JsonNode row : rows) {
            JsonNode detail = row.path("details");
            if (!"FUSION_EXPERIMENT".equals(detail.path("strategy").asText())) {
                continue;
            }
            String day = row.path("signal_date").asText();
            dates.add(day);
            if ("ML_FALLBACK".equals(detail.path("effective_mode").asText())) {
                fallbackDates.add(day);
                reasons.computeIfAbsent(
                                detail.path("fallback_reason").asText(), key -> new HashSet<>())
                        .add(day);
            }
            if ("NEWS_RISK_VETO".equals(detail.path("reason_code").asText())) {
                vetoes++;
            }
            JsonNode control = controls.get(day + ":" + row.path("symbol").asText());
            if (control == null || !detail.path("news_available").asBoolean()) {
                continue;
            }
            validDates.add(day);
            JsonNode c = control.path("details");
            if (c.path("eligible").asBoolean() != detail.path("eligible").asBoolean()) {
                buys++;
            }
            if (detail.hasNonNull("target_weight")
                    && c.hasNonNull("target_weight")
                    && decimal(detail.path("target_weight"))
                                    .compareTo(decimal(c.path("target_weight")))
                            < 0) {
                reductions++;
            }
            differences.add(
                    Map.of(
                            "date",
                            day,
                            "symbol",
                            row.path("symbol").asText(),
                            "control",
                            c,
                            "fusion",
                            detail));
        }
        Map<String, Integer> reasonCounts = new HashMap<>();
        reasons.forEach((reason, days) -> reasonCounts.put(reason, days.size()));
        return Map.of(
                "independent_dates",
                dates.size(),
                "valid_news_dates",
                validDates.size(),
                "signal_differences",
                differences,
                "buy_decision_changes",
                buys,
                "reduction_decision_changes",
                reductions,
                "risk_vetoes",
                vetoes,
                "fallback_days",
                fallbackDates.size(),
                "fallback_reasons",
                reasonCounts);
    }

    /** 金额及仓位统计保留十进制，不向资金账本写回浮点计算。 */
    private BigDecimal decimal(JsonNode node) {
        return node.isMissingNode() || node.isNull()
                ? BigDecimal.ZERO
                : new BigDecimal(node.asText());
    }
}
