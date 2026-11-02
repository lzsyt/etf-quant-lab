package cn.etfquantlab.fund;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.prediction.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
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
import org.springframework.transaction.support.TransactionTemplate;

/** 基金净值、研究工件与冻结证据独立存储；读页面不抓取、不训练、不收费。 */
@Service
public class FundResearchService {
    public static final List<String> CODES = List.of("460300", "007169", "008701");
    private final Store store;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final AnalysisService news;
    private final boolean enabled;
    private final String codeVersion;
    @org.springframework.beans.factory.annotation.Autowired private FundNews fundNews;

    /** 延用已有内部鉴权、串行任务及数据库事务，不新增服务。 */
    public FundResearchService(
            Store store,
            QuantClient quant,
            TransactionTemplate tx,
            AnalysisService news,
            @Value("${etf.fund-enabled:true}") boolean enabled,
            @Value("${etf.code-version:LOCAL_UNRELEASED}") String codeVersion) {
        this.store = store;
        this.quant = quant;
        this.tx = tx;
        this.news = news;
        this.enabled = enabled;
        this.codeVersion = codeVersion;
    }

    /** 功能开关只影响新任务，保留历史研究读取和手工账本。 */
    public boolean enabled() {
        return enabled;
    }

    /** 显式限制核心资产，保留代码前导零，避免任意代码扩池。 */
    public void requireCode(String code) {
        if (!CODES.contains(code)) {
            throw new IllegalArgumentException("不支持的基金代码");
        }
    }

    /** 目录附最近真实净值和来源状态，不从场内ETF推断净值。 */
    public Map<String, Object> catalog() {
        List<JsonNode> funds =
                rows(
                        "select (to_jsonb(f)-'metadata'||f.metadata)::text "
                                + "from fund_instrument f order by case code when '460300' then 1 "
                                + "when '007169' then 2 else 3 end");
        for (JsonNode item : funds) {
            ObjectNode fund = (ObjectNode) item;
            List<JsonNode> observations =
                    rows(
                            "select details::text from fund_nav_observation where fund_code=? "
                                    + "and nature='REAL' and quality_status in ('VALID','WARNING') "
                                    + "order by nav_date desc,revision desc,first_observed_at desc limit 1",
                            item.path("code").asText());
            JsonNode latest = observations.isEmpty() ? null : observations.get(0);
            fund.put("nature", "REAL");
            fund.put("latest_nav", latest == null ? null : latest.path("unit_nav").asText());
            fund.put("nav_date", latest == null ? null : latest.path("nav_date").asText());
            fund.put("nav_status", displayNavStatus(item.path("code").asText(), latest));
            fund.set(
                    "warnings",
                    store.json.valueToTree(
                            List.of(
                                    "NAV_CHANGE_NOT_TOTAL_RETURN",
                                    "HISTORICAL_POINT_IN_TIME_UNVERIFIED",
                                    "DEALING_CALENDAR_UNVERIFIED")));
        }
        return Map.of(
                "nature",
                "REAL",
                "as_of",
                Instant.now().toString(),
                "funds",
                funds,
                "completeness",
                "PARTIAL",
                "tasks",
                rows(
                        "select "
                                + "jsonb_build_object('id',id,'task_type',task_type,'status',status,"
                                + "'error_code',error_code,"
                                + "'updated_at',updated_at)::text from task_execution "
                                + "where task_type like 'FUND_%' and task_type<>'FUND_COMPARE' "
                                + "order by updated_at desc limit 30"));
    }

    /** 读取逐日当前修订，原版本继续存在且冻结预测仍使用原快照。 */
    public List<JsonNode> observations(String code) {
        requireCode(code);
        return rows(
                "select details::text from (select distinct on(nav_date) "
                        + "nav_date,details from fund_nav_observation where fund_code=? "
                        + "and nature='REAL' and quality_status in ('VALID','WARNING') "
                        + "order by nav_date,revision desc,first_observed_at desc) n order by nav_date",
                code);
    }

