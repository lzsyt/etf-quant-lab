package cn.etfquantlab.prediction;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalysisService {
    private final Store store;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final BigDecimal dailyLimit;
    private final BigDecimal gptDailyLimit;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 默认费用上限为零，必须通过受保护部署配置明确启用付费能力。 */
    public AnalysisService(
            Store store,
            QuantClient quant,
            TransactionTemplate tx,
            @Value("${etf.llm-daily-usd:0}") BigDecimal dailyLimit,
            @Value("${etf.gpt-daily-usd:0}") BigDecimal gptDailyLimit) {
        this.store = store;
        this.quant = quant;
        this.tx = tx;
        this.dailyLimit = dailyLimit;
        this.gptDailyLimit = gptDailyLimit;
    }

    /** 新闻按内容快照追加；重抓不改首次获取时间，不制造虚假历史可用证据。 */
    public JsonNode collect() {
        JsonNode result;
        try {
            result = quant.post("/news/collect", Map.of());
        } catch (RuntimeException error) {
            // 整体采集不可用与各来源空结果分开记录，不伪造逐来源抓取成功。
            store.audit(
                    "NEWS_COLLECT_FAILED",
                    Map.of("status", "FAILED", "error_code", error.getClass().getSimpleName()));
            throw error;
        }
        tx.executeWithoutResult(
                status -> {
                    store.lock("news:collect");
                    for (JsonNode item : result.path("items")) {
                        if (store.db.queryForObject(
                                        "select count(*) from news_item where id=?",
                                        Integer.class,
                                        item.path("id").asText())
                                > 0) {
                            continue;
                        }
                        store.db.update(
                                "insert into news_item(id,title,summary,source_url,source_name,"
                                        + "published_at,fetched_at,nature,publication_verified,content_sha256)"
                                        + " values(?,?,?,?,?,?,?,?,?,?)",
                                item.path("id").asText(),
                                item.path("title").asText(),
                                item.path("summary").asText(),
                                item.path("source_url").asText(),
                                item.path("source_name").asText(),
                                Timestamp.from(
                                        java.time.OffsetDateTime.parse(
                                                        item.path("published_at").asText())
                                                .toInstant()),
                                Timestamp.from(
                                        java.time.OffsetDateTime.parse(
                                                        item.path("fetched_at").asText())
                                                .toInstant()),
                                item.path("nature").asText(),
                                item.path("publication_verified").asBoolean(),
                                item.path("content_sha256").asText());
                        if (item.path("metadata").isObject()) {
                            store.db.update(
                                    "insert into news_item_metadata(news_id,details) values(?,?::jsonb)",
                                    item.path("id").asText(),
                                    store.encode(item.path("metadata")));
                        }
                    }
                    store.audit("NEWS_COLLECT", result.path("sources"));
                });
        return result;
    }

    /** 新闻同时满足发布日期及实际抓取时间限制，只允许当前起的前瞻验证。 */
    public List<JsonNode> eligible(Instant cutoff) {
        return store.db.query(
                "select row_to_json(n)::text from news_item n where nature='REAL'"
                        + " and publication_verified and published_at<=? and fetched_at<=?"
                        + " and published_at>=? order by published_at desc limit 15",
                (rs, row) -> store.decode(rs.getString(1)),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff.minusSeconds(7 * 86400)));
    }

    /** 新版先提供有界候选，再由同一Python规则去重排序；旧15条接口与历史记录不变。 */
    public List<JsonNode> candidates(Instant cutoff) {
        return store.db.query(
                "select (to_jsonb(n)||jsonb_build_object('metadata',coalesce(m.details,'{}'::jsonb)))::text"
                        + " from news_item n left join news_item_metadata m on m.news_id=n.id"
                        + " where n.nature='REAL' and n.publication_verified"
                        + " and n.published_at<=? and n.fetched_at<=? and n.published_at>=?"
                        + " order by n.published_at desc,n.id limit 150",
                (rs, row) -> store.decode(rs.getString(1)),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff.minusSeconds(7 * 86400)));
    }

    /** 每日每配置至多发起一次；先持久预留，崩溃后不会再次盲目收费。 */
    public JsonNode analyze(Instant cutoff) {
        Map<String, Object> payload = new HashMap<>();
        List<JsonNode> news = eligible(cutoff);
        payload.put("cutoff", cutoff.toString());
        payload.put("news", news);
        payload.put(
                "symbols",
                store.db.query(
                        "select symbol from etf_instrument where enabled order by symbol",
                        (rs, row) -> rs.getString(1)));
        JsonNode quote = quant.post("/analysis/quote", payload);
        if (quote.has("eligible_source_ids")) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            quote.path("eligible_source_ids").forEach(id -> ids.add(id.asText()));
            news = news.stream().filter(item -> ids.contains(item.path("id").asText())).toList();
            payload.put("news", news);
        }
        List<JsonNode> eligibleNews = news;
        Map<String, Object> frozenInput = new HashMap<>(payload);
        frozenInput.put("relevance", quote.path("relevance"));
        LocalDate day = cutoff.atZone(BEIJING).toLocalDate();
        String key =
                "events:"
                        + day
                        + ":"
                        + quote.path("model").asText()
                        + ":"
                        + quote.path("prompt_version").asText()
                        + ":"
                        + quote.path("enabled").asBoolean()
                        + ":"
                        + quote.path("configured").asBoolean();
        String effort = quote.path("model_settings").path("reasoning_effort").asText("none");
        if (!"none".equals(effort)) key += ":reasoning:" + effort;
        final String requestKey = key;
        BigDecimal reserve = new BigDecimal(quote.path("reserved_usd").asText());
        UUID id = UUID.randomUUID();
        JsonNode existing =
                tx.execute(
                        status -> {
                            store.lock("llm-budget:deepseek:" + day);
                            List<JsonNode> found =
                                    store.db.query(
                                            "select row_to_json(a)::text from llm_analysis a"
                                                    + " where business_key=?",
                                            (rs, row) -> store.decode(rs.getString(1)),
                                            requestKey);
                            if (!found.isEmpty()) {
                                return found.get(0);
                            }
                            boolean enabled =
                                    quote.path("enabled").asBoolean()
                                            && quote.path("configured").asBoolean();
                            BigDecimal used =
                                    store.db.queryForObject(
                                            "select coalesce(sum(case when status='CONFIRMED'"
                                                    + " then actual_cost else reserved_cost end),0) from api_call_cost c"
                                                    + " where created_at>=? and created_at<?"
                                                    // 所有DeepSeek领域共用额度，GPT历史费用按执行项排除。
                                                    + " and not exists (select 1 from forecast_run_item i where i.id=c.id"
                                                    + " and i.scheme in ('GPT','GPT_DISAGREEMENT'))",
                                            BigDecimal.class,
                                            Timestamp.from(day.atStartOfDay(BEIJING).toInstant()),
                                            Timestamp.from(
                                                    day.plusDays(1)
                                                            .atStartOfDay(BEIJING)
                                                            .toInstant()));
                            String failure =
                                    !enabled
                                            ? "PROVIDER_DISABLED_OR_KEY_MISSING"
                                            : eligibleNews.isEmpty()
                                                    ? "NO_RELEVANT_NEWS"
                                                    : used.add(reserve).compareTo(dailyLimit) > 0
                                                            ? "DAILY_BUDGET_EXCEEDED"
                                                            : null;
                            store.db.update(
                                    "insert into llm_analysis(id,business_key,input_cutoff,status,nature,"
                                            + "prompt_version,model_version,source_ids,error_code,input_snapshot)"
                                            + " values(?,?,?,?,'REAL',?,?,?::jsonb,?,?::jsonb)",
                                    id,
                                    requestKey,
                                    Timestamp.from(cutoff),
                                    failure == null ? "RUNNING" : "MISSING",
                                    quote.path("prompt_version").asText(),
                                    quote.path("model_version").asText(),
                                    store.encode(
                                            eligibleNews.stream()
                                                    .map(n -> n.path("id").asText())
                                                    .toList()),
                                    failure,
                                    store.encode(frozenInput));
                            if (failure != null) {
                                return get(id);
                            }
                            store.db.update(
                                    "insert into api_call_cost(id,business_key,status,reserved_cost,pricing_snapshot)"
                                            + " values(?,?,'RESERVED',?,?::jsonb)",
                                    id,
                                    requestKey,
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
            JsonNode result = quant.post("/analysis/run", payload);
            tx.executeWithoutResult(
                    status -> {
                        store.db.update(
                                "update llm_analysis set status=?,result=?::jsonb,error_code=? where id=?",
                                result.path("status").asText(),
                                store.encode(result),
                                result.path("error_code").asText(null),
                                id);
                        store.db.update(
                                "update api_call_cost set status=?,actual_cost=?,input_tokens=?,"
                                        + "output_tokens=?,duration_ms=? where id=?",
                                result.path("billing_uncertain").asBoolean(true)
                                        ? "UNCERTAIN"
                                        : "CONFIRMED",
                                new BigDecimal(result.path("cost_usd").asText("0")),
                                result.path("input_tokens").asInt(),
                                result.path("output_tokens").asInt(),
                                result.path("duration_ms").asLong(),
                                id);
                    });
        } catch (RuntimeException error) {
            // 无法断言供应商未扣费，保留RESERVED金额直到管理员核对账单。
            store.db.update(
                    "update llm_analysis set status='FAILED',error_code='INTERNAL_CALL_FAILED' where id=?",
                    id);
        }
        return get(id);
    }

    /** 查询结构化记录，不返回服务端密钥。 */
    public JsonNode get(UUID id) {
        return store.db.queryForObject(
                "select row_to_json(a)::text from llm_analysis a where id=?",
                (rs, row) -> store.decode(rs.getString(1)),
                id);
    }

    /** 页面只获取启用状态和费用上限，不获取密钥。 */
    public Map<String, Object> configuration() {
        JsonNode configuration = quant.post("/analysis/settings", Map.of());
        return Map.of(
                "provider",
                configuration.path("providers").path(0),
                "providers",
                configuration.path("providers"),
                "daily_limit_usd",
                dailyLimit,
                "gpt_daily_limit_usd",
                gptDailyLimit);
    }
}
