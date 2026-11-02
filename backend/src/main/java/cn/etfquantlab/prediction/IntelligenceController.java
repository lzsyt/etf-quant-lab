package cn.etfquantlab.prediction;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 财经情报只读投影；页面查看不触发采集、模型调用或历史数据回填。 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {
    private final Store store;

    /** 仅依赖本机事实库，外部来源或Python故障不阻止查询已有证据。 */
    public IntelligenceController(Store store) {
        this.store = store;
    }

    /** 有界分页查询新闻；筛选参数只作为SQL绑定值，第三方正文不返回管理页面。 */
    @GetMapping
    public Map<String, Object> overview(
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String symbol,
            @RequestParam(name = "published_from", defaultValue = "") String publishedFrom,
            @RequestParam(name = "published_to", defaultValue = "") String publishedTo,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String relevance,
            @RequestParam(defaultValue = "") String importance,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> parameters = new ArrayList<>();
        if (!source.isBlank()) {
            where.append(
                    " and (n.source_name=? or (?='PBOC' and n.source_name='PBOC_OFFICIAL_DATE_PRECISION'))");
            parameters.add(source);
            parameters.add(source);
        }
        filter(
                where,
                parameters,
                "m.details->'related_symbols' @> jsonb_build_array(?::text)",
                symbol);
        filter(where, parameters, "m.details->>'category'=?", category);
        filter(
                where,
                parameters,
                "coalesce(m.details->>'relevance_status','UNVERIFIED')=?",
                relevance);
        filter(where, parameters, "m.details->>'importance_level'=?", importance);
        if (!publishedFrom.isBlank()) {
            where.append(" and n.published_at>=?");
            parameters.add(Timestamp.from(boundary(publishedFrom, false)));
        }
        if (!publishedTo.isBlank()) {
            where.append(" and n.published_at<?");
            parameters.add(Timestamp.from(boundary(publishedTo, true)));
        }
        String from = " from news_item n left join news_item_metadata m on m.news_id=n.id" + where;
        Long total =
                store.db.queryForObject("select count(*)" + from, Long.class, parameters.toArray());
        parameters.add(Math.max(1, Math.min(limit, 200)));
        parameters.add(Math.max(0, offset));
        List<JsonNode> news =
                store.db.query(
                        "select (to_jsonb(n)||jsonb_build_object('metadata',"
                                + "coalesce(m.details,'{}'::jsonb)-'content','content_type',"
                                + "coalesce(m.details->>'content_type','SUMMARY')))::text"
                                + from
                                + " order by n.published_at desc,n.id limit ? offset ?",
                        (rs, row) -> store.decode(rs.getString(1)),
                        parameters.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", Instant.now());
        result.put("sources", sources());
        result.put("news", news);
        result.put("news_total", total);
        result.put("analyses", analyses());
        result.put("legacy_analyses", legacyAnalyses());
        result.put(
                "collection_failure",
                store.db.query(
                        "select jsonb_build_object('created_at',created_at,'details',details)::text"
                                + " from operation_audit where action='NEWS_COLLECT_FAILED'"
                                + " order by created_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1))));
        return result;
    }

    /** 从既有追加审计恢复每源最新状态与最近成功，失败不会覆盖以前的成功时刻。 */
    private List<JsonNode> sources() {
        return store.db.query(
                "with reports as (select a.id,a.created_at,e.item from operation_audit a"
                        + " cross join lateral jsonb_array_elements(case"
                        + " when jsonb_typeof(a.details)='array' then a.details else '[]'::jsonb end)"
                        + " as e(item) where a.action='NEWS_COLLECT'), latest as ("
                        + " select distinct on(item->>'source') item,created_at,"
                        + " max(created_at) filter(where item->>'status' in ('OK','PARTIAL')"
                        + " and coalesce((item->>'count')::integer,0)>0)"
                        + " over(partition by item->>'source') as last_success_at"
                        + " from reports order by item->>'source',created_at desc,id desc)"
                        + " select (item||jsonb_build_object('last_attempt_at',"
                        + "case when item->>'status' in ('DISABLED','NOT_ENABLED','NOT_ADMITTED')"
                        + " then null else created_at end,'last_reported_at',created_at,"
                        + "'last_success_at',last_success_at,'stale',case"
                        + " when nullif(item->>'source_updated_at','') is null then null"
                        + " else (item->>'source_updated_at')::timestamptz"
                        + "<now()-interval '7 days' end))::text from latest",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 分析与真实冻结决策关联；被独立ML引用或回退引用不等于新闻参与融合。 */
    private List<JsonNode> analyses() {
        List<JsonNode> analyses =
                store.db.query(
                        "select (to_jsonb(a)||jsonb_build_object('cost',to_jsonb(c)))::text"
                                + " from forward_analysis a left join api_call_cost c on c.id=a.id"
                                + " order by a.created_at desc limit 30",
                        (rs, row) -> store.decode(rs.getString(1)));
        for (JsonNode analysis : analyses) {
            ObjectNode row = (ObjectNode) analysis;
            List<JsonNode> usage =
                    store.db.query(
                            "select jsonb_build_object('decision_id',d.id,'symbol',d.symbol,"
                                    + "'account_id',d.account_id,'strategy',d.details->>'strategy',"
                                    + "'generated_at',d.generated_at,'news_available',"
                                    + "d.details->'news_available','mode',d.details->>'effective_mode',"
                                    + "'nature',e.nature,'evaluation_type',e.evaluation_type)::text"
                                    + " from forward_decision d join forward_experiment e on e.id=d.experiment_id"
                                    + " where d.details->>'analysis_id'=?"
                                    + " order by d.generated_at desc,d.symbol limit 100",
                            (rs, index) -> store.decode(rs.getString(1)),
                            analysis.path("id").asText());
            row.set("usage", store.json.valueToTree(usage));
            row.put(
                    "participated_in_fusion",
                    "REAL".equals(analysis.path("result").path("nature").asText())
                            && usage.stream()
                                    .anyMatch(
                                            item ->
                                                    "FUSION_EXPERIMENT"
                                                                    .equals(
                                                                            item.path("strategy")
                                                                                    .asText())
                                                            && item.path("news_available")
                                                                    .asBoolean()
                                                            && "REAL"
                                                                    .equals(
                                                                            item.path("nature")
                                                                                    .asText())
                                                            && "FORWARD_PAPER"
                                                                    .equals(
                                                                            item.path(
                                                                                            "evaluation_type")
                                                                                    .asText())
                                                            && "FUSION"
                                                                    .equals(
                                                                            item.path("mode")
                                                                                    .asText())));
            row.put("evaluation_type", "FORWARD_PAPER");
            row.put("nature", analysis.path("result").path("nature").asText("UNVERIFIED"));
            // 冻结输入可能包含获准内部研究的正文；页面只显示来源摘要及结构化结论。
            projectEvidence(row);
        }
        return analyses;
    }

    /** 旧市场总分分析单独返回原版本，不能冒充新版逐ETF或已验证的前瞻结果。 */
    private List<JsonNode> legacyAnalyses() {
        return store.db.query(
                "select (jsonb_build_object('id',a.id,'input_cutoff',a.input_cutoff,"
                        + "'created_at',a.created_at,'status',a.status,'nature',a.nature,"
                        + "'prompt_version',a.prompt_version,'model_version',a.model_version,"
                        + "'source_ids',a.source_ids,'result',a.result,'error_code',a.error_code,"
                        + "'evaluation_type','LEGACY_MARKET_ANALYSIS','cost',to_jsonb(c)))::text"
                        + " from llm_analysis a left join api_call_cost c on c.id=a.id"
                        + " order by a.created_at desc limit 30",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 只有顶层quote是冻结事实源，先投影再清理，避免模型伪造嵌套目录混入公开证据。 */
    private void projectEvidence(ObjectNode row) {
        JsonNode catalog = row.path("quote").path("evidence_catalog");
        ArrayNode publicEvidence =
                catalog.isArray()
                        ? publicEvidence(catalog, row.path("input_snapshot").path("news"))
                        : null;
        removeContent(row);
        if (publicEvidence != null) {
            ((ObjectNode) row.path("quote")).set("evidence_catalog", publicEvidence);
        }
    }

    /** 内容预算与输入摘要保留可见，正文、原始提示及未投影证据目录只留内部审计。 */
    private void removeContent(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove(List.of("content", "input_text", "evidence_catalog"));
            object.elements().forEachRemaining(this::removeContent);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::removeContent);
        }
    }

    /** 保留证据身份与时间元数据，仅公开同一冻结输入中获准官方来源的240字以内片段。 */
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

    /** 来源名和域名同时匹配才允许展示，避免信任模型自行填写的来源元数据。 */
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

    /** 可选条件使用参数绑定，空选项不改变原查询口径。 */
    private void filter(
            StringBuilder where, List<Object> parameters, String condition, String value) {
        if (!value.isBlank()) {
            where.append(" and ").append(condition);
            parameters.add(value);
        }
    }

    /** 仅日期按北京时间自然日筛选，带时区时间按真实时刻解释。 */
    private Instant boundary(String value, boolean upper) {
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return (upper ? date.plusDays(1) : date)
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant();
            }
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("新闻日期筛选必须是日期或带时区的ISO时间");
        }
    }
}
