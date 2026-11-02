package cn.etfquantlab.paper;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaperService {
    private final Store store;
    private final MarketService market;
    private final ResearchService research;
    private final QuantClient quant;
    private final TransactionTemplate tx;

    /** 模拟资金只有此服务可结算，数据库事务保护现金、持仓、订单和快照一致性。 */
    public PaperService(
            Store store,
            MarketService market,
            ResearchService research,
            QuantClient quant,
            TransactionTemplate tx) {
        this.store = store;
        this.market = market;
        this.research = research;
        this.quant = quant;
        this.tx = tx;
    }

    /** 使用已经发生的日线估计次日开盘成交，绝不发送证券委托。 */
    public List<Map<String, Object>> settle(LocalDate day) {
        List<String> accounts =
                store.db.query(
                        "select a.id from paper_account a join strategy_config s"
                                + " on s.id=a.strategy_id where s.enabled order by a.id",
                        (rs, row) -> rs.getString(1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String account : accounts) {
            result.add(settleAccount(account, day));
        }
        return result;
    }

    /** 同账户串行执行并比较最后结算日，重启及任务重试不会重复创建模拟订单。 */
    public Map<String, Object> settleAccount(String accountId, LocalDate day) {
        return tx.execute(
                status -> {
                    store.lock("paper:" + accountId);
                    Map<String, Object> account =
                            store.db.queryForMap(
                                    "select"
                                            + " a.strategy_id,a.last_trade_date,a.state::text,coalesce(s.enabled,false)"
                                            + " as enabled"
                                            + " from paper_account a left join strategy_config s on s.id=a.strategy_id"
                                            + " where a.id=? for update of a",
                                    accountId);
                    if (account.get("last_trade_date") != null
                            && !day.isAfter(
                                    ((Date) account.get("last_trade_date")).toLocalDate())) {
                        return Map.of("account", accountId, "status", "ALREADY_PROCESSED");
                    }
                    if (store.db.queryForObject(
                                    "select count(*) from trading_calendar where trade_date=? and is_open",
                                    Integer.class,
                                    Date.valueOf(day))
                            != 1) {
                        return recordStatus(
                                accountId, day, "NON_TRADING_DAY", "该日期不是已核实交易日，不生成模拟交易");
                    }
                    var now = Instant.now().atZone(ZoneId.of("Asia/Shanghai"));
                    if (day.isAfter(now.toLocalDate())
                            || (day.equals(now.toLocalDate())
                                    && now.toLocalTime().isBefore(LocalTime.of(15, 15)))) {
                        return recordStatus(
                                accountId, day, "WAITING_SESSION_CLOSE", "当日行情尚未完整，等待收盘后结算");
                    }
                    List<LocalDate> before =
                            store.db.query(
                                    "select max(trade_date) from trading_calendar where is_open and trade_date<?",
                                    (rs, row) ->
                                            rs.getDate(1) == null
                                                    ? null
                                                    : rs.getDate(1).toLocalDate(),
                                    Date.valueOf(day));
                    if (before.isEmpty() || before.get(0) == null) {
                        return recordStatus(accountId, day, "CALENDAR_MISSING", "缺少上一有效交易日，无法安全撮合");
                    }
                    LocalDate previous = before.get(0);
                    List<String> symbols =
                            store.db.query(
                                    "select symbol from etf_instrument where enabled order by symbol",
                                    (rs, row) -> rs.getString(1));
                    List<JsonNode> bars = new ArrayList<>();
                    List<JsonNode> previousBars = new ArrayList<>();
                    for (String symbol : symbols) {
                        bars.addAll(market.bars(symbol, day, day, "REAL", "RAW"));
                        previousBars.addAll(market.bars(symbol, previous, previous, "REAL", "RAW"));
                    }
                    if (bars.size() != symbols.size() || previousBars.size() != symbols.size()) {
                        // 缺失行情不推进结算日期，数据修复后可从同一天恢复。
                        return recordStatus(
                                accountId, day, "WAITING_COMPLETE_MARKET", "行情不完整或质量校验未通过，未推进结算日期");
                    }
                    Timestamp openTime =
                            Timestamp.from(
                                    day.atTime(LocalTime.of(9, 30))
                                            .atZone(ZoneId.of("Asia/Shanghai"))
                                            .toInstant());
                    List<JsonNode> predictions =
                            store.db.query(
                                    "select (details || jsonb_build_object('prediction_id',"
                                            + "id))::text from prediction where strategy_id=?"
                                            + " and signal_date=? and horizon=1 and nature='REAL'"
                                            + " and direction<>'MISSING' and created_at<? and input_cutoff<?",
                                    (rs, row) -> store.decode(rs.getString(1)),
                                    account.get("strategy_id"),
                                    Date.valueOf(previous),
                                    openTime,
                                    openTime);
                    List<Map<String, Object>> signals = new ArrayList<>();
                    if (Boolean.TRUE.equals(account.get("enabled"))
                            && predictions.size() == symbols.size()) {
                        for (JsonNode prediction : predictions) {
                            signals.add(
                                    Map.of(
                                            "symbol",
                                            prediction.path("symbol").asText(),
                                            "signal_date",
                                            previous.toString(),
                                            "target_weight",
                                            prediction.path("target_weight").asText("0")));
                        }
                    }
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("account_id", accountId);
                    payload.put("strategy", account.get("strategy_id"));
                    payload.put("state", store.decode((String) account.get("state")));
                    payload.put("trade_date", day.toString());
                    payload.put("previous_trade_date", previous.toString());
                    payload.put("bars", bars);
                    payload.put("previous_bars", previousBars);
                    payload.put("instruments", research.instruments(symbols));
                    payload.put("signals", signals);
                    payload.put("risk", research.risk());
                    payload.put("corporate_actions", research.verifiedActions(symbols, day));
                    JsonNode result = quant.post("/paper/step", payload);
                    JsonNode state = result.path("state");
                    if (decimal(state, "cash").signum() < 0
                            || decimal(state, "equity").signum() < 0) {
                        throw new IllegalStateException("模拟撮合返回负现金或负权益，事务已拒绝");
                    }
                    String reasonCode;
                    if (!Boolean.TRUE.equals(account.get("enabled"))) {
                        reasonCode = "STRATEGY_PAUSED";
                    } else if (signals.isEmpty()) {
                        reasonCode = "PREDICTION_MISSING_OR_LATE";
                    } else if (!result.path("fills").isEmpty()) {
                        reasonCode = "TRADED";
                    } else if (state.path("locked").asBoolean()) {
                        reasonCode = "RISK_LOCKED";
                    } else if (!result.path("risk_events").isEmpty()) {
                        reasonCode = "RISK_RESTRICTED";
                    } else {
                        boolean noBuySignal =
                                signals.stream()
                                        .allMatch(
                                                signal ->
                                                        new BigDecimal(
                                                                                signal.get(
                                                                                                "target_weight")
                                                                                        .toString())
                                                                        .signum()
                                                                == 0);
                        reasonCode =
                                noBuySignal ? "NO_BUY_SIGNAL" : "TARGET_ALREADY_MET_OR_BELOW_LOT";
                    }
                    List<String> orderReasons = new ArrayList<>();
                    for (JsonNode order : result.path("orders")) {
                        if (order.hasNonNull("reason")) {
                            orderReasons.add(
                                    order.path("symbol").asText()
                                            + ":"
                                            + order.path("reason").asText());
                        }
                    }
                    if (result.path("fills").isEmpty() && !orderReasons.isEmpty()) {
                        reasonCode = "ORDER_REJECTED";
                    }
                    String explanation = explanation(reasonCode);
                    Map<String, Object> settlement = new LinkedHashMap<>();
                    settlement.put("trade_date", day.toString());
                    settlement.put("status", signals.isEmpty() ? "VALUATION_ONLY" : "SETTLED");
                    settlement.put("reason_code", reasonCode);
                    settlement.put("reason", explanation);
                    settlement.put("fill_count", result.path("fills").size());
                    settlement.put("order_reasons", orderReasons);
                    settlement.put("risk_events", result.path("risk_events"));
                    settlement.put(
                            "prediction_ids",
                            predictions.stream()
                                    .map(p -> p.path("prediction_id").asText())
                                    .toList());
                    settlement.put("recorded_at", Instant.now());
                    store.db.update(
                            "update paper_account set cash=?,equity=?,peak_equity=?,locked=?,last_trade_date=?,"
                                    + "state=?::jsonb,last_settlement=?::jsonb where id=?",
                            decimal(state, "cash"),
                            decimal(state, "equity"),
                            decimal(state, "peak_equity"),
                            state.path("locked").asBoolean(),
                            Date.valueOf(day),
                            store.encode(state),
                            store.encode(settlement),
                            accountId);
                    // 此表仅是当前持仓投影；订单、成交、快照和账户历史均不删除。
                    store.db.update("delete from paper_position where account_id=?", accountId);
                    state.path("lots")
                            .fields()
                            .forEachRemaining(
                                    entry -> {
                                        BigDecimal quantity = BigDecimal.ZERO;
                                        BigDecimal cost = BigDecimal.ZERO;
                                        for (JsonNode lot : entry.getValue()) {
                                            quantity = quantity.add(decimal(lot, "quantity"));
                                            cost = cost.add(decimal(lot, "cost"));
                                        }
                                        if (quantity.signum() > 0) {
                                            store.db.update(
                                                    "insert into"
                                                            + " paper_position(id,account_id,symbol,quantity"
                                                            + ",cost_basis,lots)"
                                                            + " values(?,?,?,?,?,?::jsonb)",
                                                    UUID.randomUUID(),
                                                    accountId,
                                                    entry.getKey(),
                                                    quantity,
                                                    cost,
                                                    store.encode(entry.getValue()));
                                        }
                                    });
                    Map<String, UUID> orderIds = new HashMap<>();
                    for (JsonNode order : result.path("orders")) {
                        ObjectNode enrichedOrder = order.deepCopy();
                        for (JsonNode prediction : predictions) {
                            if (prediction
                                    .path("symbol")
                                    .asText()
                                    .equals(order.path("symbol").asText())) {
                                enrichedOrder.put(
                                        "prediction_id", prediction.path("prediction_id").asText());
                            }
                        }
                        UUID orderId = UUID.randomUUID();
                        orderIds.put(order.path("business_key").asText(), orderId);
                        store.db.update(
                                "insert into"
                                        + " paper_order(id,account_id,business_key,trade"
                                        + "_date,symbol,side,status,details)"
                                        + " values(?,?,?,?,?,?,?,?::jsonb)",
                                orderId,
                                accountId,
                                order.path("business_key").asText(),
                                Date.valueOf(day),
                                order.path("symbol").asText(),
                                order.path("side").asText(),
                                order.path("status").asText(),
                                store.encode(enrichedOrder));
                    }
                    for (JsonNode fill : result.path("fills")) {
                        store.db.update(
                                "insert into paper_fill(id,order_id,quantity,price,fee,details)"
                                        + " values(?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                orderIds.get(fill.path("business_key").asText()),
                                decimal(fill, "quantity"),
                                decimal(fill, "price"),
                                decimal(fill, "fee"),
                                store.encode(fill));
                    }
                    ObjectNode snapshot = result.path("snapshot").deepCopy();
                    snapshot.set("state", state);
                    snapshot.set("settlement", store.json.valueToTree(settlement));
                    store.db.update(
                            "insert into account_snapshot(id,account_id,trade_date,equity,cash,details)"
                                    + " values(?,?,?,?,?,?::jsonb)",
                            UUID.randomUUID(),
                            accountId,
                            Date.valueOf(day),
                            decimal(state, "equity"),
                            decimal(state, "cash"),
                            store.encode(snapshot));
                    for (JsonNode risk : result.path("risk_events")) {
                        store.db.update(
                                "insert into risk_event(id,account_id,trade_date,rule,details)"
                                        + " values(?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                accountId,
                                Date.valueOf(day),
                                risk.path("rule").asText(),
                                store.encode(risk));
                    }
                    if (signals.isEmpty()) {
                        store.db.update(
                                "insert into risk_event(id,account_id,trade_date,rule,details)"
                                        + " values(?,?,?,'PREDICTION_MISSING_OR_LATE','{}')",
                                UUID.randomUUID(),
                                accountId,
                                Date.valueOf(day));
                    }
                    return Map.of(
                            "account",
                            accountId,
                            "status",
                            signals.isEmpty() ? "VALUATION_ONLY" : "SETTLED",
                            "fills",
                            result.path("fills").size(),
                            "reason_code",
                            reasonCode,
                            "reason",
                            explanation,
                            "equity",
                            state.path("equity").asText());
                });
    }

    /** 无成交原因保存为当前状态投影；这不是成交，也不推进最后结算日。 */
    private Map<String, Object> recordStatus(
            String accountId, LocalDate day, String code, String reason) {
        Map<String, Object> result =
                Map.of(
                        "account",
                        accountId,
                        "trade_date",
                        day.toString(),
                        "status",
                        code,
                        "reason_code",
                        code,
                        "reason",
                        reason,
                        "recorded_at",
                        Instant.now());
        store.db.update(
                "update paper_account set last_settlement=?::jsonb where id=?",
                store.encode(result),
                accountId);
        return result;
    }

    /** 页面使用明确中文原因，不把配置启用误报为已经执行。 */
    public static String explanation(String code) {
        return switch (code) {
            case "STRATEGY_PAUSED" -> "策略已暂停；仅更新已有持仓估值";
            case "PREDICTION_MISSING_OR_LATE" -> "缺少开盘前生成的完整冻结预测；仅估值，不补造历史交易";
            case "TRADED" -> "已按冻结信号产生模拟成交";
            case "ORDER_REJECTED" -> "订单因资金、交易单位、T+1或流动性限制未成交，具体原因见订单记录";
            case "RISK_LOCKED" -> "账户触发回撤锁定，禁止继续买入";
            case "RISK_RESTRICTED" -> "风险或成交约束阻止交易，详见风险事件与订单记录";
            case "NO_BUY_SIGNAL" -> "模型未触发买入阈值或现金基准无需买入";
            case "TARGET_ALREADY_MET_OR_BELOW_LOT" -> "目标仓位已满足，或可买数量不足一个交易单位";
            default -> "尚无可核实的结算记录";
        };
    }

    /** 用初始资金、全部实际模拟成交、已到账分红及当前估值重建权益，不更改账户。 */
    public Map<String, Object> reconstruct(String accountId) {
        Map<String, Object> account =
                store.db.queryForMap(
                        "select initial_capital,cash,equity,state::text from paper_account where id=?",
                        accountId);
        JsonNode state = store.decode(account.get("state").toString());
        BigDecimal cash = (BigDecimal) account.get("initial_capital");
        BigDecimal fees = BigDecimal.ZERO;
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        List<Map<String, Object>> fills =
                store.db.queryForList(
                        "select o.symbol,o.side,o.trade_date,f.quantity,f.price,f.fee from paper_fill f"
                                + " join paper_order o on o.id=f.order_id where o.account_id=? "
                                + "order by o.trade_date,o.id",
                        accountId);
        for (Map<String, Object> fill : fills) {
            BigDecimal quantity = (BigDecimal) fill.get("quantity");
            BigDecimal amount =
                    quantity.multiply((BigDecimal) fill.get("price"))
                            .setScale(2, RoundingMode.HALF_UP);
            BigDecimal fee = (BigDecimal) fill.get("fee");
            boolean buy = "BUY".equals(fill.get("side"));
            cash = cash.add(buy ? amount.negate().subtract(fee) : amount.subtract(fee));
            fees = fees.add(fee);
            quantities.merge(
                    fill.get("symbol").toString(),
                    buy ? quantity : quantity.negate(),
                    BigDecimal::add);
        }
        // 已到账分红在每次不可变日快照中保存；不能把尚未到账应收款再次加入现金。
        List<JsonNode> snapshots =
                store.db.query(
                        "select details::text from account_snapshot where account_id=? order by "
                                + "trade_date desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        accountId);
        JsonNode snapshotState =
                snapshots.isEmpty() ? store.json.nullNode() : snapshots.get(0).path("state");
        BigDecimal dividends =
                new BigDecimal(snapshotState.path("cumulative_dividends").asText("0"));
        cash = cash.add(dividends);
        Map<String, BigDecimal> differences = new LinkedHashMap<>();
        var lots = state.path("lots").fields();
        while (lots.hasNext()) {
            var entry = lots.next();
            BigDecimal current = BigDecimal.ZERO;
            for (JsonNode lot : entry.getValue()) {
                current = current.add(decimal(lot, "quantity"));
            }
            differences.put(
                    entry.getKey(),
                    quantities.getOrDefault(entry.getKey(), BigDecimal.ZERO).subtract(current));
        }
        for (var entry : quantities.entrySet()) {
            differences.putIfAbsent(entry.getKey(), entry.getValue());
        }
        BigDecimal receivables = BigDecimal.ZERO;
        for (JsonNode item : snapshotState.path("receivables")) {
            receivables = receivables.add(decimal(item, "amount"));
        }
        BigDecimal equity = cash.add(receivables);
        for (var entry : quantities.entrySet()) {
            equity =
                    equity.add(
                            entry.getValue()
                                    .multiply(
                                            new BigDecimal(
                                                    state.path("last_prices")
                                                            .path(entry.getKey())
                                                            .asText("0"))));
        }
        BigDecimal cashDifference = cash.subtract((BigDecimal) account.get("cash"));
        BigDecimal equityDifference =
                equity.setScale(2, RoundingMode.HALF_UP)
                        .subtract((BigDecimal) account.get("equity"));
        boolean quantitiesMatch =
                differences.values().stream().allMatch(value -> value.signum() == 0);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("account", accountId);
        report.put("cash", cash);
        report.put("equity", equity);
        report.put("cash_difference", cashDifference);
        report.put("equity_difference", equityDifference);
        report.put("quantity_differences", differences);
        report.put("fees", fees);
        report.put("fill_count", fills.size());
        report.put("dividends_from_frozen_snapshot", dividends);
        report.put(
                "snapshot_state_available",
                !snapshotState.isMissingNode() && !snapshotState.isNull());
        report.put("latest_snapshot", snapshots.isEmpty() ? null : snapshots.get(0));
        report.put(
                "consistent",
                cashDifference.signum() == 0 && equityDifference.signum() == 0 && quantitiesMatch);
        report.put("limitations", "RAW价格口径；如有拆分/份额转换，必须逐项核对公司行为，差异不会自动修正");
        return report;
    }

    /** 从十进制字符串构造资金值，避免 Jackson 浮点数隐式转换。 */
    private BigDecimal decimal(JsonNode value, String field) {
        return new BigDecimal(value.path(field).asText());
    }
}
