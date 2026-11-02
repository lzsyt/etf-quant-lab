package cn.etfquantlab.forward;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ForwardNews {
    private final Store store;
    private final QuantClient quant;
    private final AnalysisService oldNews;
    private final TransactionTemplate tx;
    private final BigDecimal budget;
    private final boolean paidEnabled;

    /** 逐ETF分析与旧分析分表，但共用原费用表和同一每日预算锁，不能重复扩张预算。 */
    public ForwardNews(
            Store store,
            QuantClient quant,
            AnalysisService oldNews,
            TransactionTemplate tx,
            @Value("${etf.llm-daily-usd:0}") BigDecimal budget,
            @Value("${etf.forward-llm-enabled:false}") boolean paidEnabled) {
        this.store = store;
        this.quant = quant;
        this.oldNews = oldNews;
        this.tx = tx;
        this.budget = budget;
        this.paidEnabled = paidEnabled;
    }

    /** 收集既有允许的公开来源，保留源级候选与失败分类。 */
    public JsonNode collect() {
        return oldNews.collect();
    }

    /** 初始化不收费；正式日终只使用普通每日额度，不自动进入额外版本验证通道。 */
    public JsonNode analyze(Instant cutoff, boolean scheduled) {
        return analyze(cutoff, scheduled, false);
    }

    /** 相同请求复用原事实；显式版本验证仅允许不同身份的额外一批，仍共用原费用预算。 */
    public JsonNode analyze(Instant cutoff, boolean scheduled, boolean validateVersion) {
        String taskTrace = trace();
        JsonNode recovered = byTrace(taskTrace);
        if (recovered != null) {
            // 任务已收费后进程崩溃，跨自然日恢复仍沿用原请求，不用新的now再收费。
            return outcome(recovered, true, recovered.path("quote"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cutoff", cutoff.toString());
        payload.put("news", oldNews.candidates(cutoff));
        payload.put("symbols", ForwardRules.SYMBOLS);
        payload.put("horizon", 1);
        ObjectNode quote = (ObjectNode) quant.post("/analysis/forward/quote", payload).deepCopy();
        requireIdentity(quote);
        if ("etf-symbol-evidence-v2.1.2".equals(quote.path("schema_version").asText())) {
            // Python在任何付费请求前核对重新构建的输入，避免报价与执行之间证据快照变化。
            payload.put("expected_input_sha256", quote.path("input_sha256").asText());
            payload.put(
                    "expected_evidence_snapshot_id", quote.path("evidence_snapshot_id").asText());
        }
        String fingerprint = quote.path("request_fingerprint").asText();
        String mode = validateVersion ? "EXPLICIT_VERSION_VALIDATION" : "STANDARD_DAILY";
        quote.put("validation_mode", mode);
        var day = cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        Timestamp dayStart =
                Timestamp.from(day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        Timestamp dayEnd =
                Timestamp.from(
                        day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        boolean allow = paidEnabled && scheduled;
        String key = "forward-news:" + day + ":" + fingerprint + ":" + mode + ":" + allow;
        UUID id = UUID.randomUUID();
        BigDecimal reserve = new BigDecimal(quote.path("reserved_usd").asText());
        JsonNode existing =
                tx.execute(
                        status -> {
                            // 先锁同一任务，再锁北京时间自然日，避免跨日的同任务并发创建两份收费请求。
                            store.lock("llm-task:" + taskTrace);
                            JsonNode sameTask = byTrace(taskTrace);
                            if (sameTask != null) {
                                return sameTask;
                            }
                            store.lock("llm-budget:deepseek:" + day);
                            List<JsonNode> paid =
                                    store.db.query(
                                            "select row_to_json(a)::text from forward_analysis a"
                                                    + " join api_call_cost c on c.id=a.id"
                                                    + " where a.input_cutoff>=? and a.input_cutoff<?"
                                                    + " order by a.created_at desc,a.id",
                                            (rs, row) -> store.decode(rs.getString(1)),
                                            dayStart,
                                            dayEnd);
                            for (JsonNode previous : paid) {
                                if (fingerprint.equals(
                                        previous.path("quote")
                                                .path("request_fingerprint")
                                                .asText())) {
                                    // SUCCEEDED、FAILED、RUNNING和不确定费用都保持原记录，不因新任务再次调用。
                                    rememberTaskAnalysis(taskTrace, previous);
                                    return previous;
                                }
                            }
                            BigDecimal used =
                                    store.db.queryForObject(
                                            "select coalesce(sum(case when status='CONFIRMED' then "
                                                    + "actual_cost else reserved_cost end),0)"
                                                    + " from api_call_cost c where created_at>=? and created_at<?"
                                                    // DeepSeek各业务仍共享额度，GPT独立及分歧费用不计入。
                                                    + " and not exists (select 1 from forecast_run_item i where i.id=c.id"
                                                    + " and i.scheme in ('GPT','GPT_DISAGREEMENT'))",
                                            BigDecimal.class,
                                            dayStart,
                                            dayEnd);
                            String reason = eligibilityReason(quote, paid, allow, validateVersion);
                            if (reason == null && used.add(reserve).compareTo(budget) > 0) {
                                reason = "DAILY_BUDGET_EXCEEDED";
                            }
                            if (!paid.isEmpty()) {
                                quote.put("prior_analysis_id", paid.get(0).path("id").asText());
                            }
                            if (reason != null) {
                                List<JsonNode> blocked =
                                        store.db.query(
                                                "select row_to_json(a)::text from forward_analysis a"
                                                        + " where business_key=? and status='MISSING' and error_code=?"
                                                        + " order by created_at desc limit 1",
                                                (rs, row) -> store.decode(rs.getString(1)),
                                                key,
                                                reason);
                                if (!blocked.isEmpty()) {
                                    rememberTaskAnalysis(taskTrace, blocked.get(0));
                                    return blocked.get(0);
                                }
                            }
                            store.db.update(
                                    "insert into forward_analysis(id,business_key,input_cutoff,"
                                            + "status,trace_id,quote,input_snapshot,error_code)"
                                            + " values(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                                    id,
                                    key,
                                    Timestamp.from(cutoff),
                                    reason == null ? "RUNNING" : "MISSING",
                                    taskTrace,
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
            return outcome(
                    existing,
                    !id.toString().equals(existing.path("id").asText()),
                    taskTrace.equals(existing.path("trace_id").asText())
                            ? existing.path("quote")
                            : quote);
        }
        payload.put("reserved_usd", reserve.toPlainString());
        // 报价已冻结本次模型和推理强度，不能在执行时重新读取设置页当前值。
        if (quote.hasNonNull("model_settings"))
            payload.put("model_settings", quote.path("model_settings"));
        try {
            ObjectNode response =
                    (ObjectNode) quant.post("/analysis/forward/run", payload).deepCopy();
            List<String> mismatches = identityMismatches(quote, response);
            response.set("quote_identity_mismatches", store.json.valueToTree(mismatches));
            response.put("quote_identity_verified", mismatches.isEmpty());
            boolean rejectSuccess =
                    "SUCCEEDED".equals(response.path("status").asText()) && !mismatches.isEmpty();
            tx.executeWithoutResult(
                    status -> {
                        // 原始模型响应和实际费用仍保留；身份不符只阻止该结果进入策略，不能抹掉收费证据。
                        store.db.update(
                                "update forward_analysis set status=?,result=?::jsonb,error_code=? where id=?",
                                rejectSuccess ? "FAILED" : response.path("status").asText("FAILED"),
                                store.encode(response),
                                rejectSuccess
                                        ? "INPUT_QUOTE_MISMATCH"
                                        : response.path("error_code").asText(null),
                                id);
                        store.db.update(
                                "update api_call_cost set status=?,actual_cost=?,"
                                        + "input_tokens=?,output_tokens=?,duration_ms=? where id=?",
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
            store.db.update(
                    "update forward_analysis set status='FAILED',error_code='API_FAILED' where id=?",
                    id);
            store.audit(
                    "FORWARD_ANALYSIS_FAILED",
                    Map.of("analysis_id", id, "reason", ForwardModels.safeReason(error)));
        }
        return outcome(get(id), false, quote);
    }

    /** 每日调用次数与费用是两道独立门禁，无新闻或无授权先拒绝，不能消耗验证额度。 */
    private String eligibilityReason(
            JsonNode quote, List<JsonNode> paid, boolean allow, boolean validateVersion) {
        if (quote.path("eligible_source_ids").isEmpty()) {
            return "NO_RELEVANT_NEWS";
        }
        if (!allow) {
            return "REAL_CALL_PENDING";
        }
        if (!quote.path("enabled").asBoolean() || !quote.path("configured").asBoolean()) {
            return "PROVIDER_DISABLED_OR_KEY_MISSING";
        }
        if (!validateVersion) {
            return paid.isEmpty() ? null : "DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED";
        }
        if (paid.size() >= 2
                || paid.stream()
                        .anyMatch(
                                previous ->
                                        "EXPLICIT_VERSION_VALIDATION"
                                                .equals(
                                                        previous.path("quote")
                                                                .path("validation_mode")
                                                                .asText()))) {
            return "DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED";
        }
        if (paid.isEmpty()
                || paid.stream()
                        .noneMatch(previous -> changedIdentity(previous.path("quote"), quote))) {
            return "EXPLICIT_VALIDATION_REQUIRES_CHANGED_REQUEST";
        }
        return null;
    }

    /** 新记录使用稳定指纹；旧记录没有指纹时只承认能由已保存版本字段证实的变化。 */
    private boolean changedIdentity(JsonNode previous, JsonNode current) {
        String oldFingerprint = previous.path("request_fingerprint").asText();
        if (!oldFingerprint.isBlank()) {
            return !oldFingerprint.equals(current.path("request_fingerprint").asText());
        }
        for (String field :
                List.of("prompt_version", "schema_version", "provider", "model", "model_version")) {
            String oldValue = previous.path(field).asText();
            if (!oldValue.isBlank() && !oldValue.equals(current.path(field).asText())) {
                return true;
            }
        }
        return false;
    }

    /** 缺少稳定身份或精确输入摘要时禁止收费，不能降级为按自然日猜测请求相同。 */
    private void requireIdentity(JsonNode quote) {
        for (String field : List.of("request_fingerprint", "input_sha256")) {
            if (!quote.path(field).asText().matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("新闻报价缺少有效请求身份：" + field);
            }
        }
        for (String field :
                List.of("prompt_version", "schema_version", "provider", "model", "model_version")) {
            if (quote.path(field).asText().isBlank()) {
                throw new IllegalArgumentException("新闻报价缺少版本字段：" + field);
            }
        }
        // 证据协议由Python生成；旧版没有这两个字段，不能因升级而拒绝读取旧冻结分析。
        if ("etf-symbol-evidence-v2.1.2".equals(quote.path("schema_version").asText())
                || quote.has("evidence_version")
                || quote.has("evidence_snapshot_id")) {
            for (String field : List.of("evidence_version", "evidence_snapshot_id")) {
                if (!quote.path(field).isTextual() || quote.path(field).asText().isBlank()) {
                    throw new IllegalArgumentException("新闻报价缺少证据身份：" + field);
                }
            }
            if (!quote.path("evidence_snapshot_id").asText().matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("新闻报价缺少有效证据身份：evidence_snapshot_id");
            }
        }
    }

    /** 实际运行必须使用本次冻结报价的输入和配置；供应商返回别名另存，不混作请求模型。 */
    private List<String> identityMismatches(JsonNode quote, JsonNode response) {
        List<String> mismatches = new ArrayList<>();
        for (String field :
                List.of(
                        "request_fingerprint",
                        "prompt_version",
                        "schema_version",
                        "model_version",
                        "input_sha256")) {
            if (!quote.path(field).asText().equals(response.path(field).asText())) {
                mismatches.add(field);
            }
        }
        if (!quote.path("model").asText().equals(response.path("request_model").asText())) {
            mismatches.add("model");
        }
        // 新版的证据清单必须属于报价时冻结的快照；旧协议双方均不带字段时保持兼容。
        for (String field : List.of("evidence_version", "evidence_snapshot_id")) {
            if ((quote.has(field) || response.has(field))
                    && !quote.path(field).asText().equals(response.path(field).asText())) {
                mismatches.add(field);
            }
        }
        return mismatches;
    }

    /** 创建者trace或复用任务的持久引用都可恢复，不能因任务尚未写完最终结果而再次收费。 */
    private JsonNode byTrace(String taskTrace) {
        List<JsonNode> records =
                store.db.query(
                        "select row_to_json(a)::text from forward_analysis a"
                                + " where trace_id=? or exists (select 1 from task_execution t"
                                + " where t.id::text=? and a.id::text=coalesce("
                                + "t.result->>'forward_analysis_id',t.result#>>'{analysis,id}'))"
                                + " order by created_at limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        taskTrace,
                        taskTrace);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 复用别的任务分析时先在同一事务保存关联；队列最终结果仍保存analysis.id并正常覆盖此中间结果。 */
    private void rememberTaskAnalysis(String taskTrace, JsonNode analysis) {
        store.db.update(
                "update task_execution set result=coalesce(result,'{}'::jsonb)||?::jsonb"
                        + " where id::text=? and status='RUNNING'",
                store.encode(Map.of("forward_analysis_id", analysis.path("id").asText())),
                taskTrace);
    }

    /** 仅给返回投影添加复用与验证结论，不更新历史记录或把任务完成冒称分析成功。 */
    private JsonNode outcome(JsonNode analysis, boolean reused, JsonNode requestedQuote) {
        ObjectNode result = (ObjectNode) analysis.deepCopy();
        String status = analysis.path("status").asText();
        String value =
                switch (status) {
                    case "SUCCEEDED" ->
                            "REAL".equals(analysis.path("result").path("nature").asText())
                                    ? "SUCCEEDED"
                                    : "MOCK_ONLY";
                    case "RUNNING" -> "IN_PROGRESS";
                    case "MISSING" -> "NOT_VALIDATED";
                    default -> "FAILED";
                };
        result.put("reused", reused);
        result.put("validation_outcome", reused ? "REUSED_" + value : value);
        ObjectNode identity = store.json.createObjectNode();
        for (String field :
                List.of(
                        "request_fingerprint",
                        "prompt_version",
                        "schema_version",
                        "provider",
                        "model",
                        "model_version",
                        "input_sha256")) {
            identity.set(field, requestedQuote.path(field));
        }
        for (String field : List.of("evidence_version", "evidence_snapshot_id")) {
            if (requestedQuote.has(field)) {
                identity.set(field, requestedQuote.path(field));
            }
        }
        result.set("requested_identity", identity);
        if (analysis.path("quote").has("prior_analysis_id")) {
            result.set("prior_analysis_id", analysis.path("quote").path("prior_analysis_id"));
        }
        return result;
    }

    /** 按ETF取得同一次冻结分析；过期、不相关或结构非法一律保留明确缺失原因。 */
    public JsonNode item(JsonNode analysis, String symbol, Instant cutoff) {
        String reason = analysis.path("error_code").asText("API_FAILED");
        if ("SUCCEEDED".equals(analysis.path("status").asText())
                && "REAL".equals(analysis.path("result").path("nature").asText())) {
            Instant analyzed = Instant.parse(analysis.path("input_cutoff").asText());
            if (!analyzed.isAfter(cutoff) && analyzed.plusSeconds(86400).isAfter(cutoff)) {
                for (JsonNode item : analysis.path("result").path("result").path("items")) {
                    if (symbol.equals(item.path("symbol").asText())) {
                        return item;
                    }
                }
                reason = "INVALID_OUTPUT";
            } else {
                reason = "NEWS_EXPIRED";
            }
        } else if ("SUCCEEDED".equals(analysis.path("status").asText())) {
            reason = "NON_REAL_ANALYSIS_REJECTED";
        }
        return store.json.valueToTree(
                Map.of("symbol", symbol, "horizon", 1, "status", "MISSING", "error_code", reason));
    }

    /** 返回本次输入和响应证据，无任何服务端凭据。 */
    public JsonNode get(UUID id) {
        return store.db.queryForObject(
                "select row_to_json(a)::text from forward_analysis a where id=?",
                (rs, row) -> store.decode(rs.getString(1)),
                id);
    }

    /** trace沿任务上下文传播，人工调用也产生可追溯标识。 */
    private String trace() {
        String trace = MDC.get("trace_id");
        return trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
    }
}