    /** 净值历史和质量状态直接供图表消费，历史下载不声称当时已公开。 */
    public Map<String, Object> nav(String code) {
        List<JsonNode> data = observations(code);
        return Map.of(
                "fund_code",
                code,
                "nature",
                "REAL",
                "target_basis",
                "NAV_CHANGE",
                "observations",
                data,
                "status",
                displayNavStatus(code, data.isEmpty() ? null : data.get(data.size() - 1)),
                "sources",
                rows(
                        "select row_to_json(s)::text from fund_source_status s where fund_code=?",
                        code),
                "warnings",
                List.of(
                        "DIVIDEND_SPLIT_COVERAGE_UNVERIFIED",
                        "HISTORICAL_POINT_IN_TIME_UNVERIFIED"));
    }

    /** 夜间采集独立任务保留失败原因，失败不会改写上次成功的净值日期。 */
    public Map<String, Object> sync(String code) {
        requireEnabled(code);
        try {
            JsonNode result = quant.post("/funds/nav/fetch", Map.of("fund_code", code));
            Map<String, Object> counts = importNav(code, result);
            return Map.of(
                    "status",
                    "SUCCEEDED",
                    "fund_code",
                    code,
                    "counts",
                    counts,
                    "warnings",
                    result.path("warnings"));
        } catch (RuntimeException error) {
            tx.executeWithoutResult(
                    status ->
                            sourceStatus(
                                    code,
                                    "FAILED",
                                    Map.of(
                                            "reason_code",
                                            "NAV_SOURCE_UNAVAILABLE",
                                            "error_type",
                                            error.getClass().getSimpleName()),
                                    false));
            throw error;
        }
    }

