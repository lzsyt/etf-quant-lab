package cn.etfquantlab.forecast;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将既有冻结记录投影为统一读模型；不迁移、不覆盖领域事实。 */
@Service
@Transactional(readOnly = true)
public class ForecastService {
    private static final int EVALUATION_LIMIT = 2000;
    private static final List<String> FUNDS = List.of("460300", "007169", "008701");
    private static final List<String> ETFS = List.of("510300", "510500", "159915");
    private final Store store;

    @Value("${etf.gpt-enabled:${ETF_GPT_ENABLED:false}}")
    private boolean gptEnabled;

    /** SQL只使用固定表名和参数化筛选，所有外部输入先核对能力矩阵。 */
    public ForecastService(Store store) {
        this.store = store;
    }

    /** 一个筛选完整描述资产、目标、期限及数据性质，避免同数字期限跨协议混评。 */
    record Selection(String asset, String target, int horizon, String mode) {}

    /** 目录描述代码已有能力；解释个体和未接入个体明确独立标记。 */
    public Map<String, Object> assets() {
        List<Map<String, Object>> assets = new ArrayList<>();
        List<String> names = List.of("华泰柏瑞沪深300ETF联接A", "易方达中债1–3年国开行债券指数A", "华夏黄金ETF联接A");
        for (int index = 0; index < FUNDS.size(); index++) {
            assets.add(
                    asset(
                            FUNDS.get(index),
                            names.get(index),
                            "FUND",
                            List.of(
                                    target(
                                            "FUND_NAV_UP",
                                            "基金短期净值上涨概率",
                                            List.of(5),
                                            "FUND_NAV_OBSERVATIONS",
                                            "PROBABILITY",
                                            List.of("REAL")),
                                    target(
                                            "FUND_NAV_CHANGE",
                                            "基金净值端点收益",
                                            List.of(20, 60),
                                            "FUND_NAV_OBSERVATIONS",
                                            "RETURN_POINT",
                                            List.of("REAL"))),
                            List.of("REAL")));
        }
        names = List.of("华泰柏瑞沪深300ETF", "南方中证500ETF", "易方达创业板ETF");
        for (int index = 0; index < ETFS.size(); index++) {
            assets.add(
                    asset(
                            ETFS.get(index),
                            names.get(index),
                            "ETF",
                            List.of(
                                    target(
                                            "ETF_FORWARD_OPEN",
                                            "新前瞻：次日开盘至随后开盘",
                                            List.of(1),
                                            "TRADING_DAYS",
                                            "PROBABILITY",
                                            List.of("REAL")),
                                    target(
                                            "ETF_CLOSE_RETURN",
                                            "ETF中期收盘价格变化",
                                            List.of(20, 60),
                                            "TRADING_DAYS",
                                            "RETURN_POINT",
                                            List.of("REAL")),
                                    target(
                                            "ETF_LEGACY_CLOSE",
                                            "旧研究：信号日收盘至目标日收盘",
                                            List.of(1, 5),
                                            "TRADING_DAYS",
                                            "PROBABILITY",
                                            List.of("REAL", "DEMO"))),
                            List.of("REAL", "DEMO")));
        }
        assets.add(
                asset(
                        "Au99.99",
                        "上海黄金交易所 Au99.99",
                        "GOLD",
                        List.of(
                                target(
                                        "GOLD_BENCHMARK_RETURN",
                                        "黄金基准收盘收益",
                                        List.of(63, 126),
                                        "SGE_TRADING_DAYS",
                                        "RETURN_INTERVAL_PROBABILITY",
                                        List.of("REAL"))),
                        List.of("REAL")));
        return Map.of(
                "assets",
                assets,
                "participants",
                List.of(
                        Map.of(
                                "id",
                                "quant",
                                "name",
                                "量化模型",
                                "role",
                                "PREDICTOR",
                                "status",
                                "NATIVE_CAPABILITIES",
                                "reason",
                                "按资产原生协议读取已保存模型与预测"),
                        Map.of(
                                "id",
                                "deepseek",
                                "name",
                                "DeepSeek",
                                "role",
                                "PREDICTOR",
                                "status",
                                "INDEPENDENT_FORECAST",
                                "reason",
                                "统一批次独立读取行情与资讯预测；旧情绪解释仍保留原角色，数据不足可弃权"),
                        Map.of(
                                "id",
                                "gpt",
                                "name",
                                "GPT",
                                "role",
                                "PREDICTOR",
                                "status",
                                gptEnabled ? "INDEPENDENT_FORECAST" : "DISABLED",
                                "reason",
                                "六资产短中期独立读取冻结行情和资讯，经指定代理调用；信息不足可弃权"),
                        Map.of(
                                "id", "gpt_review",
                                "name", "GPT分歧分析",
                                "role", "REVIEW",
                                "status", "DISABLED",
                                "reason", "分歧分析已停用，GPT仅做独立预测；已保存的历史分歧记录仍可查阅")));
    }

    /** 返回合法资产条目，不把基金联接关系当成同一预测资产。 */
    private Map<String, Object> asset(
            String id,
            String name,
            String type,
            List<Map<String, Object>> targets,
            List<String> modes) {
        return Map.of(
                "id",
                id,
                "code",
                id,
                "name",
                name,
                "asset_type",
                type,
                "targets",
                targets,
                "data_modes",
                modes);
    }

    /** 原生期限和数据模式同时对外公开，前端无需猜测支持矩阵。 */
    private Map<String, Object> target(
            String id,
            String label,
            List<Integer> horizons,
            String unit,
            String output,
            List<String> modes) {
        return Map.of(
                "id",
                id,
                "label",
                label,
                "horizons",
                horizons,
                "horizon_unit",
                unit,
                "output_type",
                output,
                "data_modes",
                modes);
    }

