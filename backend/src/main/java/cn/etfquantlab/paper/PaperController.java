package cn.etfquantlab.paper;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaperController {
    private final Store store;
    private final ResearchService research;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final PaperService paper;
    private final boolean schedulingEnabled;

    /** 模拟账户管理与可审计风险参数；没有真实交易入口。 */
    public PaperController(
            Store store,
            ResearchService research,
            QuantClient quant,
            TransactionTemplate tx,
            PaperService paper,
            @Value("${etf.scheduling-enabled:false}") boolean schedulingEnabled) {
        this.store = store;
        this.research = research;
        this.quant = quant;
        this.tx = tx;
        this.paper = paper;
        this.schedulingEnabled = schedulingEnabled;
    }

    /** 每种策略有独立初始资金，不能合并解释为单个策略收益。 */
    @GetMapping("/paper/accounts")
    public List<JsonNode> accounts() {
        return store.db.query(
                "select row_to_json(a)::text from (select p.*,s.enabled,s.name from paper_account p"
                        + " join strategy_config s on s.id=p.strategy_id order by p.id) a",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 当前持仓含取得日期批次，支持解释T+1限制。 */
    @GetMapping("/paper/positions")
    public List<JsonNode> positions(@RequestParam(defaultValue = "ma") String account) {
        return store.db.query(
                "select row_to_json(p)::text from paper_position p where account_id=? order by symbol",
                (rs, row) -> store.decode(rs.getString(1)),
                account);
    }

    /** 订单含未成交原因，不能把拒单计算为成交。 */
    @GetMapping("/paper/orders")
    public List<JsonNode> orders(@RequestParam(defaultValue = "ma") String account) {
        return store.db.query(
                "select row_to_json(o)::text from (select * from paper_order where account_id=?"
                        + " order by trade_date desc limit 1000) o",
                (rs, row) -> store.decode(rs.getString(1)),
                account);
    }

    /** 不改动持久账户，独立核验成交账本能否重建现金及权益。 */
    @GetMapping("/paper/reconstruction")
    public Map<String, Object> reconstruction(@RequestParam(defaultValue = "ma") String account) {
        return paper.reconstruct(account);
    }

    /** 同时观察策略配置、真实队列、行情与冻结信号，不能用enabled代替运行证据。 */
    @GetMapping("/paper/diagnostics")
    public Map<String, Object> diagnostics(@RequestParam(defaultValue = "ma") String account) {
        JsonNode item =
                store.db.queryForObject(
                        "select row_to_json(a)::text from (select p.*,s.enabled from paper_account p"
                                + " join strategy_config s on s.id=p.strategy_id where p.id=?) a",
                        (rs, row) -> store.decode(rs.getString(1)),
                        account);
        List<JsonNode> tasks =
                store.db.query(
                        "select row_to_json(t)::text from (select id,business_key,status,task_type,attempts,started_at,"
                                + "updated_at,heartbeat_at,error_code from task_execution where "
                                + "task_type='EOD' order by updated_at desc limit 1) t",
                        (rs, row) -> store.decode(rs.getString(1)));
        Date latestMarket =
                store.db.queryForObject(
                        "select min(d) from (select max(trade_date) d from market_bar b join "
                                + "etf_instrument i on i.symbol=b.symbol"
                                + " where i.enabled and nature='REAL' and price_basis='RAW' and "
                                + "quality_status in ('VALID','WARNING') group by b.symbol) x",
                        Date.class);
        Date signal =
                store.db.queryForObject(
                        "select max(signal_date) from prediction where strategy_id=? and nature='REAL' and horizon=1",
                        Date.class,
                        item.path("strategy_id").asText());
        LocalDate today = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        Date next =
                store.db.queryForObject(
                        "select min(trade_date) from trading_calendar where is_open and trade_date>?",
                        Date.class,
                        latestMarket == null ? Date.valueOf(today) : latestMarket);
        Integer fillCount =
                store.db.queryForObject(
                        "select count(*) from paper_fill f join paper_order o on o.id=f.order_id where o.account_id=?",
                        Integer.class,
                        account);
        JsonNode last = item.path("last_settlement");
        String code = last.path("reason_code").asText("TASK_NOT_EXECUTED");
        String message = last.path("reason").asText("尚无该账户结算证据，启用状态不等于任务已执行");
        if (!item.path("enabled").asBoolean()) {
            code = "STRATEGY_PAUSED";
            message = "策略配置已暂停，不发送新的模拟交易";
        } else if (!schedulingEnabled) {
            code = "SCHEDULER_DISABLED";
            message = "当前环境未启用自动调度；手动历史执行不代表持续运行";
        } else if (!tasks.isEmpty() && "FAILED".equals(tasks.get(0).path("status").asText())) {
            code = "TASK_FAILED";
            message = "最近日终任务失败，检查任务错误及行情同步结果";
        } else if (latestMarket == null) {
            code = "MARKET_MISSING";
            message = "尚无完整真实行情，无法生成或执行策略信号";
        } else if (signal != null
                && next != null
                && !signal.before(latestMarket)
                && Instant.now()
                        .isBefore(
                                next.toLocalDate()
                                        .atTime(9, 30)
                                        .atZone(ZoneId.of("Asia/Shanghai"))
                                        .toInstant())) {
            code = "WAITING_NEXT_TRADING_DAY";
            message = "冻结预测已保存，等待下一交易日；不能将周末新预测回填为周五成交";
        } else if (signal == null) {
            code = "MODEL_OR_SIGNAL_UNAVAILABLE";
            message = "尚无可执行冻结预测，需检查模型、新闻证据及日终任务";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", account);
        result.put("strategy_id", item.path("strategy_id").asText());
        result.put("enabled", item.path("enabled").asBoolean());
        result.put("scheduling_enabled", schedulingEnabled);
        result.put("reason_code", code);
        result.put("message", message);
        result.put("last_task", tasks.isEmpty() ? null : tasks.get(0));
        result.put("latest_market_date", latestMarket);
        result.put("last_signal_date", signal);
        result.put("next_trade_date", next);
        result.put("last_trade_date", item.path("last_trade_date"));
        result.put("last_settlement", last);
        result.put("fill_count", fillCount);
        BigDecimal equity = item.path("equity").decimalValue();
        BigDecimal cash = item.path("cash").decimalValue();
        result.put(
                "capital_utilization",
                equity.signum() == 0
                        ? BigDecimal.ZERO
                        : equity.subtract(cash).divide(equity, 10, RoundingMode.HALF_UP));
        result.put("mode", "FORWARD_PAPER_ONLY");
        return result;
    }

    /** 每日净值独立于历史回测结果。 */
    @GetMapping("/paper/snapshots")
    public List<JsonNode> snapshots(@RequestParam(defaultValue = "ma") String account) {
        return store.db.query(
                "select row_to_json(s)::text from account_snapshot s where account_id=? order by trade_date",
                (rs, row) -> store.decode(rs.getString(1)),
                account);
    }

    /** 明确启停选定策略并保留审计记录，不清空既有持仓或历史。 */
    @PostMapping("/strategies/{id}/enabled")
    public Map<String, Object> enabled(
            @PathVariable String id, @RequestBody Map<String, Boolean> request) {
        boolean enabled = Boolean.TRUE.equals(request.get("enabled"));
        tx.executeWithoutResult(
                status -> {
                    if (store.db.update(
                                    "update strategy_config set enabled=?,updated_at=now() where id=?",
                                    enabled,
                                    id)
                            != 1) {
                        throw new IllegalArgumentException("策略不存在");
                    }
                    store.audit("STRATEGY_ENABLED", Map.of("id", id, "enabled", enabled));
                });
        return Map.of("id", id, "enabled", enabled);
    }

    /** 当前风险配置及历史修改记录。 */
    @GetMapping("/risk")
    public Map<String, Object> risk() {
        return Map.of(
                "current",
                research.risk(),
                "history",
                store.db.query(
                        "select row_to_json(r)::text"
                                + " from risk_config_history r order by changed_at desc limit 50",
                        (rs, row) -> store.decode(rs.getString(1))));
    }

    /** 参数通过撮合引擎相同Schema验证后另存版本，不覆盖历史版本。 */
    @PostMapping("/risk")
    public JsonNode saveRisk(@RequestBody JsonNode request) {
        JsonNode valid = quant.post("/risk/validate", request.path("parameters"));
        String reason = request.path("reason").asText("").trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("必须填写修改原因");
        }
        tx.executeWithoutResult(
                status -> {
                    store.db.update(
                            "insert into risk_config_history(id,parameters,reason) values(?,?::jsonb,?)",
                            UUID.randomUUID(),
                            store.encode(valid),
                            reason);
                    store.audit(
                            "RISK_CONFIGURATION", Map.of("parameters", valid, "reason", reason));
                });
        return valid;
    }
}
