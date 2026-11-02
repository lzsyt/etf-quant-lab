package cn.etfquantlab.forecast;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 沿用费用账本与故障恢复，DeepSeek与GPT分别执行各自的日预算。 */
@Service
public class ForecastDeepSeek {
    private final Store store;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final BigDecimal budget;
    private final BigDecimal gptBudget;

    /** DeepSeek沿用原配置；GPT只做独立预测，历史分歧费用仍计入当日已用预算。 */
    public ForecastDeepSeek(
            Store store,
            QuantClient quant,
            TransactionTemplate tx,
            @Value("${etf.llm-daily-usd:0}") BigDecimal budget,
            @Value("${etf.gpt-daily-usd:0}") BigDecimal gptBudget) {
        this.store = store;
        this.quant = quant;
        this.tx = tx;
        this.budget = budget;
        this.gptBudget = gptBudget;
    }

    /** 相同真实证据跨批次复用原结果，预留或异常账单永不自动发起第二次HTTP。 */
    public JsonNode predict(UUID itemId, JsonNode input) {
        return predict(itemId, input, "DEEPSEEK");
    }

    /** 报价指纹区分研究角色，费用占用和事务锁按供应商隔离。 */
    public JsonNode predict(UUID itemId, JsonNode input, String scheme) {
        // 即使旧调用方绕过计划入口，也必须在查询账本和外部报价之前阻断分歧分析。
        if ("GPT_DISAGREEMENT".equals(scheme)) {
            return outcome("BLOCKED", "GPT_DISAGREEMENT_DISABLED");
        }
        String route =
                switch (scheme) {
                    case "DEEPSEEK" -> "/analysis/forecast";
                    case "GPT" -> "/analysis/gpt";
                    case "GPT_DISAGREEMENT" -> "/analysis/gpt-review";
                    default -> throw new IllegalArgumentException("UNSUPPORTED_AI_SCHEME");
                };
        boolean review = "GPT_DISAGREEMENT".equals(scheme);
        boolean gpt = !"DEEPSEEK".equals(scheme);
        Integer charged =
                store.db.queryForObject(
                        "select count(*) from api_call_cost where id=?", Integer.class, itemId);
        if (charged > 0) return outcome("BLOCKED", "BILLING_ALREADY_RESERVED_OR_UNKNOWN");
        ObjectNode payload = input.deepCopy();
        JsonNode quote = quant.post(route + "/quote", payload);
        String domain = review ? "FORECAST_DISAGREEMENT_REVIEW" : "INDEPENDENT_MARKET_FORECAST";
        if (!domain.equals(quote.path("domain").asText())) {
            throw new IllegalArgumentException("INVALID_FORECAST_DOMAIN");
        }
        for (String field :
                List.of("request_fingerprint", "input_sha256", "evidence_snapshot_id")) {
            if (!quote.path(field).asText().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("INVALID_FORECAST_QUOTE");
        }
        BigDecimal reserve = new BigDecimal(quote.path("reserved_usd").asText());
        if (reserve.signum() < 0) throw new IllegalArgumentException("INVALID_FORECAST_PRICE");
        String fingerprint = quote.path("request_fingerprint").asText();
        var day = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        JsonNode prior =
                tx.execute(
                        status -> {
                            store.lock("forecast-ds-evidence:" + fingerprint);
                            store.lock("llm-budget:" + (gpt ? "gpt:" : "deepseek:") + day);
                            List<JsonNode> existing =
                                    store.db.query(
                                            "select jsonb_build_object('status',i.status,'result_id',i.result_id)::text "
                                                    + "from forecast_run_item i join api_call_cost c on c.id=i.id "
                                                    + "where i.quote->>'request_fingerprint'=? order by c.created_at limit 1",
                                            (rs, n) -> store.decode(rs.getString(1)),
                                            fingerprint);
                            store.db.update(
                                    "update forecast_run_item set quote=?::jsonb,updated_at=now() where id=?",
                                    store.encode(quote),
                                    itemId);
                            if (!existing.isEmpty()) {
                                ObjectNode reuse =
                                        outcome(
                                                existing.get(0).hasNonNull("result_id")
                                                        ? "REUSED"
                                                        : "BLOCKED",
                                                "SAME_EVIDENCE_ALREADY_CONSUMED");
                                reuse.set("result_id", existing.get(0).path("result_id"));
                                return reuse;
                            }
                            if (!quote.path("enabled").asBoolean()
                                    || !quote.path("configured").asBoolean()) {
                                return outcome(
                                        "BLOCKED",
                                        "DEEPSEEK".equals(scheme)
                                                ? "DEEPSEEK_DISABLED_OR_UNCONFIGURED"
                                                : "GPT_DISABLED_OR_PROXY_UNCONFIGURED");
                            }
                            if (!review && quote.path("eligible_source_ids").isEmpty())
                                return outcome("ABSTAIN", "NO_QUALIFIED_NEWS");
                            Timestamp from =
                                    Timestamp.from(
                                            day.atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                                    .toInstant());
                            Timestamp until =
                                    Timestamp.from(
                                            day.plusDays(1)
                                                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                                    .toInstant());
                            BigDecimal used =
                                    store.db.queryForObject(
                                            "select coalesce(sum(case when status='CONFIRMED' then actual_cost else reserved_cost end),0) "
                                                    + "from api_call_cost c where created_at>=? and created_at<? "
                                                    // 旧GPT费用已关联统一执行项，无需回写历史账单即可归属供应商。
                                                    + "and exists (select 1 from forecast_run_item i where i.id=c.id "
                                                    + "and i.scheme in ('GPT','GPT_DISAGREEMENT'))=?",
                                            BigDecimal.class,
                                            from,
                                            until,
                                            gpt);
                            if (used.add(reserve).compareTo(gpt ? gptBudget : budget) > 0)
                                return outcome(
                                        "BLOCKED",
                                        gpt
                                                ? "GPT_DAILY_BUDGET_EXCEEDED"
                                                : "DEEPSEEK_DAILY_BUDGET_EXCEEDED");
                            store.db.update(
                                    "insert into api_call_cost(id,business_key,status,reserved_cost,pricing_snapshot) "
                                            + "values(?,?,'RESERVED',?,?::jsonb)",
                                    itemId,
                                    "FORECAST_PREDICTION:" + fingerprint,
                                    reserve,
                                    store.encode(quote.path("pricing")));
                            return null;
                        });
        if (prior != null) return prior;
        payload.put("reserved_usd", reserve.toPlainString());
        // 报价与执行沿用同一模型和推理强度，设置页保存不会改变本次已经预留的请求。
        if (quote.hasNonNull("model_settings"))
            payload.set("model_settings", quote.path("model_settings"));
        payload.put("expected_input_sha256", quote.path("input_sha256").asText());
        payload.put("expected_evidence_snapshot_id", quote.path("evidence_snapshot_id").asText());
        try {
            JsonNode response = quant.post(route + "/run", payload);
            BigDecimal actual = new BigDecimal(response.path("cost_usd").asText());
            if (actual.signum() < 0) throw new IllegalArgumentException("INVALID_FORECAST_COST");
            boolean matches = true;
            for (String field :
                    List.of("input_sha256", "evidence_snapshot_id", "request_fingerprint")) {
                matches &= quote.path(field).asText().equals(response.path(field).asText());
            }
            store.db.update(
                    "update api_call_cost set status=?,actual_cost=?,input_tokens=?,output_tokens=?,duration_ms=? where id=?",
                    response.path("billing_uncertain").asBoolean(true) ? "UNCERTAIN" : "CONFIRMED",
                    actual,
                    response.path("input_tokens").asInt(),
                    response.path("output_tokens").asInt(),
                    response.path("duration_ms").asLong(),
                    itemId);
            if (!matches) return outcome("FAILED", "INPUT_QUOTE_MISMATCH");
            return response;
        } catch (RuntimeException error) {
            store.db.update("update api_call_cost set status='UNCERTAIN' where id=?", itemId);
            return outcome("FAILED", "API_FAILED_BILLING_UNCERTAIN");
        }
    }

    /** 返回业务状态，不包含异常原文、供应端点或凭据。 */
    private ObjectNode outcome(String status, String reason) {
        ObjectNode result = store.json.createObjectNode();
        result.put("status", status);
        result.put("error_code", reason);
        return result;
    }
}
