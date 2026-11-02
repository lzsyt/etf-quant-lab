package cn.etfquantlab.gold;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 黄金页只依赖保存事实；浏览、刷新、切换标签都不触发采集或付费调用。 */
@Service
public class GoldView {
    private final Store store;
    private final GoldService gold;
    private final boolean scheduling;
    private final GoldNews news;

    /** 页面投影和后台执行分离，Python或外部来源不可用仍能查看旧结果日期。 */
    public GoldView(
            Store store,
            GoldService gold,
            GoldNews news,
            @Value("${etf.scheduling-enabled:false}") boolean scheduling) {
        this.store = store;
        this.gold = gold;
        this.scheduling = scheduling;
        this.news = news;
    }

    /** 展示基准、历史、最新两个期限和来源健康；详细证据另有只读查询。 */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<JsonNode> observations = gold.observations();
        List<JsonNode> prices =
                observations.stream()
                        .filter(row -> GoldService.BENCHMARK.equals(row.path("series_id").asText()))
                        .toList();
        JsonNode last = prices.isEmpty() ? null : prices.get(prices.size() - 1);
        String asOf = last == null ? null : last.path("observation_date").asText();
        boolean stale =
                asOf != null
                        && LocalDate.parse(asOf)
                                .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(10));
        result.put(
                "benchmark",
                Map.of(
                        "series_id",
                        GoldService.BENCHMARK,
                        "name",
                        "上海黄金交易所 Au99.99",
                        "unit",
                        "CNY/克",
                        "price_field",
                        "官方日收盘价",
                        "fineness",
                        "0.9999",
                        "boundary",
                        "国内人民币黄金基准，不等于银行/商家零售价或回购成交价"));
        result.put("status", last == null ? "MISSING_DATA" : stale ? "STALE" : "AVAILABLE");
        result.put("as_of", asOf);
        result.put("updated_at", last == null ? null : last.path("fetched_at").asText());
        result.put(
                "history",
                prices.stream()
                        .map(
                                row ->
                                        Map.of(
                                                "date",
                                                row.path("observation_date").asText(),
                                                "price",
                                                row.path("value").asText(),
                                                "source",
                                                row.path("source").asText(),
                                                "quality",
                                                row.path("quality").asText()))
                        .toList());
        List<JsonNode> forecasts = gold.predictions();
        for (JsonNode forecast : forecasts) {
            ((ObjectNode) forecast).remove("input_snapshot");
        }
        result.put("predictions", forecasts);
        result.put("models", gold.models());
        result.put(
                "sources",
                store.db.query(
                        "select (details||jsonb_build_object('series_id',series_id,'status',status,"
                                + "'checked_at',checked_at,'last_attempt_at',checked_at,"
                                + "'last_success_at',last_success_at))::text"
                                + " from gold_source order by series_id",
                        (rs, row) -> store.decode(rs.getString(1))));
        result.put(
                "coverage",
                store.db.query(
                        "select row_to_json(c)::text from (select series_id,count(distinct observation_date) as count,"
                                + "min(observation_date) as first_date,max(observation_date) as last_date,"
                                + "count(*) as revision_count from gold_observation group by series_id) c",
                        (rs, row) -> store.decode(rs.getString(1))));
        Map<String, JsonNode> macro = new LinkedHashMap<>();
        for (JsonNode observation : observations) {
            if (!GoldService.BENCHMARK.equals(observation.path("series_id").asText())) {
                ObjectNode value = observation.deepCopy();
                value.put("date", value.path("observation_date").asText());
                value.put("name", value.path("source").asText());
                long age =
                        java.time.temporal.ChronoUnit.DAYS.between(
                                LocalDate.parse(value.path("observation_date").asText()),
                                LocalDate.now(ZoneId.of("Asia/Shanghai")));
                int maximum =
                        "WORLD_BANK_GOLD_MONTHLY".equals(value.path("series_id").asText())
                                ? 62
                                : 14;
                value.put("data_age_days", age);
                value.put("max_age_days", maximum);
                value.put("stale", age > maximum);
                macro.put(value.path("series_id").asText(), value);
            }
        }
        result.put("macro", new ArrayList<>(macro.values()));
        result.put("scenarios", scenarios());
        result.put("news", news.latest());
        result.put(
                "health",
                Map.of(
                        "enabled",
                        gold.enabled(),
                        "scheduling_enabled",
                        scheduling,
                        "schedule",
                        "北京时间每日21:00增量；周一22:30研究预测；每月夜间重训，可由部署配置调整",
                        "tasks",
                        store.db.query(
                                "select jsonb_build_object('id',id,'task_type',task_type,'status',status,"
                                        + "'attempts',attempts,'updated_at',updated_at,"
                                        + "'error_code',error_code,'result',result)::text"
                                        + " from task_execution where task_type like 'GOLD_%'"
                                        + " order by updated_at desc limit 20",
                                (rs, row) -> store.decode(rs.getString(1)))));
        result.put(
                "boundaries",
                List.of(
                        "历史回放使用当前版本公开历史，不能冒称严格点时回放",
                        "63/126为上金所交易日；未来日历未确认时目标日期仅为预计",
                        "上线后真实前瞻未到期保持待验证；历史指标不证明收益",
                        "国际美元金价×USD/CNY÷31.1034768仅为折算参考，未再次叠加到国内金价"));
        return result;
    }

    /** 12个月只提供条件情景，不编造目标价、路径、概率或因果强度。 */
    private List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(
                Map.of(
                        "name",
                        "实际利率下降与避险需求增强",
                        "horizon",
                        "12个月条件情景",
                        "assumptions",
                        List.of("美国实际利率持续下降", "避险或官方购金需求延续", "人民币未显著升值抵消美元金价变化"),
                        "mechanism",
                        "持有黄金的机会成本可能下降，需求增加可能支持价格；这些是机制假设，不是因果估计。",
                        "invalidation",
                        "实际利率反弹、需求回落、人民币明显升值或利好已充分计价。",
                        "direction",
                        "偏强条件",
                        "numeric_target",
                        "条件不足，不提供数值目标或概率"));
        scenarios.add(
                Map.of(
                        "name",
                        "实际利率走高与需求减弱",
                        "horizon",
                        "12个月条件情景",
                        "assumptions",
                        List.of("实际利率维持高位或回升", "避险及购金需求减弱", "人民币相对美元升值"),
                        "mechanism",
                        "机会成本和资金流变化可能压制美元金价；人民币升值还可能压低折算价格，国内溢价会改变传导。",
                        "invalidation",
                        "需求突增、利率转向下行、人民币贬值或国内供需溢价扩大。",
                        "direction",
                        "偏弱条件",
                        "numeric_target",
                        "条件不足，不提供数值目标或概率"));
        scenarios.add(
                Map.of(
                        "name",
                        "相反因素互相抵消",
                        "horizon",
                        "12个月条件情景",
                        "assumptions",
                        List.of("利率、汇率和需求因素方向不一致", "没有可靠证据确定净影响"),
                        "mechanism",
                        "美元金价与人民币汇率可能部分抵消，国内供需及实物交易价差仍需独立观察。",
                        "invalidation",
                        "关键变量出现持续单向变化或新的可核实需求冲击。",
                        "direction",
                        "方向不确定",
                        "numeric_target",
                        "条件不足，不提供数值目标或概率"));
        return scenarios;
    }
}
