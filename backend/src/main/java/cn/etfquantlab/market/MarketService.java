package cn.etfquantlab.market;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MarketService {
    private final Store store;
    private final QuantClient quant;
    private final TransactionTemplate transactions;

    /** 行情源请求在事务外，批量入库与业务去重在同一事务内。 */
    public MarketService(Store store, QuantClient quant, TransactionTemplate transactions) {
        this.store = store;
        this.quant = quant;
        this.transactions = transactions;
    }

    /** 显式选择真实、CSV 或演示数据源，每次同步均留下成功或失败报告。 */
    public JsonNode sync(Map<String, Object> request) {
        String symbol = String.valueOf(request.get("symbol"));
        String source = String.valueOf(request.getOrDefault("provider", "EASTMONEY"));
        if (store.db.queryForObject(
                        "select count(*) from etf_instrument where symbol=?", Integer.class, symbol)
                != 1) {
            throw new IllegalArgumentException("ETF代码尚未核实登记");
        }
        validateInstrument(symbol);
        UUID syncId = UUID.randomUUID();
        store.db.update(
                "insert into market_sync(id,symbol,source,status) values(?,?,?,'RUNNING')",
                syncId,
                symbol,
                source);
        try {
            Map<String, Object> payload = new HashMap<>(request);
            if (!"DEMO".equals(source)
                    && request.containsKey("start")
                    && request.containsKey("end")) {
                List<String> calendar =
                        store.db.query(
                                "select trade_date::text from trading_calendar"
                                        + " where is_open and trade_date between ? and ? order by trade_date",
                                (rs, row) -> rs.getString(1),
                                Date.valueOf(request.get("start").toString()),
                                Date.valueOf(request.get("end").toString()));
                payload.put("expected_dates", calendar);
            }
            JsonNode result = quant.post("/market/fetch", payload);
            transactions.executeWithoutResult(
                    status -> {
                        store.lock("market:" + symbol);
                        int skippedVerified = 0;
                        List<String> skippedDates = new ArrayList<>();
                        for (JsonNode bar : result.path("bars")) {
                            if (preserveVerifiedVolume(bar)) {
                                skippedVerified++;
                                skippedDates.add(bar.path("trade_date").asText());
                                continue;
                            }
                            saveBar(bar);
                        }
                        if (result.path("report") instanceof ObjectNode report) {
                            report.put("skipped_verified_volume_revisions", skippedVerified);
                            report.set(
                                    "skipped_verified_volume_dates",
                                    store.json.valueToTree(skippedDates));
                        }
                        store.db.update(
                                "update market_sync set status='SUCCEEDED',completed_at=now(),"
                                        + "report=?::jsonb where id=?",
                                store.encode(result.path("report")),
                                syncId);
                        store.audit(
                                "MARKET_SYNC",
                                Map.of(
                                        "symbol",
                                        symbol,
                                        "source",
                                        source,
                                        "count",
                                        result.path("bars").size()));
                    });
            return result.path("report");
        } catch (RuntimeException error) {
            store.db.update(
                    "update market_sync set status='FAILED',completed_at=now(),"
                            + "report=?::jsonb where id=?",
                    store.encode(Map.of("error", error.getClass().getSimpleName())),
                    syncId);
            throw error;
        }
    }

    /** 已由腾讯量核对且OHLC相同的正量行，不被新浪已确认的零量缺陷再次降级。 */
    private boolean preserveVerifiedVolume(JsonNode incoming) {
        if (!"REAL".equals(incoming.path("nature").asText())
                || !"RAW".equals(incoming.path("price_basis").asText())
                || !List.of("AKSHARE_SINA", "SINA").contains(incoming.path("source").asText())
                || new BigDecimal(incoming.path("volume").asText()).signum() != 0) {
            return false;
        }
        List<JsonNode> verified =
                store.db.query(
                        "select jsonb_build_object('open',open::text,'high',high::text,'low',"
                                + "low::text,'close',close::text)::text"
                                + " from market_bar where symbol=? and trade_date=? and "
                                + "nature='REAL' and price_basis='RAW'"
                                + " and source='SINA_PRICE_TENCENT_VOLUME_VERIFIED' and volume>0",
                        (rs, row) -> store.decode(rs.getString(1)),
                        incoming.path("symbol").asText(),
                        Date.valueOf(incoming.path("trade_date").asText()));
        if (verified.isEmpty()) {
            return false;
        }
        for (JsonNode previous : verified) {
            for (String field : List.of("open", "high", "low", "close")) {
                if (new BigDecimal(previous.path(field).asText())
                                .compareTo(new BigDecimal(incoming.path(field).asText()))
                        != 0) {
                    throw new IllegalArgumentException(
                            "已核验正量行与新浪新OHLC冲突，拒绝自动覆盖："
                                    + incoming.path("symbol").asText()
                                    + "/"
                                    + incoming.path("trade_date").asText());
                }
            }
        }
        return true;
    }

    /** 事务锁内按品种、日期、口径、性质与来源去重，修订不改变其他来源。 */
    private void saveBar(JsonNode bar) {
        Date day = Date.valueOf(bar.path("trade_date").asText());
        String symbol = bar.path("symbol").asText();
        String basis = bar.path("price_basis").asText();
        String nature = bar.path("nature").asText();
        String source = bar.path("source").asText();
        List<UUID> ids =
                store.db.query(
                        "select id from market_bar where symbol=? and trade_date=?"
                                + " and price_basis=? and nature=? and source=?",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        symbol,
                        day,
                        basis,
                        nature,
                        source);
        Object[] values = {
            bar.path("open").decimalValue(),
            bar.path("high").decimalValue(),
            bar.path("low").decimalValue(),
            bar.path("close").decimalValue(),
            new java.math.BigDecimal(bar.path("volume").asText()),
            bar.path("amount").isNull()
                    ? null
                    : new java.math.BigDecimal(bar.path("amount").asText()),
            Timestamp.from(Instant.parse(bar.path("fetched_at").asText())),
            bar.path("quality_status").asText(),
            store.encode(bar.path("quality_flags")),
            store.encode(bar.path("raw_values"))
        };
        // Python 的 Decimal 在 JSON 中为字符串，逐项转 BigDecimal 防止浮点结算。
        for (int index = 0; index < 4; index++) {
            values[index] =
                    new java.math.BigDecimal(
                            bar.path(new String[] {"open", "high", "low", "close"}[index])
                                    .asText());
        }
        List<Object> parameters = new ArrayList<>(List.of(symbol, day, basis, nature, source));
        java.util.Collections.addAll(parameters, values);
        if (ids.isEmpty()) {
            parameters.add(0, UUID.randomUUID());
            store.db.update(
                    "insert into market_bar(id,symbol,trade_date,price_basis,nature,source,"
                            + "open,high,low,close,volume,amount,fetched_at,quality_status,quality_flags,raw_values)"
                            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
                    parameters.toArray());
        } else {
            List<Object> updates = new ArrayList<>();
            java.util.Collections.addAll(updates, values);
            updates.add(ids.get(0));
            store.db.update(
                    "update market_bar set open=?,high=?,low=?,close=?,volume=?,amount=?,"
                            + "fetched_at=?,quality_status=?,quality_flags=?::jsonb,raw_values=?::jsonb,"
                            + "revision=revision+1 where id=?",
                    updates.toArray());
        }
        // 单条行情不是交易日历证据；异常源返回周末记录不得污染全系统日历。
    }

    /** 同一性质与价格口径下，每个日期选择最近抓取的真实来源；绝不跨性质拼接。 */
    public List<JsonNode> bars(
            String symbol, LocalDate start, LocalDate end, String nature, String basis) {
        return store.db.query(
                "select row_to_json(b)::text from (select distinct on (trade_date) symbol,"
                        + "trade_date,open::text,high::text,low::text,close::text,volume::text,amount::text,"
                        + "price_basis,source,nature,fetched_at,quality_status,quality_flags,raw_values"
                        + " from market_bar where symbol=? and trade_date between ? and ? and nature=?"
                        + " and price_basis=? and quality_status in ('VALID','WARNING')"
                        + " and (nature<>'REAL' or (fetched_at >= (trade_date+time '15:15') at time "
                        + "zone 'Asia/Shanghai'"
                        + " and trade_date <= (now() at time zone 'Asia/Shanghai')::date"
                        + " and exists(select 1 from trading_calendar c where "
                        + "c.trade_date=market_bar.trade_date and c.is_open)))"
                        + " order by trade_date,fetched_at desc) b",
                (rs, row) -> store.decode(rs.getString(1)),
                symbol,
                Date.valueOf(start),
                Date.valueOf(end),
                nature,
                basis);
    }

    /** 来源日历保留历史记录；官方公告覆盖的休市日期标记关闭，不删除预测或交易历史。 */
    public JsonNode syncCalendar() {
        JsonNode result = quant.post("/market/calendar", Map.of());
        transactions.executeWithoutResult(
                status -> {
                    store.lock("calendar");
                    for (JsonNode closed : result.path("closed_dates")) {
                        store.db.update(
                                "insert into trading_calendar(trade_date,source,is_open) values(?,?,false)"
                                        + " on conflict(trade_date) do update set is_open=false,"
                                        + "source=excluded.source,fetched_at=now()",
                                Date.valueOf(closed.asText()),
                                "OFFICIAL_EXCHANGE_CLOSED_2026");
                    }
                    for (JsonNode date : result.path("dates")) {
                        Date day = Date.valueOf(date.asText());
                        store.db.update(
                                "insert into trading_calendar(trade_date,source,is_open) values(?,?,true)"
                                        + " on conflict(trade_date) do update set "
                                        + "source=excluded.source,is_open=true,fetched_at=now()",
                                day,
                                result.path("source").asText());
                    }
                });
        return result;
    }

    /** 缺少日历记录返回未知，不能把未知覆盖误报为周末或节假日。 */
    public Boolean tradingDayStatus(LocalDate day) {
        List<Boolean> states =
                store.db.query(
                        "select is_open from trading_calendar where trade_date=? and source not like 'OBSERVED_%'",
                        (rs, row) -> rs.getBoolean(1), Date.valueOf(day));
        return states.isEmpty() ? null : states.get(0);
    }

    /** 核查登记与已确认的证券映射；不因名称相似而把中证500误归为上证50。 */
    public void validateInstrument(String symbol) {
        Map<String, Object> item =
                store.db.queryForMap("select * from etf_instrument where symbol=?", symbol);
        List<String> issues = instrumentIssues(item);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(
                    "ETF基础资料校验失败：" + symbol + " " + String.join("；", issues));
        }
    }

    /** 所有研究对象都需具备品种、交易所、整手及来源证据，三只初始ETF再做确定性映射核对。 */
    public static List<String> instrumentIssues(Map<String, Object> item) {
        List<String> issues = new ArrayList<>();
        String symbol = String.valueOf(item.get("symbol"));
        if (!symbol.matches("[0-9]{6}")) {
            issues.add("证券代码必须为6位数字");
        }
        if (!List.of("SSE", "SZSE").contains(String.valueOf(item.get("exchange")))) {
            issues.add("交易所未核实");
        }
        if (!"STOCK_ETF".equals(item.get("etf_type"))
                || ((Number) item.get("lot_size")).intValue() != 100
                || ((Number) item.get("settlement_days")).intValue() != 1) {
            issues.add("本研究池仅允许已核实的100份整手、T+1股票ETF");
        }
        Map<String, List<String>> reference =
                Map.of(
                        "510300", List.of("SSE", "沪深300", "华泰柏瑞"),
                        "510500", List.of("SSE", "中证500", "南方"),
                        "159915", List.of("SZSE", "创业板指", "易方达"));
        if (reference.containsKey(symbol)) {
            List<String> expected = reference.get(symbol);
            if (!expected.get(0).equals(item.get("exchange"))
                    || !expected.get(1).equals(item.get("tracking_index"))
                    || !String.valueOf(item.get("name")).contains(expected.get(2))
                    || !String.valueOf(item.get("name")).contains(expected.get(1).replace("指", ""))
                    || ("510500".equals(symbol)
                            && String.valueOf(item.get("name")).contains("上证50"))) {
                issues.add("名称、交易所或跟踪指数与核实基线不符");
            }
            BigDecimal limit = new BigDecimal(item.get("limit_rate").toString());
            if (limit.compareTo(new BigDecimal("159915".equals(symbol) ? "0.20" : "0.10")) != 0
                    || new BigDecimal(item.get("tick_size").toString())
                                    .compareTo(new BigDecimal("0.001"))
                            != 0) {
                issues.add("涨跌幅限制或最小报价单位与核实规则不符");
            }
        }
        if (item.get("verified_at") == null
                || !String.valueOf(item.get("source_url")).startsWith("https://")
                || !String.valueOf(item.get("rules_source_url")).startsWith("https://")) {
            issues.add("缺少证券资料或交易规则核实来源");
        }
        return issues;
    }

    /** 基于持久原始行情产生质量报告；未执行的跨源与含分红校验明确标示，不声称通过。 */
    public Map<String, Object> qualityReport() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> instrument :
                store.db.queryForList("select * from etf_instrument order by symbol")) {
            String symbol = instrument.get("symbol").toString();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", symbol);
            item.put("name", instrument.get("name"));
            item.put("metadata_issues", instrumentIssues(instrument));
            item.put(
                    "coverage",
                    store.db.queryForMap(
                            "select count(*) as rows,count(distinct trade_date) as dates,min(trade_date) as first_date,"
                                    + "max(trade_date) as last_date,count(distinct source) as source_count"
                                    + " from market_bar where symbol=? and nature='REAL' and price_basis='RAW'",
                            symbol));
            item.put(
                    "duplicate_keys",
                    store.db.queryForList(
                            "select trade_date,source,count(*) as count from market_bar where "
                                    + "symbol=? and nature='REAL'"
                                    + " and price_basis='RAW' group by trade_date,source having count(*)>1",
                            symbol));
            item.put(
                    "invalid_prices",
                    store.db.queryForObject(
                            "select count(*) from market_bar where symbol=? and nature='REAL' and (open<=0 or close<=0"
                                    + " or low<=0 or high<greatest(open,close,low) or "
                                    + "low>least(open,close,high) or volume<0)",
                            Integer.class,
                            symbol));
            item.put(
                    "zero_volume_dates",
                    store.db.query(
                            "select distinct trade_date::text from market_bar where symbol=? and nature='REAL'"
                                    + " and price_basis='RAW' and volume=0 order by trade_date::text",
                            (rs, row) -> rs.getString(1),
                            symbol));
            item.put("zero_volume_semantics", "零量仅是源数据现象，尚未核实停牌，不推定可成交");
            item.put(
                    "nonfinal_bars",
                    store.db.queryForObject(
                            "select count(*) from market_bar where symbol=? and nature='REAL' and fetched_at <"
                                    + " (trade_date+time '15:15') at time zone 'Asia/Shanghai'",
                            Integer.class,
                            symbol));
            item.put(
                    "missing_dates",
                    store.db.query(
                            "select c.trade_date::text from trading_calendar c where c.is_open and c.trade_date between"
                                    + " (select min(trade_date) from market_bar where symbol=? and "
                                    + "nature='REAL' and price_basis='RAW')"
                                    + " and (select max(trade_date) from market_bar where symbol=? "
                                    + "and nature='REAL' and price_basis='RAW')"
                                    + " and not exists(select 1 from market_bar b where b.symbol=? and b.nature='REAL'"
                                    + " and b.price_basis='RAW' and b.trade_date=c.trade_date and "
                                    + "b.quality_status in ('VALID','WARNING'))"
                                    + " order by c.trade_date",
                            (rs, row) -> rs.getString(1),
                            symbol,
                            symbol,
                            symbol));
            List<Map<String, Object>> differences =
                    store.db.queryForList(
                            "select trade_date,min(close) as minimum_close,max(close) as "
                                    + "maximum_close,count(distinct source) as sources"
                                    + " from market_bar where symbol=? and nature='REAL' and "
                                    + "price_basis='RAW' and close>0"
                                    + " group by trade_date having count(distinct source)>1 and "
                                    + "max(close)/min(close)-1>0.01 order by trade_date",
                            symbol);
            item.put("cross_source_anomalies", differences);
            Number sources = (Number) ((Map<?, ?>) item.get("coverage")).get("source_count");
            Integer overlap =
                    store.db.queryForObject(
                            "select count(*) from (select trade_date from market_bar where symbol=?"
                                    + " and nature='REAL' and price_basis='RAW' group by trade_date"
                                    + " having count(distinct source)>1) overlapping",
                            Integer.class,
                            symbol);
            List<JsonNode> verified =
                    store.db.query(
                            "select report::text from market_sync where symbol=? and source='TENCENT_CROSS_CHECK'"
                                    + " and status='VERIFIED' order by completed_at desc,started_at desc limit 1",
                            (rs, row) -> store.decode(rs.getString(1)),
                            symbol);
            Integer corrected =
                    store.db.queryForObject(
                            "select count(*) from market_bar where symbol=? and nature='REAL' and price_basis='RAW'"
                                    + " and source='SINA_PRICE_TENCENT_VOLUME_VERIFIED'",
                            Integer.class,
                            symbol);
            String crossSourceStatus;
            if (!differences.isEmpty()) {
                crossSourceStatus = "ANOMALY";
            } else if (!verified.isEmpty()) {
                crossSourceStatus =
                        corrected > 0 ? "SAMPLED_VERIFIED_WITH_CORRECTIONS" : "SAMPLED_VERIFIED";
            } else if (sources.intValue() < 2) {
                crossSourceStatus = "NOT_VERIFIED_SINGLE_SOURCE";
            } else if (overlap == 0) {
                crossSourceStatus = "NOT_VERIFIED_NO_OVERLAP";
            } else {
                crossSourceStatus = "CHECKED_NO_DIFFERENCE_OVER_1_PERCENT";
            }
            item.put("cross_source_status", crossSourceStatus);
            item.put("cross_source_overlap_dates", overlap);
            // V9将抽样摘要直接存入report；该查询已取出完整对象，不能再按不存在的嵌套键读取。
            item.put("cross_source_evidence", verified.isEmpty() ? List.of() : verified.get(0));
            item.put("verified_volume_corrections", corrected);
            item.put("price_basis", "RAW_PRICE_RETURN_DIVIDENDS_NOT_VERIFIED");
            item.put(
                    "demo_rows_excluded",
                    store.db.queryForObject(
                            "select count(*) from market_bar where symbol=? and nature<>'REAL'",
                            Integer.class,
                            symbol));
            items.add(item);
        }
        return Map.of(
                "generated_at",
                Instant.now(),
                "items",
                items,
                "source_policy",
                "REAL_ONLY; DEMO/CSV nature remains separate; no inferred missing bars",
                "cross_source_tolerance",
                "0.01",
                "corporate_actions",
                "UNVERIFIED_UNLESS_EXPLICIT_EVIDENCE");
    }
}
