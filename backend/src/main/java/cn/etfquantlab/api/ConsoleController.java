package cn.etfquantlab.api;

import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ConsoleController {
    private final Store store;
    private final QuantClient quant;
    private final AnalysisService analysis;
    private final boolean scheduling;

    /** 控制台只返回运行指标和脱敏配置，不提供任意文件或命令执行入口。 */
    public ConsoleController(
            Store store,
            QuantClient quant,
            AnalysisService analysis,
            @Value("${etf.scheduling-enabled:false}") boolean scheduling) {
        this.store = store;
        this.quant = quant;
        this.analysis = analysis;
        this.scheduling = scheduling;
    }

    /** 默认展示单策略独立账户，避免把多个实验账户合并成虚假的组合收益。 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "lightgbm") String account) {
        Map<String, Object> result = new HashMap<>();
        result.put(
                "account",
                store.db.queryForMap(
                        "select id,cash,equity,initial_capital,peak_equity,locked,"
                                + "last_trade_date,state::text from paper_account where id=?",
                        account));
        result.put(
                "previous_equity",
                store.db.query(
                        "select equity from account_snapshot where account_id=?"
                                + " order by trade_date desc offset 1 limit 1",
                        (rs, row) -> rs.getBigDecimal(1),
                        account));
        result.put("market", marketSummary());
        result.put(
                "latest_prediction",
                store.db.query(
                        "select row_to_json(t)::text from (select signal_date,created_at,"
                                + "direction,model_version from prediction where strategy_id=(select strategy_id"
                                + " from paper_account where id=?) order by created_at desc limit 1) t",
                        (rs, row) -> store.decode(rs.getString(1)),
                        account));
        result.put(
                "enabled_strategies",
                store.db.queryForList("select id,name from strategy_config where enabled"));
        result.put("mode", "PAPER_ONLY");
        result.put("evaluation_type", "FORWARD_PAPER");
        result.put("health", Map.of("backend", "UP", "database", "UP"));
        return result;
    }

    /** 实盘模拟排行仅使用上线后冻结预测和净值；历史模型成绩由独立页面展示。 */
    @GetMapping("/rankings/forward")
    public List<JsonNode> forward() {
        List<JsonNode> accounts =
                rows(
                        "select row_to_json(r)::text from (select s.id,s.name,s.category,a.equity,a.initial_capital,"
                                + "a.peak_equity,a.locked,(a.equity/a.initial_capital-1) as total_return,"
                                + "(select count(*) from prediction p join prediction_actual x on x.prediction_id=p.id"
                                + " where p.strategy_id=s.id and p.horizon=1 and x.correct is not null) as evaluated,"
                                + "(select avg(case when x.correct then 1.0 else 0.0 end) from prediction p"
                                + " join prediction_actual x on x.prediction_id=p.id where p.strategy_id=s.id"
                                + " and p.horizon=1 and x.correct is not null) as accuracy,"
                                + "(select avg(x.brier_score) from prediction p"
                                + " join prediction_actual x on x.prediction_id=p.id"
                                + " where p.strategy_id=s.id and p.horizon=1) as brier_score,"
                                + "(select max((n.details->>'drawdown')::numeric) from account_snapshot n"
                                + " where n.account_id=a.id) as max_drawdown,"
                                + "a.state->>'cumulative_fees' as fees from strategy_config s"
                                + " join paper_account a on a.strategy_id=s.id) r");
        List<Map<String, Object>> payload = new java.util.ArrayList<>();
        for (JsonNode account : accounts) {
            String id = account.path("id").asText();
            List<JsonNode> curve =
                    store.db.query(
                            "select details::text from account_snapshot"
                                    + " where account_id=? order by trade_date",
                            (rs, row) -> store.decode(rs.getString(1)),
                            id);
            List<JsonNode> fills =
                    store.db.query(
                            "select f.details::text from paper_fill f join paper_order o"
                                    + " on o.id=f.order_id where o.account_id=? order by o.trade_date",
                            (rs, row) -> store.decode(rs.getString(1)),
                            id);
            payload.add(
                    Map.of(
                            "id",
                            id,
                            "curve",
                            curve,
                            "fills",
                            fills,
                            "initial_capital",
                            account.path("initial_capital").asText()));
        }
        JsonNode metrics = quant.post("/paper/metrics", Map.of("accounts", payload));
        for (JsonNode account : accounts) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) account)
                    .set("metrics", metrics.path(account.path("id").asText()));
        }
        return accounts;
    }

    /** 正式融合评价必须按时间划分并由验证集确定权重；证据不足明确返回待积累。 */
    @GetMapping("/fusion/evaluation")
    public JsonNode fusion(@RequestParam(defaultValue = "1") int horizon) {
        if (horizon != 1 && horizon != 5) {
            throw new IllegalArgumentException("预测窗口只支持1或5个交易日");
        }
        List<JsonNode> values =
                store.db.query(
                        "select jsonb_build_object('signal_date',p.signal_date,"
                                + "'target_date',a.target_date,'ml_probability',p.probability,"
                                + "'sentiment',(n.details->>'score')::numeric,'actual_up',a.actual_up)::text"
                                + " from prediction p join prediction_actual a on a.prediction_id=p.id"
                                + " join prediction n on n.symbol=p.symbol and n.signal_date=p.signal_date"
                                + " and n.horizon=p.horizon and n.strategy_id='llm'"
                                + " join prediction_actual na on na.prediction_id=n.id and na.correct is not null"
                                + " where p.strategy_id='lightgbm' and p.horizon=? and p.probability is not null"
                                + " and p.nature='REAL' and n.nature='REAL' and n.direction<>'MISSING'"
                                + " and n.created_at < ((select min(c.trade_date) from trading_calendar c"
                                + " where c.is_open and c.trade_date>n.signal_date)+time '09:30') at "
                                + "time zone 'Asia/Shanghai'"
                                + " and a.correct is not null order by p.signal_date",
                        (rs, row) -> store.decode(rs.getString(1)),
                        horizon);
        return quant.post("/fusion/evaluate", Map.of("rows", values));
    }

    /** 已认证系统状态：资源用量、提供商状态、费用和任务，不包含秘密配置。 */
    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> result = new HashMap<>();
        result.put("mode", "PAPER_ONLY");
        result.put("scheduling_enabled", scheduling);
        result.put("schedule", "Asia/Shanghai 17:30；启动补偿、每月串行训练");
        result.put("timezone", "Asia/Shanghai / storage UTC");
        result.put(
                "jvm_used_mb",
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576);
        result.put("jvm_max_mb", Runtime.getRuntime().maxMemory() / 1048576);
        result.put("cpu_count", Runtime.getRuntime().availableProcessors());
        result.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("database", "UP");
        try {
            result.put("llm", analysis.configuration());
            result.put("quant", "UP");
        } catch (RuntimeException error) {
            result.put("quant", "DOWN");
            result.put("llm", Map.of("status", "UNAVAILABLE"));
        }
        result.put("market", marketSummary());
        result.put(
                "costs",
                rows(
                        "select row_to_json(c)::text from (select * from api_call_cost"
                                + " order by created_at desc limit 100) c"));
        result.put(
                "cost_total_usd",
                store.db.queryForObject(
                        "select coalesce(sum(case when status='CONFIRMED'"
                                + " then actual_cost else reserved_cost end),0) from api_call_cost",
                        BigDecimal.class));
        result.put(
                "data_sources", List.of("SINA", "TENCENT", "EASTMONEY", "CSV_UNVERIFIED", "DEMO"));
        result.put("model_pruning", "仅清理可重建缓存，已引用模型与研究历史永久保留；不自动删除账户数据");
        return result;
    }

    /** 安全的操作日志，不暴露任意服务日志文件或环境变量。 */
    @GetMapping("/system/audit")
    public List<JsonNode> audit() {
        return rows(
                "select row_to_json(a)::text from (select * from operation_audit"
                        + " order by created_at desc limit 200) a");
    }

    /** 风控原因供管理台查询，与任务异常分开显示。 */
    @GetMapping("/risk/events")
    public List<JsonNode> riskEvents() {
        return rows(
                "select row_to_json(r)::text from (select * from risk_event order by created_at desc limit 200) r");
    }

    /** 成交查询使用账户订单关联，拒单不作为成交记录展示。 */
    @GetMapping("/paper/fills")
    public List<JsonNode> fills(@RequestParam(defaultValue = "lightgbm") String account) {
        return store.db.query(
                "select row_to_json(f)::text from (select p.*,o.trade_date,o.symbol,o.side"
                        + " from paper_fill p join paper_order o on o.id=p.order_id where o.account_id=?"
                        + " order by o.trade_date desc limit 1000) f",
                (rs, row) -> store.decode(rs.getString(1)),
                account);
    }

    /** 质量统计严格按来源和数据性质分组，界面不能混称全部为真实行情。 */
    private List<JsonNode> marketSummary() {
        return rows(
                "select row_to_json(m)::text from (select symbol,nature,price_basis,source,count(*) as count,"
                        + "min(trade_date) as first_date,max(trade_date) as last_date,max(fetched_at) as fetched_at"
                        + " from market_bar group by symbol,nature,price_basis,source order by symbol,nature) m");
    }

    /** 内部固定SQL查询统一返回结构化行，用户输入不得拼接进SQL。 */
    private List<JsonNode> rows(String sql) {
        return store.db.query(sql, (rs, row) -> store.decode(rs.getString(1)));
    }
}