    /** 不支持组合明确失败，不把错误参数解释成空预测或自动换资产。 */
    Selection selection(String asset, String target, int horizon, String mode) {
        boolean valid =
                switch (target) {
                    case "FUND_NAV_UP" ->
                            FUNDS.contains(asset) && horizon == 5 && "REAL".equals(mode);
                    case "FUND_NAV_CHANGE" ->
                            FUNDS.contains(asset)
                                    && List.of(20, 60).contains(horizon)
                                    && "REAL".equals(mode);
                    case "GOLD_BENCHMARK_RETURN" ->
                            "Au99.99".equals(asset)
                                    && List.of(63, 126).contains(horizon)
                                    && "REAL".equals(mode);
                    case "ETF_FORWARD_OPEN" ->
                            ETFS.contains(asset) && horizon == 1 && "REAL".equals(mode);
                    case "ETF_CLOSE_RETURN" ->
                            ETFS.contains(asset)
                                    && List.of(20, 60).contains(horizon)
                                    && "REAL".equals(mode);
                    case "ETF_LEGACY_CLOSE" ->
                            ETFS.contains(asset)
                                    && List.of(1, 5).contains(horizon)
                                    && List.of("REAL", "DEMO").contains(mode);
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException("资产、目标、期限或数据模式组合不受支持");
        }
        return new Selection(asset, target, horizon, mode);
    }

