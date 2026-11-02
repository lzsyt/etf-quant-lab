package cn.etfquantlab.research;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResearchReportService {
    private final Store store;
    private final ResearchViewService views;
    private final TransactionTemplate tx;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    // 最新行情日期与各ETF覆盖使用同一门禁；盘中抓取、未来抓取及观察推导日历不能充当正式输入。
    private static final String USABLE_MARKET =
            "b.nature='REAL' and b.price_basis='RAW' and b.quality_status in ('VALID','WARNING')"
                    + " and b.fetched_at >= (b.trade_date+time '15:15') at time zone 'Asia/Shanghai'"
                    + " and b.fetched_at<=now() and b.trade_date<=(now() at time zone 'Asia/Shanghai')::date"
                    + " and exists(select 1 from trading_calendar c where c.trade_date=b.trade_date"
                    + " and c.is_open and c.source not like 'OBSERVED_%')";

    /** 日报以真实数据库快照为来源，生成时间永远使用实际时钟。 */
    public ResearchReportService(Store store, ResearchViewService views, TransactionTemplate tx) {
        this.store = store;
        this.views = views;
        this.tx = tx;
    }

    /** 交易日日终追加冻结报告；相同内容重复执行复用已有版本，变化才追加新版本。 */
    public JsonNode generate(LocalDate day, Map<String, Object> dailyResult) {
        if (day.isAfter(LocalDate.now(BEIJING))
                || !Boolean.TRUE.equals(
                        store.db.queryForObject(
                                "select coalesce(bool_or(is_open),false) from trading_calendar"
                                        + " where trade_date=? and source not like 'OBSERVED_%'",
                                Boolean.class, Date.valueOf(day)))) {
            throw new IllegalArgumentException("日报只能归档已登记且已经到达的交易日");
        }
        Date latest =
                store.db.queryForObject(
                        "select max(b.trade_date) from market_bar b where " + USABLE_MARKET,
                        Date.class);
        if (latest == null || !day.equals(latest.toLocalDate())) {
            throw new IllegalArgumentException("只允许为最新已到达行情日生成观察报告；历史日报只能读取已有冻结版本");
        }
        ObjectNode report = store.json.createObjectNode();
        report.put("trade_date", day.toString());
        report.put("mode", "PAPER_ONLY");
        report.put("nature", "REAL");
        report.put("snapshot_semantics", "按实际生成时刻归档已存在记录，不倒造历史预测或当时不可得的日报");
        List<JsonNode> market =
                rows(
                        "select jsonb_build_object('symbol',i.symbol,'name',i.name,'cutoff',max(b.trade_date),"
                                + "'bars',count(distinct b.trade_date))::text from etf_instrument i"
                                + " left join market_bar b"
                                + " on b.symbol=i.symbol and "
                                + USABLE_MARKET
                                + " and b.trade_date<=? where i.enabled group by i.symbol,i.name order by i.symbol",
                        Date.valueOf(day));
        report.set("market", store.json.valueToTree(market));
        report.put(
                "market_coverage_count",
                market.stream()
                        .filter(m -> day.toString().equals(m.path("cutoff").asText()))
                        .count());
        report.put(
                "etf_count",
                store.db.queryForObject(
                        "select count(distinct symbol) from prediction where nature='REAL' and "
                                + "signal_date=? and direction<>'MISSING'",
                        Long.class,
                        Date.valueOf(day)));
        report.put(
                "market_cutoff",
                market.stream()
                        .filter(m -> m.hasNonNull("cutoff"))
                        .map(m -> m.path("cutoff").asText())
                        .min(String::compareTo)
                        .orElse("行情不足"));
        report.set(
                "predictions",
                store.json.valueToTree(
                        rows(
                                "select jsonb_build_object('id',p.id,'symbol',p.symbol,'strategy_id',p.strategy_id,"
                                        + "'model_version',p.model_version,'signal_date',"
                                        + "p.signal_date,'created_at',p.created_at,"
                                        + "'target_date',p.target_date,'horizon',p.horizon,'probability',p.probability,"
                                        + "'direction',p.direction,'raw_score',p.raw_score,"
                                        + "'score_semantics',p.score_semantics)::text"
                                        + " from prediction p where p.signal_date=? and p.nature='REAL' "
                                        + "order by p.strategy_id,p.symbol,p.horizon,p.created_at",
                                Date.valueOf(day))));
        report.set(
                "news",
                store.json.valueToTree(
                        rows(
                                "select jsonb_build_object('id',id,'status',status,'created_at',created_at,"
                                        + "'prompt_version',prompt_version,'model_version',"
                                        + "model_version,'source_ids',source_ids,"
                                        + "'summary',result->'result'->>'analysis_summary',"
                                        + "'error_code',error_code)::text"
                                        + " from llm_analysis where nature='REAL' and "
                                        + "created_at>=?::date at time zone 'Asia/Shanghai'"
                                        + " and created_at<(?::date+1) at time zone 'Asia/Shanghai' "
                                        + "order by created_at",
                                Date.valueOf(day),
                                Date.valueOf(day))));
        List<JsonNode> matured =
                rows(
                        "select jsonb_build_object('samples',count(*),'accuracy',avg(case when "
                                + "a.correct then 1.0 else 0.0 end))::text"
                                + " from prediction_actual a join prediction p on p.id=a.prediction_id"
                                + " where p.nature='REAL' and p.horizon=1 and a.correct is not null"
                                + " and a.target_date=(select max(trade_date) from trading_calendar "
                                + "where is_open and trade_date<?)",
                        Date.valueOf(day));
        report.set("previous_matured_accuracy", matured.get(0));
        report.put(
                "valid_prediction_samples",
                store.db.queryForObject(
                        "select count(*) from prediction_actual a join prediction p on p.id=a.prediction_id"
                                + " where p.nature='REAL' and p.horizon=1 and a.correct is not null and "
                                + "a.target_date<=?",
                        Long.class,
                        Date.valueOf(day)));
        report.set(
                "trades",
                store.json.valueToTree(
                        rows(
                                "select jsonb_build_object('account_id',o.account_id,'symbol',o.symbol,'side',o.side,"
                                        + "'quantity',f.quantity,'price',f.price,'fee',f.fee,"
                                        + "'trade_date',o.trade_date,'details',f.details)::text"
                                        + " from paper_fill f join paper_order o on o.id=f.order_id "
                                        + "where o.trade_date=? order by o.account_id,o.id",
                                Date.valueOf(day))));
        report.set("accounts", accountSummaries());
        report.set(
                "cost_usd",
                store.json.valueToTree(
                        store.db.queryForObject(
                                "select coalesce(sum(case when status='CONFIRMED' then actual_cost else "
                                        + "reserved_cost end),0)"
                                        + " from api_call_cost where created_at>=?::date at time zone 'Asia/Shanghai'"
                                        + " and created_at<(?::date+1) at time zone 'Asia/Shanghai'",
                                java.math.BigDecimal.class,
                                Date.valueOf(day),
                                Date.valueOf(day))));
        report.set(
                "anomalies",
                store.json.valueToTree(
                        rows(
                                "select jsonb_build_object('id',id,'business_key',business_key,'task_type',task_type,"
                                        + "'status',status,'error_code',error_code,'updated_at',"
                                        + "updated_at)::text from task_execution"
                                        + " where (status='FAILED' or (status='RUNNING' and "
                                        + "coalesce(heartbeat_at,updated_at)<now()-interval '10 "
                                        + "minutes'))"
                                        + " and updated_at>=?::date at time zone 'Asia/Shanghai'"
                                        + " and updated_at<(?::date+1) at time zone 'Asia/Shanghai' "
                                        + "order by updated_at",
                                Date.valueOf(day),
                                Date.valueOf(day))));
        ObjectNode execution = report.putObject("daily_execution");
        for (String field :
                List.of(
                        "trade_date",
                        "predictions",
                        "analysis",
                        "reconciled",
                        "status",
                        "reason")) {
            if (dailyResult.containsKey(field)) {
                execution.set(field, store.json.valueToTree(dailyResult.get(field)));
            }
        }
        report.put("conclusion", "样本不足，无法得出可靠结论；150个交易日门槛不等于投资有效性已经成立。");
        String digest = digest(report);
        return tx.execute(
                status -> {
                    store.lock("daily-report:" + day);
                    List<JsonNode> existing =
                            rows(
                                    "select row_to_json(r)::text from daily_research_report r where "
                                            + "report_date=? and input_hash=? order by version limit 1",
                                    Date.valueOf(day),
                                    digest);
                    if (!existing.isEmpty()) {
                        return existing.get(0);
                    }
                    int version =
                            store.db.queryForObject(
                                    "select coalesce(max(version),0)+1 from daily_research_report where report_date=?",
                                    Integer.class,
                                    Date.valueOf(day));
                    report.put("generated_at", Instant.now().toString());
                    UUID id = UUID.randomUUID();
                    store.db.update(
                            "insert into "
                                    + "daily_research_report(id,report_date,version,input_hash,report) "
                                    + "values(?,?,?,?,?::jsonb)",
                            id,
                            Date.valueOf(day),
                            version,
                            digest,
                            store.encode(report));
                    return get(id);
                });
    }

    /** 返回已冻结的历史版本，不根据今天的行情动态改写旧日报。 */
    public List<JsonNode> list() {
        return rows(
                "select row_to_json(r)::text from (select id,report_date,version,created_at,report"
                        + " from daily_research_report order by report_date desc,version desc limit 120) r");
    }

    /** 单份报告始终按不可变ID读取，缺失由统一错误处理报告。 */
    public JsonNode get(UUID id) {
        return store.db.queryForObject(
                "select row_to_json(r)::text from daily_research_report r where id=?",
                (rs, row) -> store.decode(rs.getString(1)),
                id);
    }

    /** 账户余额按实际结算日注明时点；未结算保留null，不拿报告日期伪装成已更新的账户快照。 */
    private JsonNode accountSummaries() {
        Map<String, JsonNode> settlementDates = new HashMap<>();
        for (JsonNode value :
                rows(
                        "select jsonb_build_object('id',id,'last_settlement_date',last_trade_date)::text"
                                + " from paper_account")) {
            settlementDates.put(value.path("id").asText(), value.path("last_settlement_date"));
        }
        JsonNode accounts = views.rankings("paper", 1).path("rows").deepCopy();
        for (JsonNode account : accounts) {
            // 模拟排行榜的strategy_id承载账户ID，因此按该键与账户结算记录一一匹配。
            ((ObjectNode) account)
                    .set(
                            "last_settlement_date",
                            settlementDates.get(account.path("strategy_id").asText()));
        }
        return accounts;
    }

    /** 摘要仅针对可展示研究内容，不包含秘密或认证材料。 */
    private String digest(JsonNode value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(store.encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法计算报告摘要", error);
        }
    }

    /** 固定SQL与参数化条件读取研究记录，用户不能传入表名或SQL。 */
    private List<JsonNode> rows(String sql, Object... arguments) {
        return store.db.query(sql, (rs, row) -> store.decode(rs.getString(1)), arguments);
    }
}
