package cn.etfquantlab.gold;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GoldNews {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final Store store;
    private final QuantClient quant;
    private final AnalysisService news;
    private final TransactionTemplate tx;
    private final BigDecimal budget;
    private final boolean enabled;

    @Value("${etf.gold-enabled:true}")
    private boolean moduleEnabled = true;

    /** 黄金不新增日预算，只在原ETF日终之后使用同一费用锁下的剩余额度。 */
    public GoldNews(
            Store store,
            QuantClient quant,
            AnalysisService news,
            TransactionTemplate tx,
            @Value("${etf.llm-daily-usd:0}") BigDecimal budget,
            @Value("${etf.gold-llm-enabled:false}") boolean enabled) {
        this.store = store;
        this.quant = quant;
        this.news = news;
        this.tx = tx;
        this.budget = budget;
        this.enabled = enabled;
    }

    /** 仅显式持久任务可调用；同任务崩溃恢复沿用原快照，不产生第二次收费。 */
    public JsonNode analyze(Instant cutoff) {
        if (!moduleEnabled) {
            return store.json.valueToTree(
                    Map.of("status", "MISSING", "error_code", "GOLD_DISABLED"));
        }
        String trace = MDC.get("trace_id");
        if (trace == null) {
            throw new IllegalArgumentException("黄金解释必须由持久任务执行");
        }
        List<JsonNode> prior = rows("where trace_id=? order by created_at limit 1", trace);
        if (!prior.isEmpty()) {
            return prior.get(0);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cutoff", cutoff.toString());
        payload.put("news", news.candidates(cutoff));
        JsonNode quote = quant.post("/analysis/gold/quote", payload);
        requireQuote(quote);
        payload.put("expected_input_sha256", quote.path("input_sha256").asText());
        payload.put("expected_evidence_snapshot_id", quote.path("evidence_snapshot_id").asText());
        LocalDate day = cutoff.atZone(BEIJING).toLocalDate();
        LocalDate week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String key =
                "GOLD_LONG_TERM:explanation:"
                        + week
                        + ":"
                        + quote.path("request_fingerprint").asText();
        Timestamp from = Timestamp.from(day.atStartOfDay(BEIJING).toInstant());
        Timestamp until = Timestamp.from(day.plusDays(1).atStartOfDay(BEIJING).toInstant());
        Timestamp weekStart = Timestamp.from(week.atStartOfDay(BEIJING).toInstant());
        UUID id = UUID.randomUUID();
        BigDecimal reserve = new BigDecimal(quote.path("reserved_usd").asText());
        JsonNode existing =
                tx.execute(
                        status -> {
                            store.lock("gold-news-task:" + trace);
                            store.lock("llm-budget:deepseek:" + day);
                            List<JsonNode> found =
                                    rows(
                                            "where trace_id=? or business_key=? order by created_at limit 1",
                                            trace,
                                            key);
                            if (!found.isEmpty()) {
                                return found.get(0);
                            }
                            String reason =
                                    blockedReason(quote, cutoff, from, until, weekStart, reserve);
                            store.db.update(
                                    "insert into gold_analysis(id,business_key,trace_id,input_cutoff,"
                                            + "status,quote,input_snapshot,error_code)"
                                            + " values(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                                    id,
                                    key,
                                    trace,
                                    Timestamp.from(cutoff),
                                    reason == null ? "RUNNING" : "MISSING",
                                    store.encode(quote),
                                    store.encode(payload),
                                    reason);
                            if (reason != null) {
                                return get(id);
                            }
                            store.db.update(
                                    "insert into api_call_cost(id,business_key,status,reserved_cost,pricing_snapshot)"
                                            + " values(?,?,'RESERVED',?,?::jsonb)",
                                    id,
                                    key,
                                    reserve,
                                    store.encode(quote.path("pricing")));
                            return null;
                        });
        if (existing != null) {
            return existing;
        }
        payload.put("reserved_usd", reserve.toPlainString());
        // 报价已冻结本次模型和推理强度，不能在执行时重新读取设置页当前值。
        if (quote.hasNonNull("model_settings"))
            payload.put("model_settings", quote.path("model_settings"));
        try {
            JsonNode response = quant.post("/analysis/gold/run", payload);
            boolean verified = identityMatches(quote, response);
            tx.executeWithoutResult(
                    status -> {
                        store.db.update(
                                "update gold_analysis set status=?,result=?::jsonb,error_code=? where id=?",
                                verified ? response.path("status").asText("FAILED") : "FAILED",
                                store.encode(response),
                                verified
                                        ? response.path("error_code").asText(null)
                                        : "INPUT_QUOTE_MISMATCH",
                                id);
                        store.db.update(
                                "update api_call_cost set status=?,actual_cost=?,input_tokens=?,"
                                        + "output_tokens=?,duration_ms=? where id=?",
                                response.path("billing_uncertain").asBoolean(true)
                                        ? "UNCERTAIN"
                                        : "CONFIRMED",
                                new BigDecimal(response.path("cost_usd").asText("0")),
                                response.path("input_tokens").asInt(),
                                response.path("output_tokens").asInt(),
                                response.path("duration_ms").asLong(),
                                id);
                    });
        } catch (RuntimeException error) {
            // 不确定是否已收费时保留RESERVED占用，不重放请求或清空费用。
            store.db.update(
                    "update gold_analysis set status='FAILED',error_code='API_FAILED' where id=?",
                    id);
            store.audit(
                    "GOLD_EXPLANATION_FAILED",
                    Map.of("id", id, "reason", error.getClass().getSimpleName()));
        }
        return get(id);
    }

    /** ETF先执行；黄金每周至多一批且最多一次HTTP，预算不足保留明确缺失状态。 */
    private String blockedReason(
            JsonNode quote,
            Instant cutoff,
            Timestamp from,
            Timestamp until,
            Timestamp weekStart,
            BigDecimal reserve) {
        if (quote.path("eligible_source_ids").isEmpty()) {
            return "NO_QUALIFIED_GOLD_NEWS";
        }
        if (!enabled
                || !quote.path("enabled").asBoolean()
                || !quote.path("configured").asBoolean()) {
            return "EXPLANATION_DISABLED";
        }
        if (cutoff.atZone(BEIJING).getHour() < 20) {
            return "ETF_PRIORITY_WINDOW";
        }
        Integer pending =
                store.db.queryForObject(
                        "select count(*) from task_execution where task_type in ('EOD','FORWARD_EOD','FORWARD_INIT')"
                                + " and status in ('QUEUED','RUNNING','FAILED') and updated_at>=? and updated_at<?",
                        Integer.class,
                        from,
                        until);
        if (pending > 0) {
            return "ETF_PRIORITY_PENDING";
        }
        Integer paid =
                store.db.queryForObject(
                        "select count(*) from gold_analysis a join api_call_cost c on c.id=a.id where a.created_at>=?",
                        Integer.class,
                        weekStart);
        if (paid > 0) {
            return "GOLD_WEEKLY_CALL_LIMIT";
        }
        BigDecimal used =
                store.db.queryForObject(
                        "select coalesce(sum(case when status='CONFIRMED' then actual_cost else reserved_cost end),0)"
                                + " from api_call_cost c where created_at>=? and created_at<?"
                                // DeepSeek各业务仍共享额度，GPT独立及分歧费用不计入。
                                + " and not exists (select 1 from forecast_run_item i where i.id=c.id"
                                + " and i.scheme in ('GPT','GPT_DISAGREEMENT'))",
                        BigDecimal.class,
                        from,
                        until);
        return used.add(reserve).compareTo(budget) > 0 ? "DEEPSEEK_DAILY_BUDGET_EXCEEDED" : null;
    }

    /** 缺少明确黄金域或固定输入身份时零费用拒绝，绝不沿用ETF报价。 */
    private void requireQuote(JsonNode quote) {
        if (!"GOLD_LONG_TERM".equals(quote.path("domain").asText())) {
            throw new IllegalArgumentException("黄金解释业务域不匹配");
        }
        for (String field :
                List.of("request_fingerprint", "input_sha256", "evidence_snapshot_id")) {
            if (!quote.path(field).asText().matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("黄金解释输入身份不完整");
            }
        }
    }

    /** 结果身份必须完整对应已冻结报价，模型响应内容不能更改数值预测。 */
    private boolean identityMatches(JsonNode quote, JsonNode response) {
        for (String field :
                List.of(
                        "domain",
                        "prompt_version",
                        "schema_version",
                        "model_version",
                        "request_fingerprint",
                        "input_sha256",
                        "evidence_snapshot_id",
                        "evidence_version")) {
            if (!quote.path(field).asText().equals(response.path(field).asText())) {
                return false;
            }
        }
        return quote.path("model").asText().equals(response.path("request_model").asText());
    }

    /** 只读页面展示最新尝试和原有效日期，失败时不会冒充今日解释。 */
    public JsonNode latest() {
        List<JsonNode> found = rows("order by created_at desc limit 1");
        if (found.isEmpty()) {
            return store.json.valueToTree(
                    Map.of(
                            "status",
                            "MISSING",
                            "reason",
                            "解释暂不可用：尚无合格黄金证据",
                            "error_code",
                            "NO_QUALIFIED_GOLD_NEWS",
                            "affects_numeric_prediction",
                            false));
        }
        ObjectNode result = (ObjectNode) found.get(0).deepCopy();
        result.put("affects_numeric_prediction", false);
        result.put("reason", "解释与数值预测独立；信息不足时不生成方向、目标价或概率");
        if (!"SUCCEEDED".equals(result.path("status").asText())) {
            List<JsonNode> successful =
                    rows("where status='SUCCEEDED' order by created_at desc limit 1");
            if (!successful.isEmpty()) {
                result.set("last_success", successful.get(0));
            }
        }
        String until = result.path("result").path("valid_until").asText();
        if (!until.isBlank() && Instant.parse(until).isBefore(Instant.now())) {
            result.put("display_status", "STALE");
        } else {
            result.put("display_status", result.path("status").asText());
        }
        return result;
    }

    /** 查询精确记录，不在读取时触发新闻采集或外部调用。 */
    private JsonNode get(UUID id) {
        return rows("where id=?", id).get(0);
    }

    /** 固定内部SQL片段，参数全部绑定；输入快照和费用身份保持独立可追溯。 */
    private List<JsonNode> rows(String suffix, Object... args) {
        return store.db.query(
                "select row_to_json(a)::text from gold_analysis a " + suffix,
                (rs, row) -> store.decode(rs.getString(1)),
                args);
    }
}