    /** 采用limit加一探测下一页，不把截断列表表述为完整历史。 */
    public Map<String, Object> records(
            String asset, String target, int horizon, String mode, int limit, int offset) {
        Selection selection = selection(asset, target, horizon, mode);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000) {
            throw new IllegalArgumentException("分页范围为limit 1—100、offset 0—100000");
        }
        List<JsonNode> rows = read(selection, limit + 1, offset, null);
        boolean more = rows.size() > limit;
        List<JsonNode> records =
                normalizeRows(selection, rows.subList(0, Math.min(rows.size(), limit)));
        Map<String, Object> result = identity(selection);
        result.put("records", records);
        result.put("pagination", Map.of("limit", limit, "offset", offset, "has_more", more));
        result.put("reason", records.isEmpty() ? "当前组合暂无已保存预测；读取不会触发生成" : "");
        return result;
    }

    /** 详情筛选与主键在同一SQL内核验，拒绝跨资产或跨模式历史链接。 */
    public JsonNode detail(
            String source, UUID id, String asset, String target, int horizon, String mode) {
        Selection selection = selection(asset, target, horizon, mode);
        if ("unified".equals(source)) {
            List<JsonNode> unified = readUnified(selection, id);
            if (unified.isEmpty()) {
                throw new IllegalArgumentException("预测不存在或不属于当前筛选");
            }
            return normalize("unified", unified.get(0), selection);
        }
        if (!source(selection).equals(source)) {
            throw new IllegalArgumentException("预测来源与目标不匹配");
        }
        List<JsonNode> rows = read(selection, 1, 0, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预测不存在或不属于当前资产、目标、期限和数据模式");
        }
        ObjectNode record = (ObjectNode) normalize(source, rows.get(0), selection);
        ObjectNode evidence = rows.get(0).deepCopy();
        JsonNode details = rows.get(0).path("record").path("details");
        if ("legacy".equals(source)) {
            evidence.set(
                    "frozen_input",
                    reference(
                            "select snapshot::text from prediction_input where id::text=?",
                            details.path("input_snapshot_id").asText()));
            evidence.set(
                    "frozen_analysis",
                    reference(
                            "select jsonb_build_object('id',id,'input_cutoff',input_cutoff,"
                                    + "'status',status,'nature',nature,'result',result,"
                                    + "'input_snapshot',input_snapshot,'source_ids',source_ids,"
                                    + "'model_version',model_version,'prompt_version',prompt_version)"
                                    + "::text from llm_analysis where id::text=?",
                            details.path("analysis_id").asText()));
        } else if ("forward".equals(source)) {
            evidence.set(
                    "frozen_plan",
                    reference(
                            "select details::text from forward_plan where id::text=?",
                            rows.get(0).path("record").path("plan_id").asText()));
            evidence.set(
                    "frozen_analysis",
                    reference(
                            "select jsonb_build_object('id',id,'input_cutoff',input_cutoff,"
                                    + "'status',status,'input_snapshot',input_snapshot,'result',result)"
                                    + "::text from forward_analysis where id::text=?",
                            details.path("analysis_id").asText()));
        }
        evidence.put("association_rule", "只返回原记录及其冻结ID引用；未绑定的最新资讯不作为历史依据");
        record.set("evidence", evidence);
        record.set("analysis", ForecastAnalysis.frozen(store.json, record));
        return record;
    }

    /** 新方案与历史解释分别标记，最新资讯不冒充旧预测的输入。 */
    public JsonNode news(String asset, String target, int horizon, String mode) {
        Selection selection = selection(asset, target, horizon, mode);
        List<JsonNode> current =
                "REAL".equals(mode)
                        ? store.db.query(
                                "select jsonb_build_object('status',i.status,'execution_result',i.execution_result,"
                                        + "'input',i.input_snapshot->'news_request','catalog',i.quote->'evidence_catalog',"
                                        + "'details',r.details,'error_code',i.error_code,'updated_at',i.updated_at)::text"
                                        + " from forecast_run_item i left join forecast_result r on r.id=i.result_id"
                                        + " where i.asset=? and i.target=? and i.horizon=? and i.scheme='DEEPSEEK'"
                                        + " order by i.updated_at desc,i.id desc limit 1",
                                (rs, n) -> store.decode(rs.getString(1)),
                                asset,
                                target,
                                horizon)
                        : List.of();
        if (!current.isEmpty()) {
            JsonNode latest = current.get(0);
            ObjectNode result;
            if (latest.path("details").isObject()) {
                result = ForecastAnalysis.frozen(store.json, latest.path("details"));
            } else {
                ObjectNode raw = store.json.createObjectNode();
                raw.set("result", latest.path("execution_result"));
                raw.set("input_snapshot", latest.path("input"));
                raw.set("evidence_catalog", latest.path("catalog"));
                raw.set("error_code", latest.path("error_code"));
                raw.put("status", latest.path("status").asText());
                raw.set("generated_at", latest.path("updated_at"));
                raw.set("input_cutoff", latest.path("input").path("cutoff"));
                result = ForecastAnalysis.project(store.json, raw, asset);
            }
            result.put("context", "LATEST_INDEPENDENT_FORECAST");
            result.put("execution_status", latest.path("status").asText());
            result.put("role", "PREDICTOR");
            return result;
        }
        if ("unified".equals(source(selection))) {
            ObjectNode result =
                    ForecastAnalysis.project(store.json, store.json.createObjectNode(), asset);
            result.put("context", "LATEST_INDEPENDENT_FORECAST");
            result.put("role", "PREDICTOR");
            result.put("reason", "当前新增目标尚无已保存分析，不以其他期限的历史解释替代");
            return result;
        }
        String sql;
        Object[] args;
        switch (source(selection)) {
            case "fund" -> {
                sql =
                        "select row_to_json(a)::text from fund_news_call a where fund_code=? order by created_at desc limit 1";
                args = new Object[] {asset};
            }
            case "gold" -> {
                sql =
                        "select row_to_json(a)::text from gold_analysis a order by created_at desc limit 1";
                args = new Object[0];
            }
            case "forward" -> {
                sql =
                        "select row_to_json(a)::text from forward_analysis a order by created_at desc limit 1";
                args = new Object[0];
            }
            default -> {
                sql =
                        "select row_to_json(a)::text from llm_analysis a where nature=? order by created_at desc limit 1";
                args = new Object[] {mode};
            }
        }
        List<JsonNode> rows = store.db.query(sql, (rs, n) -> store.decode(rs.getString(1)), args);
        ObjectNode result =
                ForecastAnalysis.project(
                        store.json,
                        rows.isEmpty() ? store.json.createObjectNode() : rows.get(0),
                        asset);
        result.put("context", "LATEST_LEGACY_EXPLANATION");
        result.put("role", "EXPLANATION");
        result.put("reason", "最新已保存资讯解释；不是当前目标的独立预测，也不替换历史冻结依据");
        return result;
    }

    /** 通过原引用精确读取，缺失时保持空值，禁止用最新分析补齐。 */
    private JsonNode reference(String sql, String id) {
        if (id.isBlank()) {
            return store.json.nullNode();
        }
        List<JsonNode> rows =
                store.db.query(sql, (rs, number) -> store.decode(rs.getString(1)), id);
        return rows.isEmpty() ? store.json.nullNode() : rows.get(0);
    }

    /** 有界评价公开取样范围，不以记录比例冒充完整调度机会覆盖率。 */
    public Map<String, Object> evaluations(String asset, String target, int horizon, String mode) {
        Selection selection = selection(asset, target, horizon, mode);
        List<JsonNode> rows = readEvaluation(selection);
        List<JsonNode> records = new ArrayList<>();
        java.util.Set<String> included = new java.util.HashSet<>();
        int matureRecords = 0;
        int currentReferences = 0;
        boolean truncated = false;
        for (JsonNode row : rows) {
            boolean current = "CURRENT_POLICY_ONLY".equals(row.path("sample_role").asText());
            String recordIdentity =
                    row.path("source").asText() + ":" + row.path("record").path("id").asText();
            if (current && included.contains(recordIdentity)) continue;
            if (!current && matureRecords >= EVALUATION_LIMIT) {
                truncated = true;
                continue;
            }
            ObjectNode record = (ObjectNode) normalize(row.path("source").asText(), row, selection);
            if (current) {
                // 最新但未入成熟共同样本的记录仅确定当前方案，不能第二次获得评分权重。
                record.put("evaluation_eligible", false);
                record.put("evaluation_reason", "CURRENT_POLICY_REFERENCE_NOT_SCORED");
                currentReferences++;
            } else {
                matureRecords++;
            }
            records.add(record);
            included.add(recordIdentity);
        }
        Map<String, Object> result = identity(selection);
        result.put("groups", ForecastEvaluation.groups(records));
        result.put("truncated", truncated);
        result.put("record_limit", EVALUATION_LIMIT);
        result.put("records_scanned", records.size());
        result.put("mature_records_sampled", matureRecords);
        result.put("current_policy_references", currentReferences);
        result.put("current_reference_limit", 20);
        result.put("nonoverlapping_window_limit_per_protocol", 100);
        result.put("coverage", coverage(selection));
        result.put("reason", "按共同预测机会比较不同个体；新批次公开实际执行覆盖，旧记录无分母时不推算覆盖率。推荐需通过时间留出与基线门槛。");
        result.put(
                "sample_rule",
                "先取已有实际终点的成熟记录；每个同协议目标窗口选择独立方案覆盖最多、其次截止最新的共同批次，按方案与制品去重。"
                        + "每协议从方案覆盖最多的窗口中由近到远优先保留最多100个不重叠窗口，其余名额按近期逐日记录补齐，总计最多2000条。"
                        + "另取各方案最新记录仅确定当前规则，不计评分。长周期选优仍需共同样本与可得时间清除；截断时不代表完整历史。");
        return result;
    }

    /** 成熟记录先取共同批次，再保留不重叠窗口；长周期不能被近期逐日重叠标签挤满限额。 */
    private List<JsonNode> readEvaluation(Selection selection) {
        ReadQuery query = readQuery(selection, null);
        String sql =
                "with recursive data as ("
                        + query.sql()
                        + "), timed as ("
                        + "select data.*,"
                        + "case when pg_input_is_valid(eval_cutoff,'timestamp with time zone') then eval_cutoff::timestamptz end as cutoff_at,"
                        + "case when pg_input_is_valid(eval_anchor,'date') then eval_anchor::date end as anchor_day,"
                        + "case when pg_input_is_valid(eval_target,'date') then eval_target::date end as target_day from data),"
                        + "mature as (select * from timed where eval_independent and eval_model is not null and eval_model<>'' "
                        + "and eval_model<>'UNKNOWN' and cutoff_at<=generated_at and anchor_day<target_day "
                        + "and target_day<=(now() at time zone 'Asia/Shanghai')::date "
                        + "and (generated_at at time zone 'Asia/Shanghai')::date<target_day "
                        + "and payload->'actual'->>'actual_return' is not null),"
                        + "cutoffs as (select eval_protocol,anchor_day,target_day,cutoff_at,count(distinct eval_scheme) as coverage "
                        + "from mature group by eval_protocol,anchor_day,target_day,cutoff_at),"
                        + "ranked_cutoffs as (select *,row_number() over(partition by eval_protocol,anchor_day,target_day "
                        + "order by coverage desc,cutoff_at desc) as choice from cutoffs),"
                        + "chosen as (select m.*,row_number() over(partition by m.eval_protocol,m.anchor_day,m.target_day,"
                        + "m.eval_scheme,m.eval_model order by m.generated_at desc,m.id desc) as revision "
                        + "from mature m join ranked_cutoffs c on m.eval_protocol=c.eval_protocol "
                        + "and m.anchor_day=c.anchor_day and m.target_day=c.target_day and m.cutoff_at=c.cutoff_at where c.choice=1),"
                        + "window_choices as (select eval_protocol,anchor_day,target_day,count(distinct eval_scheme) as coverage "
                        + "from chosen where revision=1 group by eval_protocol,anchor_day,target_day),"
                        + "covered_windows as (select *,max(coverage) over(partition by eval_protocol) as max_coverage from window_choices),"
                        + "priority_windows as (select * from covered_windows where coverage=max_coverage),"
                        // 逆序贪心与选优规则一致；每协议最多递归100步，历史再长也不放大应用侧内存。
                        + "spaced as ((select distinct on(eval_protocol) eval_protocol,anchor_day,target_day,1 as depth "
                        + "from priority_windows order by eval_protocol,anchor_day desc,target_day desc) union all "
                        + "select newer.eval_protocol,older.anchor_day,older.target_day,newer.depth+1 from spaced newer "
                        + "cross join lateral (select anchor_day,target_day from priority_windows w "
                        + "where w.eval_protocol=newer.eval_protocol and w.target_day<=newer.anchor_day "
                        + "order by anchor_day desc,target_day desc limit 1) older where newer.depth<100),"
                        + "history as (select c.*,case when exists(select 1 from spaced s "
                        + "where s.eval_protocol=c.eval_protocol and s.anchor_day=c.anchor_day and s.target_day=c.target_day) "
                        + "then 0 else 1 end as sample_priority from chosen c where revision=1 "
                        + "order by sample_priority,target_day desc,anchor_day desc,cutoff_at desc,eval_scheme,eval_model,id desc limit ?),"
                        + "current_choices as (select distinct on(eval_protocol,eval_scheme) * from timed where eval_independent "
                        + "order by eval_protocol,eval_scheme,generated_at desc,id desc),"
                        + "current_records as (select * from current_choices order by generated_at desc,id desc limit 20) "
                        + "select sampled::text from (select payload||jsonb_build_object('sample_role','MATURE_WINDOW') as sampled,"
                        + "0 as kind,sample_priority,target_day,anchor_day,cutoff_at,eval_scheme,eval_model,id from history "
                        + "union all select payload||jsonb_build_object('sample_role','CURRENT_POLICY_ONLY'),"
                        + "1,0,target_day,anchor_day,cutoff_at,eval_scheme,eval_model,id from current_records) bounded "
                        + "order by kind,sample_priority,target_day desc,anchor_day desc,cutoff_at desc,eval_scheme,eval_model,id desc";
        List<Object> args = new ArrayList<>(query.args());
        args.add(EVALUATION_LIMIT + 1);
        return store.db.query(sql, (rs, number) -> store.decode(rs.getString(1)), args.toArray());
    }

    /** 只对统一执行项提供真实分母，失败、弃权与复用均保留各自状态。 */
    private List<Map<String, Object>> coverage(Selection selection) {
        if (!"REAL".equals(selection.mode())) return List.of();
        return store.db.queryForList(
                "select scheme,count(*) as planned,"
                        + "count(*) filter(where result_id is not null) as available,"
                        + "count(*) filter(where status='REUSED') as reused,"
                        + "count(*) filter(where status='ABSTAIN') as abstained,"
                        + "count(*) filter(where status='FAILED') as failed,"
                        + "count(*) filter(where status='BLOCKED') as blocked,"
                        + "count(*) filter(where status in ('QUEUED','RUNNING')) as pending "
                        + "from forecast_run_item where asset=? and target=? and horizon=? group by scheme order by scheme",
                selection.asset(),
                selection.target(),
                selection.horizon());
    }

    /** 查询响应保留完整筛选身份，异步页面可核对响应是否仍属当前上下文。 */
    private Map<String, Object> identity(Selection selection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", selection.asset());
        result.put("target", selection.target());
        result.put("horizon", selection.horizon());
        result.put("mode", selection.mode());
        return result;
    }

    /** 原生存储来源由已验证目标决定，用户不能直接选择任意表。 */
    private String source(Selection selection) {
        return switch (selection.target()) {
            case "FUND_NAV_UP", "ETF_CLOSE_RETURN" -> "unified";
            case "FUND_NAV_CHANGE" -> "fund";
            case "GOLD_BENCHMARK_RETURN" -> "gold";
            case "ETF_FORWARD_OPEN" -> "forward";
            default -> "legacy";
        };
    }

    /** 列表与评价复用完全相同的来源筛选，查询参数不包含外部SQL片段。 */
    private record ReadQuery(String sql, List<Object> args) {}

    /** 普通列表仍按生成时间分页，不改变用户正在阅读的记录顺序。 */
    private List<JsonNode> read(Selection selection, int limit, int offset, UUID id) {
        ReadQuery query = readQuery(selection, id);
        String sql =
                "select payload::text from ("
                        + query.sql()
                        + ") combined order by generated_at desc,id desc limit ? offset ?";
        List<Object> args = new ArrayList<>(query.args());
        args.add(limit);
        args.add(offset);
        return store.db.query(sql, (rs, number) -> store.decode(rs.getString(1)), args.toArray());
    }

    /** 保留协议元信息但列表剔除大体积冻结输入，评价附加固定窗口键供数据库有界取样。 */
    private ReadQuery readQuery(Selection selection, UUID id) {
        String source = source(selection);
        if ("unified".equals(source)) return unifiedQuery(selection, id);
        String projection = "to_jsonb(p)";
        if (id == null) {
            projection =
                    "(to_jsonb(p)||jsonb_build_object('details',p.details-'input_revisions'-'input_snapshot'-'news_snapshot',"
                            + "'input_identity',coalesce(p.details->>'data_snapshot_id',"
                            + "p.details->'input_snapshot'->>'observations_sha256',"
                            + "p.details->>'input_snapshot_id')))";
        }
        String from;
        String actual = "a.details";
        String where;
        String scheme;
        String model;
        String protocol;
        String anchor;
        String target;
        String cutoff;
        String independent;
        List<Object> args = new ArrayList<>();
        switch (source) {
            case "fund" -> {
                from =
                        "fund_forecast p left join lateral (select details from fund_forecast_evaluation e"
                                + " where e.forecast_id=p.id order by evaluated_at desc,id desc limit 1) a on true";
                where = "p.fund_code=? and p.horizon=? and p.details->>'nature'=?";
                scheme =
                        "case when p.details->>'algorithm'='xgboost' then 'FUND_XGBOOST' else 'FUND_LIGHTGBM' end";
                model = "p.details->>'model_version_id'";
                protocol = "p.details->>'target_version'";
                anchor = "p.details->>'anchor_date'";
                target =
                        "coalesce(a.details->>'actual_target_date',a.details->>'target_date',p.details->'points'->0->>'target_date')";
                cutoff = "p.details->>'input_cutoff_at'";
                independent = "true";
                args.addAll(List.of(selection.asset(), selection.horizon(), selection.mode()));
            }
            case "gold" -> {
                from = "gold_prediction p left join gold_actual a on a.prediction_id=p.id";
                where = "p.horizon=?";
                scheme =
                        "case when p.details->>'algorithm'='xgboost' then 'GOLD_XGBOOST' else 'GOLD_LIGHTGBM' end";
                model = "p.model_id";
                protocol = "p.details->>'label_version'";
                anchor = "p.reference_date::text";
                target =
                        "coalesce(a.details->>'actual_target_date',a.details->>'target_date',p.details->>'target_date')";
                cutoff = "p.details->>'data_cutoff'";
                independent = "true";
                args.add(selection.horizon());
            }
            case "forward" -> {
                from =
                        "forward_decision p join forward_experiment e on e.id=p.experiment_id left join forward_actual a on a.decision_id=p.id";
                where = "p.symbol=? and e.nature=? and p.details->>'horizon'=?";
                scheme = "p.details->>'strategy'";
                model = "p.details->'ml'->>'model_id'";
                protocol = "(p.details->>'label_version')||'/'||(p.details->>'protocol_version')";
                anchor = "p.entry_date::text";
                target =
                        "coalesce(a.details->>'actual_target_date',a.details->>'target_date',p.evaluation_date::text)";
                cutoff = "p.details->'ml'->>'input_cutoff_at'";
                independent = "p.details->>'strategy' in ('LGBM_BASE','XGB_BASE')";
                args.addAll(
                        List.of(
                                selection.asset(),
                                selection.mode(),
                                Integer.toString(selection.horizon())));
            }
            default -> {
                from = "prediction p left join prediction_actual a on a.prediction_id=p.id";
                actual = "to_jsonb(a)";
                where = "p.symbol=? and p.horizon=? and p.nature=?";
                scheme = "p.strategy_id";
                model = "p.model_version";
                protocol =
                        "'LEGACY_CLOSE_PRICE_RETURN/'||(p.details->>'evidence_version')||'/'||(p.details->>'price_basis')";
                anchor = "p.signal_date::text";
                target =
                        "coalesce(a.target_date::text,p.target_date::text,p.details->>'target_date')";
                cutoff = "p.input_cutoff::text";
                independent = "p.strategy_id in ('lightgbm','xgboost')";
                args.addAll(List.of(selection.asset(), selection.horizon(), selection.mode()));
            }
        }
        if (id != null) {
            where += " and p.id=?";
            args.add(id);
        }
        String generated = "legacy".equals(source) ? "p.created_at" : "p.generated_at";
        // 只读取原生冻结表与附属实际结果；外部行情与模型服务不参与本查询。
        String sql =
                "select jsonb_build_object('source','"
                        + source
                        + "','record',"
                        + projection
                        + ",'actual',"
                        + actual
                        + ") as payload,"
                        + generated
                        + " as generated_at,p.id,"
                        + scheme
                        + " as eval_scheme,"
                        + model
                        + " as eval_model,"
                        + protocol
                        + " as eval_protocol,"
                        + anchor
                        + " as eval_anchor,"
                        + target
                        + " as eval_target,"
                        + cutoff
                        + " as eval_cutoff,"
                        + independent
                        + " as eval_independent from "
                        + from
                        + " where "
                        + where;
        if (id == null) {
            ReadQuery unified = unifiedQuery(selection, null);
            sql += " union all " + unified.sql();
            args.addAll(unified.args());
        }
        return new ReadQuery(sql, args);
    }

    /** 新目标只读统一冻结事实，旧目标可联合读取；同一投影保证分页与选优取样一致。 */
    private ReadQuery unifiedQuery(Selection selection, UUID id) {
        String details = id == null ? "p.details-'evidence'-'input_snapshot'" : "p.details";
        String sql =
                "select jsonb_build_object('source','unified','record',"
                        + "to_jsonb(p)||jsonb_build_object('details',"
                        + details
                        + "),"
                        + "'actual',a.details) as payload,p.generated_at,p.id,p.scheme as eval_scheme,"
                        + "p.details->>'model_version' as eval_model,p.details->>'target_version' as eval_protocol,"
                        + "p.details->>'anchor_date' as eval_anchor,"
                        + "coalesce(a.details->>'actual_target_date',a.details->>'target_date',"
                        + "p.details->>'target_date') as eval_target,"
                        + "p.details->>'data_cutoff' as eval_cutoff,p.details->>'role'='PREDICTOR' as "
                        + "eval_independent from forecast_result p "
                        + "left join lateral(select details from forecast_result_actual a where a.forecast_id=p.id"
                        + " order by evaluated_at desc limit 1) a on true"
                        + " where p.asset=? and p.target=? and p.horizon=? and p.details->>'mode'=?";
        List<Object> args =
                new ArrayList<>(
                        List.of(
                                selection.asset(),
                                selection.target(),
                                selection.horizon(),
                                selection.mode()));
        if (id != null) {
            sql += " and p.id=?";
            args.add(id);
        }
        return new ReadQuery(sql, args);
    }

    /** 统一预测详情单独核验完整身份，避免按来源猜测或跨资产读取。 */
    private List<JsonNode> readUnified(Selection selection, UUID id) {
        return store.db.query(
                "select jsonb_build_object('record',to_jsonb(p),'actual',a.details)::text from forecast_result p"
                        + " left join lateral(select details from forecast_result_actual a where a.forecast_id=p.id"
                        + " order by evaluated_at desc limit 1) a on true"
                        + " where p.id=? and p.asset=? and p.target=? and p.horizon=? and p.details->>'mode'=?",
                (rs, n) -> store.decode(rs.getString(1)),
                id,
                selection.asset(),
                selection.target(),
                selection.horizon(),
                selection.mode());
    }

    /** 统一转换不改变原记录，列表顺序与SQL分页顺序保持一致。 */
    private List<JsonNode> normalizeRows(Selection selection, List<JsonNode> rows) {
        return rows.stream()
                .map(row -> normalize(row.path("source").asText(source(selection)), row, selection))
                .toList();
    }

    /** 原生字段映射显式分支，缺失数值保持null，策略分数绝不换算成概率。 */
    JsonNode normalize(String source, JsonNode row, Selection selection) {
        if ("unified".equals(source)) {
            return normalizeUnified(row, selection);
        }
        JsonNode nativeRecord = row.path("record");
        JsonNode details = nativeRecord.path("details");
        JsonNode actual = row.path("actual");
        ObjectNode result = store.json.valueToTree(identity(selection));
        result.put("id", nativeRecord.path("id").asText());
        result.put("source", source);
        result.put(
                "generated_at",
                nativeRecord.path("generated_at").asText(nativeRecord.path("created_at").asText()));
        result.put("role", "PREDICTOR");
        result.put("status", "FROZEN");
        result.put("evidence_status", "FROZEN_RECORD_AVAILABLE");
        result.put("explanation_status", "NOT_LINKED_TO_RECORD");
        result.put(
                "model_validation_status",
                details.path("validation_status")
                        .asText(details.path("quality").path("status").asText("UNKNOWN")));
        result.put(
                "probability_validation_status",
                details.path("probability_validation_status").asText("UNKNOWN"));
        result.set("evaluation", actual.isMissingNode() ? store.json.nullNode() : actual);
        result.set(
                "reason_codes",
                details.path("reason_codes").isArray()
                        ? details.path("reason_codes")
                        : store.json.createArrayNode());
        ObjectNode output = result.putObject("output");
        for (String field :
                List.of(
                        "point_return",
                        "probability_up",
                        "interval_lower",
                        "interval_upper",
                        "score")) {
            output.putNull(field);
        }
        boolean eligible;
        String inputIdentity = nativeRecord.path("input_identity").asText("");
        switch (source) {
            case "fund" -> {
                result.put(
                        "scheme",
                        "xgboost".equals(details.path("algorithm").asText())
                                ? "FUND_XGBOOST"
                                : "FUND_LIGHTGBM");
                result.put("model_version", details.path("model_version_id").asText("UNKNOWN"));
                result.put("target_version", details.path("target_version").asText("UNKNOWN"));
                result.put(
                        "horizon_unit",
                        details.path("horizon_unit").asText("FUND_NAV_OBSERVATIONS"));
                result.put("output_type", "RETURN_POINT");
                result.put("data_cutoff", details.path("input_cutoff_at").asText());
                result.put("anchor_date", details.path("anchor_date").asText());
                result.put(
                        "target_date",
                        nullableText(details.path("points").path(0).path("target_date")));
                copy(output, "point_return", details.path("points").path(0).path("point_return"));
                inputIdentity = details.path("data_snapshot_id").asText(inputIdentity);
                result.put(
                        "explanation_status",
                        details.hasNonNull("news_analysis_id")
                                ? "FROZEN_REFERENCE"
                                : "NOT_LINKED_TO_RECORD");
                eligible =
                        "EVALUATED".equals(actual.path("evaluation_status").asText())
                                && "RETURN_POINT".equals(details.path("capability").asText())
                                && "fund-nav-change-20-60-v1"
                                        .equals(details.path("target_version").asText())
                                && "FUND_NAV_OBSERVATIONS"
                                        .equals(details.path("horizon_unit").asText());
            }
            case "gold" -> {
                result.put(
                        "scheme",
                        "xgboost".equals(details.path("algorithm").asText())
                                ? "GOLD_XGBOOST"
                                : "GOLD_LIGHTGBM");
                result.put(
                        "model_version",
                        nativeRecord
                                .path("model_id")
                                .asText(details.path("model_id").asText("UNKNOWN")));
                result.put("target_version", details.path("label_version").asText("UNKNOWN"));
                result.put("horizon_unit", "SGE_TRADING_DAYS");
                result.put("output_type", "RETURN_INTERVAL_PROBABILITY");
                result.put("data_cutoff", details.path("data_cutoff").asText());
                result.put(
                        "anchor_date",
                        nativeRecord
                                .path("reference_date")
                                .asText(details.path("reference_date").asText()));
                result.put("target_date", nullableText(details.path("target_date")));
                copy(output, "point_return", details.path("median_return"));
                copy(output, "probability_up", details.path("probability_up"));
                copy(output, "interval_lower", details.path("interval_lower"));
                copy(output, "interval_upper", details.path("interval_upper"));
                output.put("interval_unit", "CNY_PER_GRAM");
                inputIdentity =
                        details.path("input_snapshot")
                                .path("observations_sha256")
                                .asText(inputIdentity);
                eligible =
                        "MATURED".equals(actual.path("status").asText())
                                && "SGE_CLOSE_RETURN_63_126_V1"
                                        .equals(details.path("label_version").asText());
            }
            case "forward" -> {
                String strategy = details.path("strategy").asText("UNKNOWN");
                boolean independent = List.of("LGBM_BASE", "XGB_BASE").contains(strategy);
                result.put("scheme", strategy);
                result.put("model_version", details.path("ml").path("model_id").asText("UNKNOWN"));
                result.put(
                        "target_version",
                        details.path("label_version").asText("UNKNOWN")
                                + "/"
                                + details.path("protocol_version").asText("UNKNOWN"));
                result.put("horizon_unit", "TRADING_DAYS");
                result.put("output_type", independent ? "PROBABILITY" : "STRATEGY_SCORE");
                result.put("role", independent ? "PREDICTOR" : "STRATEGY_EXPERIMENT");
                result.put("data_cutoff", details.path("ml").path("input_cutoff_at").asText());
                result.put("anchor_date", nativeRecord.path("entry_date").asText());
                result.put("target_date", nativeRecord.path("evaluation_date").asText());
                if (independent) {
                    copy(output, "probability_up", details.path("ml").path("probability"));
                } else {
                    copy(output, "score", details.path("score"));
                }
                result.put(
                        "status",
                        "SUCCEEDED".equals(details.path("ml").path("status").asText())
                                ? "FROZEN"
                                : "MISSING");
                result.withArray("reason_codes").add(details.path("reason_code").asText("UNKNOWN"));
                inputIdentity = details.path("ml").path("input_sha256").asText("");
                result.put(
                        "explanation_status",
                        details.hasNonNull("analysis_id")
                                ? "FROZEN_REFERENCE"
                                : "NOT_LINKED_TO_RECORD");
                eligible =
                        independent
                                && "EVALUATED".equals(actual.path("status").asText())
                                && "SUCCEEDED".equals(details.path("ml").path("status").asText())
                                && "OPEN_TO_OPEN_H1_V1"
                                        .equals(details.path("label_version").asText())
                                && "OPEN_NET_REBALANCE_V1"
                                        .equals(details.path("protocol_version").asText())
                                && "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
                                        .equals(details.path("target_semantics").asText())
                                && "RAW".equals(details.path("ml").path("price_basis").asText())
                                && "REAL".equals(details.path("ml").path("nature").asText())
                                && selection
                                        .asset()
                                        .equals(details.path("ml").path("symbol").asText())
                                && "FORWARD_PRODUCTION"
                                        .equals(details.path("ml").path("model_role").asText())
                                && "OPEN_TO_OPEN_H1_V1"
                                        .equals(details.path("ml").path("label_version").asText())
                                && "OPEN_NET_REBALANCE_V1"
                                        .equals(
                                                details.path("ml")
                                                        .path("protocol_version")
                                                        .asText())
                                && "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
                                        .equals(
                                                details.path("ml")
                                                        .path("target_semantics")
                                                        .asText())
                                && details.path("ml").path("horizon").asInt() == 1
                                && "OPEN_TO_OPEN_H1_V1"
                                        .equals(actual.path("label_version").asText())
                                && "NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
                                        .equals(actual.path("target_semantics").asText());
            }
            default -> {
                String strategy = nativeRecord.path("strategy_id").asText("UNKNOWN");
                boolean independent = List.of("lightgbm", "xgboost").contains(strategy);
                result.put("scheme", strategy);
                result.put("model_version", nativeRecord.path("model_version").asText("UNKNOWN"));
                result.put(
                        "target_version",
                        "LEGACY_CLOSE_PRICE_RETURN/"
                                + details.path("evidence_version").asText("UNKNOWN")
                                + "/"
                                + details.path("price_basis").asText("UNKNOWN"));
                result.put("horizon_unit", "TRADING_DAYS");
                result.put(
                        "output_type",
                        independent
                                ? "PROBABILITY"
                                : "llm".equals(strategy)
                                        ? "EVENT_SENTIMENT"
                                        : "fusion".equals(strategy)
                                                ? "STRATEGY_SCORE"
                                                : "POSITION_SIGNAL");
                result.put(
                        "role",
                        independent
                                ? "PREDICTOR"
                                : "llm".equals(strategy) ? "EXPLANATION" : "STRATEGY_EXPERIMENT");
                result.put("data_cutoff", nativeRecord.path("input_cutoff").asText());
                result.put("anchor_date", nativeRecord.path("signal_date").asText());
                result.put(
                        "target_date",
                        nativeRecord
                                .path("target_date")
                                .asText(details.path("target_date").asText(null)));
                if (independent) {
                    copy(output, "probability_up", nativeRecord.path("probability"));
                } else {
                    copy(output, "score", details.path("raw_score"));
                }
                inputIdentity = details.path("input_snapshot_id").asText(inputIdentity);
                result.put(
                        "explanation_status",
                        details.hasNonNull("analysis_id")
                                ? "FROZEN_REFERENCE"
                                : "NOT_LINKED_TO_RECORD");
                eligible =
                        independent
                                && actual.path("details")
                                        .path("timely_before_next_open")
                                        .asBoolean(false)
                                && "RAW".equals(actual.path("price_basis").asText())
                                && "RAW".equals(details.path("price_basis").asText());
            }
        }
        String actualDate =
                actual.path("actual_target_date")
                        .asText(
                                actual.path("target_date")
                                        .asText(result.path("target_date").asText("")));
        result.put("actual_target_date", actualDate.isBlank() ? null : actualDate);
        result.put("actual_return", nullableText(actual.path("actual_return")));
        result.set("evaluation", normalizedActual(actual));
        // 评价机会必须有可追溯输入和明确实际终点；跨时区同一时刻归一化后再匹配。
        String cutoff = canonicalInstant(result.path("data_cutoff").asText());
        String generated = canonicalInstant(result.path("generated_at").asText());
        boolean timeValid =
                !cutoff.isBlank()
                        && !generated.isBlank()
                        && !Instant.parse(cutoff).isAfter(Instant.parse(generated))
                        && beforeActual(generated, actualDate);
        if ("forward".equals(source)) {
            timeValid = timeValid && beforeOpen(generated, result.path("anchor_date").asText());
        }
        eligible =
                eligible
                        && timeValid
                        && !inputIdentity.isBlank()
                        && !result.path("model_version").asText().isBlank()
                        && !"UNKNOWN".equals(result.path("model_version").asText());
        result.put("evaluation_eligible", eligible);
        result.put("evaluation_reason", eligible ? "ELIGIBLE" : "未到期、证据不完整、时点不合法或非独立预测；不计入准确率");
        result.put(
                "sample_key",
                String.join("|", result.path("anchor_date").asText(), actualDate, cutoff));
        result.put("input_identity", inputIdentity);
        return result;
    }

    /** 统一结果保持冻结身份，成熟评价另行追加；真实生成时间必须早于目标结束。 */
    private JsonNode normalizeUnified(JsonNode row, Selection selection) {
        ObjectNode result = (ObjectNode) row.path("record").path("details").deepCopy();
        result.put("id", row.path("record").path("id").asText());
        result.put("source", "unified");
        if (List.of("DEEPSEEK", "GPT").contains(result.path("scheme").asText())
                && result.path("output").hasNonNull("probability_up")) {
            result.put("probability_validation_status", "UNCALIBRATED");
        }
        JsonNode actual = row.path("actual");
        result.set("evaluation", normalizedActual(actual));
        String actualDate =
                actual.path("actual_target_date").asText(actual.path("target_date").asText());
        result.put("actual_target_date", actualDate.isBlank() ? null : actualDate);
        copy(result, "actual_return", actual.path("actual_return"));
        String generated =
                canonicalInstant(
                        result.path("generated_at")
                                .asText(row.path("record").path("generated_at").asText()));
        result.put("generated_at", generated);
        String cutoff = canonicalInstant(result.path("data_cutoff").asText());
        String actualAvailable = canonicalInstant(actual.path("actual_available_at").asText());
        result.put("actual_available_at", actualAvailable.isBlank() ? null : actualAvailable);
        // 到期日期不能替代真实可得时刻；缺少来源时间的旧记录保留展示，但不进入选优证据。
        boolean actualTimeValid =
                !actualAvailable.isBlank()
                        && !generated.isBlank()
                        && Instant.parse(generated).isBefore(Instant.parse(actualAvailable))
                        && !Instant.parse(actualAvailable).isAfter(Instant.now());
        boolean valid =
                !generated.isBlank()
                        && !cutoff.isBlank()
                        && "PREDICTOR".equals(result.path("role").asText())
                        && !result.path("model_version").asText().isBlank()
                        && !"UNKNOWN".equals(result.path("model_version").asText())
                        && !result.path("anchor_date").asText().isBlank()
                        && !result.path("target_version").asText().isBlank()
                        && result.path("target_version")
                                .asText()
                                .equals(actual.path("target_version").asText())
                        && !Instant.parse(cutoff).isAfter(Instant.parse(generated))
                        && actualTimeValid
                        && beforeActual(generated, actualDate)
                        && (List.of("EVALUATED", "MATURED").contains(actual.path("status").asText())
                                || "EVALUATED".equals(actual.path("evaluation_status").asText()));
        if ("ETF_FORWARD_OPEN".equals(selection.target())) {
            valid = valid && beforeOpen(generated, result.path("anchor_date").asText());
        }
        if (result.hasNonNull("submission_deadline")) {
            String deadline = canonicalInstant(result.path("submission_deadline").asText());
            valid =
                    valid
                            && !deadline.isBlank()
                            && Instant.parse(generated).isBefore(Instant.parse(deadline));
        }
        result.put("evaluation_eligible", valid);
        result.put("evaluation_reason", valid ? "ELIGIBLE" : "尚未到期或生成时点不合法，不计入评分");
        result.put(
                "sample_key",
                String.join("|", result.path("anchor_date").asText(), actualDate, cutoff));
        result.set("analysis", ForecastAnalysis.frozen(store.json, result));
        if ("REVIEW".equals(result.path("role").asText())) {
            result.put("evaluation_eligible", false);
            result.put("evaluation_reason", "分歧分析仅提供说明，不是数值预测，不计入准确率或到期选优");
            result.set("evaluation", store.json.createObjectNode().put("status", "NOT_APPLICABLE"));
        }
        return result;
    }

    /** 原生结果有实际收益即已产生到期事实，不能因旧表没有status而显示等待。 */
    private JsonNode normalizedActual(JsonNode actual) {
        ObjectNode result =
                actual.isObject() ? (ObjectNode) actual.deepCopy() : store.json.createObjectNode();
        if (!result.hasNonNull("status")) {
            result.put(
                    "status",
                    result.path("evaluation_status")
                            .asText(
                                    result.hasNonNull("actual_return")
                                            ? "EVALUATED"
                                            : "WAITING_MATURITY"));
        }
        return result;
    }

    /** 数值保持原始精度及空值，由评价层严格解析后才计算。 */
    private void copy(ObjectNode target, String name, JsonNode value) {
        target.set(name, value.isMissingNode() ? store.json.nullNode() : value);
    }

    /** 未提供字段不得用空字符串或零伪装已知值。 */
    private String nullableText(JsonNode value) {
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    /** 时区等价的UTC时间统一身份；格式缺失时拒绝进入评价。 */
    private String canonicalInstant(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    /** 期后生成的回填记录不作为当时可用预测；日期无效时保持不可评价。 */
    private boolean beforeActual(String generated, String day) {
        try {
            LocalDate actual = LocalDate.parse(day);
            return !actual.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                    && Instant.parse(generated)
                            .atZone(ZoneId.of("Asia/Shanghai"))
                            .toLocalDate()
                            .isBefore(actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 开盘到开盘协议只允许执行开盘前冻结，晚到记录不能回溯加入评分。 */
    private boolean beforeOpen(String generated, String day) {
        try {
            return Instant.parse(generated)
                    .isBefore(
                            LocalDate.parse(day)
                                    .atTime(9, 30)
                                    .atZone(ZoneId.of("Asia/Shanghai"))
                                    .toInstant());
        } catch (RuntimeException error) {
            return false;
        }
    }
}