    /** 每个基金在事务锁下追加修订；相同hash不刷新首次可得时间。 */
    public Map<String, Object> importNav(String code, JsonNode batch) {
        requireCode(code);
        if (!code.equals(batch.path("fund_code").asText())
                || !"REAL".equals(batch.path("nature").asText())) {
            throw new IllegalArgumentException("基金净值身份或数据模式不匹配");
        }
        return tx.execute(
                status -> {
                    store.lock("fund-nav:" + code);
                    int imported = 0;
                    int duplicate = 0;
                    for (JsonNode raw : batch.path("observations")) {
                        if (!code.equals(raw.path("fund_code").asText())
                                || !"REAL".equals(raw.path("nature").asText())) {
                            throw new IllegalArgumentException("净值行身份不匹配");
                        }
                        BigDecimal nav = new BigDecimal(raw.path("unit_nav").asText());
                        LocalDate date = LocalDate.parse(raw.path("nav_date").asText());
                        Instant observed = Instant.parse(raw.path("first_observed_at").asText());
                        Instant fetched = Instant.parse(raw.path("fetched_at").asText());
                        String hash = raw.path("revision_hash").asText();
                        if (nav.signum() <= 0
                                || date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                                || observed.isAfter(Instant.now().plusSeconds(30))
                                || fetched.isBefore(observed)
                                || !hash.matches("[a-f0-9]{64}")) {
                            throw new IllegalArgumentException("净值金额、日期或来源摘要无效");
                        }
                        String provider = raw.path("provider").asText();
                        List<JsonNode> existing =
                                rows(
                                        "select details::text from fund_nav_observation "
                                                + "where fund_code=? and nav_date=? and provider=? and revision_hash=?",
                                        code,
                                        Date.valueOf(date),
                                        provider,
                                        hash);
                        if (!existing.isEmpty()) {
                            if (new BigDecimal(existing.get(0).path("unit_nav").asText())
                                            .compareTo(nav)
                                    != 0) {
                                throw new IllegalArgumentException("相同来源修订含不同净值");
                            }
                            duplicate++;
                            continue;
                        }
                        int revision =
                                store.db.queryForObject(
                                        "select coalesce(max(revision),0)+1 "
                                                + "from fund_nav_observation where fund_code=? and nav_date=?",
                                        Integer.class,
                                        code,
                                        Date.valueOf(date));
                        ObjectNode row = (ObjectNode) raw.deepCopy();
                        row.put("revision", revision);
                        row.put("unit_nav", nav.toPlainString());
                        store.db.update(
                                "insert into fund_nav_observation(id,fund_code,nav_date,unit_nav,"
                                        + "accumulated_nav,published_at,first_observed_at,fetched_at,provider,revision,"
                                        + "revision_hash,quality_status,nature,details) "
                                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,'REAL',?::jsonb)",
                                UUID.randomUUID(),
                                code,
                                Date.valueOf(date),
                                nav,
                                raw.hasNonNull("accumulated_nav")
                                        ? new BigDecimal(raw.path("accumulated_nav").asText())
                                        : null,
                                raw.hasNonNull("published_at")
                                        ? Timestamp.from(
                                                Instant.parse(raw.path("published_at").asText()))
                                        : null,
                                Timestamp.from(observed),
                                Timestamp.from(fetched),
                                provider,
                                revision,
                                hash,
                                raw.path("quality_status").asText("WARNING"),
                                store.encode(row));
                        imported++;
                    }
                    sourceStatus(
                            code,
                            batch.path("observations").isEmpty() ? "MISSING" : "HEALTHY",
                            Map.of(
                                    "warnings",
                                    batch.path("warnings"),
                                    "provider",
                                    batch.path("provider"),
                                    "corporate_action_status",
                                    batch.path("corporate_action_status")),
                            !batch.path("observations").isEmpty());
                    return Map.of("imported", imported, "duplicates", duplicate);
                });
    }

    /** 来源投影允许更新，但原始净值、冻结输入与失败证据不会被覆盖。 */
    private void sourceStatus(String code, String status, Object details, boolean success) {
        store.db.update(
                "insert into fund_source_status(fund_code,status,last_attempt_at,last_success_at,details) "
                        + "values(?,?,now(),?,?::jsonb) on conflict(fund_code) do update set status=excluded.status,"
                        + "last_attempt_at=excluded.last_attempt_at,last_success_at=coalesce(excluded.last_success_at,"
                        + "fund_source_status.last_success_at),details=excluded.details",
                code,
                status,
                success ? Timestamp.from(Instant.now()) : null,
                store.encode(details));
    }

    /** 训练只消费本基金净值，不使用ETF旧h1/h5工件或新闻未来信息。 */
    public JsonNode train(String code) {
        requireEnabled(code);
        JsonNode result =
                quant.post(
                        "/funds/models/train",
                        Map.of(
                                "fund_code",
                                code,
                                "observations",
                                observations(code),
                                "horizons",
                                List.of(5, 20, 60),
                                "algorithms",
                                List.of("lightgbm", "xgboost"),
                                "code_version",
                                codeVersion));
        tx.executeWithoutResult(
                status -> {
                    store.lock("fund-model:" + code);
                    for (JsonNode model : result.path("models")) {
                        String id = model.path("id").asText("failed-" + UUID.randomUUID());
                        store.db.update(
                                "insert into fund_model(id,fund_code,horizon,details) values(?,?,?,?::jsonb) "
                                        + "on conflict(id) do nothing",
                                id,
                                code,
                                model.path("horizon").asInt(),
                                store.encode(model));
                    }
                });
        return result;
    }

    /** 每算法和期限分别使用最近训练结果，旧无算法字段的工件仍归属LightGBM。 */
    public List<JsonNode> models(String code) {
        requireCode(code);
        return rows(
                "select details::text from (select distinct on(horizon,coalesce(details->>'algorithm','lightgbm')) horizon,details "
                        + "from fund_model where fund_code=? and coalesce(details->>'algorithm','lightgbm') in ('lightgbm','xgboost') "
                        + "order by horizon,coalesce(details->>'algorithm','lightgbm'),created_at desc,id desc) m",
                code);
    }

    /** 列表仅投影所选窗口的最新模型摘要，训练输入和工件路径留在内部记录中。 */
    private List<JsonNode> modelSummaries(String code, int horizon) {
        return rows(
                "select jsonb_build_object('id',id,'fund_code',fund_code,'horizon',horizon,'algorithm',coalesce(details->>'algorithm','lightgbm'),"
                        + "'status',details->'status','validation_status',details->'validation_status',"
                        + "'adjustment_eligible',details->'adjustment_eligible',"
                        + "'reason_codes',details->'reason_codes','metrics',details->'metrics',"
                        + "'generated_at',details->'generated_at','training_cutoff',details->'training_cutoff',"
                        + "'target_basis',details->'target_basis','capability',details->'capability',"
                        + "'evidence_type',details->'evidence_type')::text from "
                        + "(select distinct on(coalesce(details->>'algorithm','lightgbm')) * from fund_model "
                        + "where fund_code=? and horizon=? and coalesce(details->>'algorithm','lightgbm') in ('lightgbm','xgboost') "
                        + "order by coalesce(details->>'algorithm','lightgbm'),created_at desc,id desc) m",
                code,
                horizon);
    }

    /** 任务生成新冻结预测，重试按输入和模型业务键复用，绝不改旧生成时间。 */
    public JsonNode predict(String code) {
        requireEnabled(code);
        List<JsonNode> data = observations(code);
        List<String> sourceStates =
                store.db.query(
                        "select status from fund_source_status where fund_code=?",
                        (rs, row) -> rs.getString(1),
                        code);
        if (sourceStates.isEmpty() || !"HEALTHY".equals(sourceStates.get(0))) {
            return store.json.valueToTree(
                    Map.of(
                            "status",
                            "BLOCKED_SOURCE",
                            "reason_codes",
                            List.of("LATEST_NAV_SOURCE_UNAVAILABLE")));
        }
        if (!"VALID".equals(navStatus(data.isEmpty() ? null : data.get(data.size() - 1)))) {
            return store.json.valueToTree(
                    Map.of(
                            "status",
                            "BLOCKED_DATA",
                            "reason_codes",
                            List.of("NAV_MISSING_OR_STALE")));
        }
        List<JsonNode> usable =
                models(code).stream()
                        // 旧基金预测表只保存20/60幅度；h5概率由统一预测运行冻结。
                        .filter(model -> model.path("horizon").asInt() != 5)
                        .filter(model -> model.hasNonNull("artifact_hash"))
                        .toList();
        if (usable.isEmpty()) {
            return store.json.valueToTree(
                    Map.of("status", "MODEL_NOT_READY", "reason_codes", List.of("NO_FUND_MODEL")));
        }
        JsonNode generated =
                quant.post(
                        "/funds/models/predict",
                        Map.of("fund_code", code, "observations", data, "models", usable));
        List<JsonNode> frozen =
                tx.execute(
                        status -> {
                            store.lock("fund-forecast:" + code);
                            List<JsonNode> saved = new ArrayList<>();
                            for (JsonNode raw : generated.path("forecasts")) {
                                int horizon = raw.path("points").path(0).path("horizon").asInt();
                                String key =
                                        code
                                                + ":"
                                                + raw.path("model_version_id").asText()
                                                + ":"
                                                + raw.path("data_snapshot_id").asText();
                                List<JsonNode> prior =
                                        rows(
                                                "select details::text from fund_forecast where business_key=?",
                                                key);
                                if (!prior.isEmpty()) {
                                    saved.add(prior.get(0));
                                    continue;
                                }
                                if (!code.equals(raw.path("fund_code").asText())
                                        || !List.of(20, 60).contains(horizon)
                                        || !"RETURN_POINT".equals(raw.path("capability").asText())
                                        || raw.path("daily_path").isArray()) {
                                    throw new IllegalArgumentException("基金预测能力或资产身份不匹配");
                                }
                                ObjectNode record = (ObjectNode) raw.deepCopy();
                                UUID id = UUID.randomUUID();
                                record.put("id", id.toString());
                                // 保存确切观测修订引用；源记录不可变，可回查当时输入而非最新净值。
                                record.set(
                                        "input_revisions",
                                        store.json.valueToTree(
                                                data.stream()
                                                        .map(
                                                                row ->
                                                                        Map.of(
                                                                                "nav_date",
                                                                                        row.path(
                                                                                                        "nav_date")
                                                                                                .asText(),
                                                                                "revision_hash",
                                                                                        row.path(
                                                                                                        "revision_hash")
                                                                                                .asText(),
                                                                                "first_observed_at",
                                                                                        row.path(
                                                                                                        "first_observed_at")
                                                                                                .asText()))
                                                        .toList()));
                                JsonNode newsSnapshot =
                                        newsForForecast(
                                                code,
                                                horizon,
                                                Instant.parse(raw.path("generated_at").asText()));
                                boolean hasNews = !newsSnapshot.isNull();
                                record.set("news_snapshot", newsSnapshot);
                                record.put(
                                        "news_analysis_id",
                                        hasNews ? newsSnapshot.path("analysis_id").asText() : null);
                                record.put("news_usage", hasNews ? "DISPLAY_ONLY" : "NOT_USED");
                                record.put("usage", "MODEL_ONLY");
                                store.db.update(
                                        "insert into fund_forecast(id,fund_code,business_key,horizon,generated_at,details) "
                                                + "values(?,?,?,?,?,?::jsonb)",
                                        id,
                                        code,
                                        key,
                                        horizon,
                                        Timestamp.from(
                                                Instant.parse(raw.path("generated_at").asText())),
                                        store.encode(record));
                                saved.add(record);
                            }
                            return saved;
                        });
        return store.json.valueToTree(Map.of("status", "FROZEN", "forecasts", frozen));
    }

    /** 图表只读取真实冻结批次；实际验证与不可变预测分开存储。 */
    public Map<String, Object> forecasts(String code, int horizon) {
        requireCode(code);
        if (!List.of(20, 60).contains(horizon)) {
            throw new IllegalArgumentException("基金研究窗口仅支持20或60");
        }
        List<JsonNode> records =
                rows(
                                "select ((f.details-'input_revisions') || jsonb_build_object('evaluation',"
                                        + "(select e.details from fund_forecast_evaluation e where e.forecast_id=f.id "
                                        + "order by evaluated_at desc limit 1)))::text from fund_forecast f "
                                        + "where fund_code=? and horizon=? order by generated_at desc limit 100",
                                code,
                                horizon)
                        .stream()
                        .map(record -> FundNewsIdentity.forecast(record, code))
                        .toList();
        return Map.of(
                "fund_code",
                code,
                "horizon",
                horizon,
                "forecasts",
                records,
                "current_timing",
                forecastTiming(code, records),
                "models",
                modelSummaries(code, horizon),
                "status",
                records.isEmpty() ? "MODEL_NOT_READY" : "AVAILABLE",
                "reason_codes",
                records.isEmpty() ? List.of("NO_FROZEN_FUND_FORECAST") : List.of());
    }

    /** 当前观察进度与交易所日历参考独立返回，绝不改写冻结日期或用于实际收益判定。 */
    private Map<String, Object> forecastTiming(String code, List<JsonNode> records) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<LocalDate> observed =
                store.db.query(
                        "select distinct nav_date from fund_nav_observation where fund_code=? "
                                + "and nature='REAL' and quality_status in ('VALID','WARNING') "
                                + "and first_observed_at<=? and nav_date<=? order by nav_date",
                        (rs, row) -> rs.getDate(1).toLocalDate(),
                        code,
                        Timestamp.from(now),
                        Date.valueOf(today));
        // 当前核验过的官方范围只有2026；其他年份和缺日不能用工作日代替。
        Map<LocalDate, Boolean> calendar = new LinkedHashMap<>();
        store.db.query(
                "select trade_date,is_open from trading_calendar where trade_date between '2026-01-01' "
                        + "and '2026-12-31' and source in "
                        + "('AKSHARE_SINA_AND_SSE_OFFICIAL_2026','OFFICIAL_EXCHANGE_CLOSED_2026')",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> calendar.put(rs.getDate(1).toLocalDate(), rs.getBoolean(2)));
        Map<String, Object> result = new LinkedHashMap<>();
        for (JsonNode record : records) {
            if (!record.hasNonNull("anchor_date")
                    || !"FUND_NAV_OBSERVATIONS".equals(record.path("horizon_unit").asText())) {
                continue;
            }
            LocalDate anchor = LocalDate.parse(record.path("anchor_date").asText());
            int horizon =
                    record.path("horizon")
                            .asInt(record.path("points").path(0).path("horizon").asInt());
            if (!List.of(20, 60).contains(horizon)) {
                continue;
            }
            long count = observed.stream().filter(day -> day.isAfter(anchor)).count();
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("as_of", now.toString());
            timing.put("observed", count);
            timing.put("required", horizon);
            timing.put("remaining", Math.max(0, horizon - count));
            timing.put(
                    "status",
                    record.hasNonNull("evaluation")
                            ? "EVALUATED"
                            : count >= horizon ? "READY_FOR_EVALUATION" : "WAITING_OBSERVATIONS");
            LocalDate estimate = anchor;
            for (int remaining = horizon; remaining > 0; ) {
                estimate = estimate.plusDays(1);
                Boolean open = calendar.get(estimate);
                if (open == null) {
                    estimate = null;
                    break;
                }
                if (open) {
                    remaining--;
                }
            }
            timing.put("estimated_target_date", estimate == null ? null : estimate.toString());
            timing.put(
                    "estimate_status",
                    estimate == null
                            ? "CALENDAR_COVERAGE_INSUFFICIENT"
                            : "EXCHANGE_CALENDAR_PROXY");
            timing.put(
                    "calendar_source",
                    "https://www.sse.com.cn/disclosure/dealinstruc/closed/c/c_20251222_10802510.shtml");
            timing.put("calendar_coverage_end", "2026-12-31");
            result.put(record.path("id").asText(), timing);
        }
        return result;
    }

    /** 精确批次返回当时输入、新闻和模型版本，不能用最新结论解释旧预测。 */
    public JsonNode forecast(UUID id) {
        List<JsonNode> found =
                rows(
                        "select (f.details || jsonb_build_object('evaluation',"
                                + "(select details from fund_forecast_evaluation where forecast_id=f.id "
                                + "order by evaluated_at desc limit 1)))::text from fund_forecast f where id=?",
                        id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("预测记录不存在");
        }
        JsonNode record = found.get(0);
        return FundNewsIdentity.forecast(record, record.path("fund_code").asText());
    }

    /** 到期验证只追加完整实际结果，未到期或日历缺失不记作0收益。 */
    public Map<String, Object> evaluate(String code) {
        requireEnabled(code);
        List<JsonNode> data = observations(code);
        List<JsonNode> forecasts =
                rows(
                        "select details::text from fund_forecast f where fund_code=? "
                                + "and not exists(select 1 from fund_forecast_evaluation e where e.forecast_id=f.id)",
                        code);
        int appended = 0;
        List<JsonNode> waiting = new ArrayList<>();
        for (JsonNode forecast : forecasts) {
            JsonNode result =
                    quant.post(
                            "/funds/models/evaluate",
                            Map.of("forecast", forecast, "observations", data));
            if (!"EVALUATED".equals(result.path("evaluation_status").asText())) {
                waiting.add(result);
                continue;
            }
            Boolean saved =
                    tx.execute(
                            status -> {
                                UUID id = UUID.fromString(forecast.path("id").asText());
                                store.lock("fund-evaluate:" + id);
                                if (store.db.queryForObject(
                                                "select count(*) from fund_forecast_evaluation where forecast_id=?",
                                                Integer.class,
                                                id)
                                        > 0) {
                                    return false;
                                }
                                store.db.update(
                                        "insert into fund_forecast_evaluation(id,forecast_id,details) values(?,?,?::jsonb)",
                                        UUID.randomUUID(),
                                        id,
                                        store.encode(result));
                                return true;
                            });
            if (Boolean.TRUE.equals(saved)) {
                appended++;
            }
        }
        return Map.of("status", "SUCCEEDED", "appended", appended, "waiting", waiting);
    }

    /** 只读新闻缺失状态不产生任务，也不把缺失分数填成中性0。 */
    public JsonNode latestNews(String code) {
        requireCode(code);
        List<JsonNode> found =
                rows(
                        "select details::text from fund_news_analysis where fund_code=? order by created_at desc limit 1",
                        code);
        if (!found.isEmpty()) {
            return consistentNewsStatus(found.get(0), code);
        }
        ObjectNode missing = store.json.createObjectNode();
        missing.put("fund_code", code)
                .put("nature", "REAL")
                .put("analysis_status", "NOT_ANALYZED")
                .put("forecast_usage", "NOT_USED")
                .putNull("sentiment_score")
                .putNull("analysis_id");
        for (String field : List.of("documents", "facts", "inferences", "uncertainties")) {
            missing.putArray(field);
        }
        missing.putArray("reason_codes").add("NO_FUND_NEWS_SNAPSHOT");
        return missing;
    }

    /** 去除准备阶段遗留的未分析提示；历史快照只在响应中修正，不回写原始证据。 */
    private JsonNode consistentNewsStatus(JsonNode newsSnapshot, String code) {
        newsSnapshot = FundNewsIdentity.project(newsSnapshot, code);
        if (!"SUCCEEDED".equals(newsSnapshot.path("ds_status").asText())
                || !List.of("VALID", "INSUFFICIENT_INPUT")
                        .contains(newsSnapshot.path("analysis_status").asText())) {
            return newsSnapshot;
        }
        ObjectNode result = newsSnapshot.deepCopy();
        for (String field : List.of("reason_codes", "uncertainties")) {
            if (!result.path(field).isArray()) {
                continue;
            }
            var filtered = store.json.createArrayNode();
            for (JsonNode item : result.path(field)) {
                if (!"NOT_ANALYZED".equals(item.asText())
                        && !"尚未调用基金专用分析；已准备的资料不等于模型结论".equals(item.asText())) {
                    filtered.add(item);
                }
            }
            result.set(field, filtered);
        }
        if (result.path("ds_reason").isNull() || "null".equals(result.path("ds_reason").asText())) {
            result.putNull("ds_reason");
        }
        return result;
    }

    /** 新闻必须在预测生成前已冻结；未知或过期分析不绑定到新的预测。 */
    private JsonNode newsForForecast(String code, int horizon, Instant cutoff) {
        List<JsonNode> found =
                rows(
                        "select details::text from fund_news_analysis where fund_code=? "
                                + "and created_at<=? and input_cutoff_at<=? and details->>'horizon'=? "
                                + "order by created_at desc limit 1",
                        code,
                        Timestamp.from(cutoff),
                        Timestamp.from(cutoff),
                        String.valueOf(horizon));
        if (found.isEmpty()) {
            return store.json.nullNode();
        }
        JsonNode snapshot = FundNewsIdentity.project(found.get(0), code);
        if (List.of("UNVERIFIED", "INVALID").contains(snapshot.path("analysis_status").asText())) {
            return store.json.nullNode();
        }
        if (!"SUCCEEDED".equals(snapshot.path("ds_status").asText())
                || !snapshot.hasNonNull("input_cutoff_at")
                || Instant.parse(snapshot.path("input_cutoff_at").asText())
                        .isBefore(cutoff.minusSeconds(7 * 86400))
                || (snapshot.hasNonNull("valid_until")
                        && Instant.parse(snapshot.path("valid_until").asText()).isBefore(cutoff))) {
            return store.json.nullNode();
        }
        return snapshot;
    }

    /** 免费筛选真实已存新闻，冻结适用资产和材料范围；付费DS分析另有授权开关。 */
    public JsonNode prepareNews(String code) {
        requireEnabled(code);
        Instant cutoff = Instant.now();
        JsonNode result =
                quant.post(
                        "/funds/news/prepare",
                        Map.of(
                                "fund_code",
                                code,
                                "horizon",
                                20,
                                "cutoff",
                                cutoff.toString(),
                                "documents",
                                news.candidates(cutoff)));
        JsonNode call = fundNews.analyze(code, cutoff);
        return tx.execute(
                status -> {
                    String key =
                            code
                                    + ":"
                                    + result.path("input_snapshot_hash").asText()
                                    + ":"
                                    + call.path("id").asText();
                    store.lock("fund-news:" + key);
                    List<JsonNode> existing =
                            rows(
                                    "select details::text from fund_news_analysis where business_key=?",
                                    key);
                    if (!existing.isEmpty()) {
                        return consistentNewsStatus(existing.get(0), code);
                    }
                    UUID id = UUID.randomUUID();
                    ObjectNode snapshot = (ObjectNode) result.deepCopy();
                    snapshot.put("analysis_id", id.toString());
                    snapshot.put("nature", "REAL");
                    snapshot.put("horizon", 20);
                    snapshot.put("input_cutoff_at", cutoff.toString());
                    snapshot.put("ds_status", call.path("status").asText());
                    snapshot.put("ds_reason", call.path("error_code").asText());
                    snapshot.put("news_call_id", call.path("id").asText());
                    snapshot.set(
                            "candidate_input_snapshot_hash", result.path("input_snapshot_hash"));
                    snapshot.put("forecast_usage", "DISPLAY_ONLY");
                    JsonNode analysis = call.path("result").path("analysis");
                    if ("SUCCEEDED".equals(call.path("status").asText()) && analysis.isObject()) {
                        // 准备器升级不代表被复用的旧付费响应已经通过新校验，身份版本必须取实际调用。
                        snapshot.set("schema_version", call.path("result").path("schema_version"));
                        for (String field :
                                List.of(
                                        "fund_identity",
                                        "analysis_status",
                                        "stance",
                                        "sentiment_score",
                                        "score_scale",
                                        "facts",
                                        "inferences",
                                        "uncertainties",
                                        "risk_flags",
                                        "documents",
                                        "input_snapshot_hash",
                                        "input_cutoff_at",
                                        "generated_at",
                                        "schema_version",
                                        "prompt_version")) {
                            if (analysis.has(field)) {
                                snapshot.set(field, analysis.path(field));
                            }
                        }
                        snapshot.put(
                                "input_cutoff_at",
                                call.path("input_snapshot").path("cutoff").asText());
                        snapshot.put(
                                "ds_input_sha256",
                                call.path("quote").path("input_sha256").asText());
                        snapshot.put(
                                "ds_evidence_snapshot_id",
                                call.path("quote").path("evidence_snapshot_id").asText());
                        if (call.path("result").hasNonNull("valid_until")) {
                            snapshot.set("valid_until", call.path("result").path("valid_until"));
                        }
                    } else {
                        snapshot.put(
                                "analysis_status", call.path("error_code").asText("NOT_ANALYZED"));
                        snapshot.putNull("sentiment_score");
                    }
                    snapshot = (ObjectNode) consistentNewsStatus(snapshot, code);
                    store.db.update(
                            "insert into fund_news_analysis(id,fund_code,business_key,input_cutoff_at,details) "
                                    + "values(?,?,?,?,?::jsonb)",
                            id,
                            code,
                            key,
                            Timestamp.from(
                                    Instant.parse(snapshot.path("input_cutoff_at").asText())),
                            store.encode(snapshot));
                    return snapshot;
                });
    }

    /** 新手概览明确模型独立、新闻只解释，不声称已验证融合。 */
    public Map<String, Object> summary(String code, int horizon) {
        var records = (List<JsonNode>) forecasts(code, horizon).get("forecasts");
        JsonNode latest = records.isEmpty() ? null : records.get(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fund_code", code);
        summary.put("forecast", latest);
        summary.put("forecast_id", latest == null ? null : latest.path("id").asText());
        summary.put("news", latest == null ? null : latest.path("news_snapshot"));
        summary.put("usage", latest == null ? "NO_VALID_SIGNAL" : "MODEL_ONLY");
        summary.put("reason_codes", List.of("NO_VALIDATED_FUSION", "RESEARCH_NOT_CASH_ADVICE"));
        return summary;
    }

    /** 采集失败时仍可看旧值，但不得把旧缓存标成当前健康。 */
    private String displayNavStatus(String code, JsonNode latest) {
        List<String> states =
                store.db.query(
                        "select status from fund_source_status where fund_code=?",
                        (rs, row) -> rs.getString(1),
                        code);
        return !states.isEmpty() && "FAILED".equals(states.get(0)) ? "ERROR" : navStatus(latest);
    }

    /** 净值时效采用保守自然日上限，独立于申购开放日规则。 */
    private String navStatus(JsonNode latest) {
        if (latest == null) {
            return "MISSING";
        }
        return LocalDate.parse(latest.path("nav_date").asText())
                        .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(7))
                ? "STALE"
                : "VALID";
    }

    /** 只有数据或工件修订变化才恢复等待任务，避免轮询造成无限重试。 */
    public String dependencyVersion(String code) {
        requireCode(code);
        String navVersion =
                store.db.queryForObject(
                        "select count(*)::text||':'||coalesce(max(fetched_at)::text,'') "
                                + "from fund_nav_observation where fund_code=?",
                        String.class,
                        code);
        String modelVersion =
                store.db.queryForObject(
                        "select count(*)::text||':'||coalesce(max(created_at)::text,'') "
                                + "from fund_model where fund_code=?",
                        String.class,
                        code);
        List<String> source =
                store.db.query(
                        "select status from fund_source_status where fund_code=?",
                        (rs, row) -> rs.getString(1),
                        code);
        return navVersion
                + ":"
                + modelVersion
                + ":"
                + (source.isEmpty() ? "MISSING" : source.get(0));
    }

    /** 对显式任务执行再次检查新模块开关。 */
    private void requireEnabled(String code) {
        requireCode(code);
        if (!enabled) {
            throw new IllegalArgumentException("基金研究任务已关闭");
        }
    }

    /** 仅内部固定SQL，外部值一律参数绑定。 */
    private List<JsonNode> rows(String sql, Object... parameters) {
        return store.db.query(sql, (rs, row) -> store.decode(rs.getString(1)), parameters);
    }
}
