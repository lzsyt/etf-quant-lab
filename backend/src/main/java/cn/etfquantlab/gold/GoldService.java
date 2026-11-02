package cn.etfquantlab.gold;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
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

/** 黄金的数据、模型、预测和到期评价全部写入独立命名空间。 */
@Service
public class GoldService {
    public static final String BENCHMARK = "SGE_AU9999_CLOSE";
    private final Store store;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private final String codeVersion;
    private final boolean enabled;

    /** 复用现有内部鉴权和数据库事务，不复用ETF模型权重及交易账户。 */
    public GoldService(
            Store store,
            QuantClient quant,
            TransactionTemplate tx,
            @Value("${etf.code-version:LOCAL_UNRELEASED}") String codeVersion,
            @Value("${etf.gold-enabled:true}") boolean enabled) {
        this.store = store;
        this.quant = quant;
        this.tx = tx;
        this.codeVersion = codeVersion;
        this.enabled = enabled;
    }

    /** 功能开关只阻止新采集和预测；关闭后历史研究仍可只读查看。 */
    public boolean enabled() {
        return enabled;
    }

    /** 正常按30日增量；停机超过30日从最后保存交易日补齐，不能把缺日误当休市。 */
    public JsonNode sync(JsonNode request) {
        requireEnabled();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String lastSaved =
                store.db.queryForObject(
                        "select max(observation_date)::text from gold_observation where series_id=?"
                                + " and quality='REAL_OFFICIAL_CURRENT_VINTAGE'",
                        String.class,
                        BENCHMARK);
        LocalDate defaultStart = today.minusDays(30);
        if (lastSaved != null && LocalDate.parse(lastSaved).isBefore(defaultStart)) {
            defaultStart = LocalDate.parse(lastSaved);
        }
        String start = request.path("start").asText(defaultStart.toString());
        String end = request.path("end").asText(today.toString());
        if (LocalDate.parse(end).isAfter(today)
                || LocalDate.parse(start).isAfter(LocalDate.parse(end))) {
            throw new IllegalArgumentException("黄金采集日期范围无效");
        }
        JsonNode collected;
        try {
            collected = quant.post("/gold/data/sync", Map.of("start", start, "end", end));
            importData(collected, false);
        } catch (RuntimeException unavailable) {
            tx.executeWithoutResult(
                    status -> {
                        store.lock("gold:data-import");
                        for (String series :
                                List.of(
                                        BENCHMARK,
                                        "FRED_DEXCHUS",
                                        "FRED_DFII10",
                                        "FRED_DTWEXBGS")) {
                            saveSource(
                                    store.json.valueToTree(
                                            Map.of(
                                                    "series_id",
                                                    series,
                                                    "status",
                                                    "FAILED",
                                                    "reason",
                                                    "采集或数据校验未完成："
                                                            + unavailable
                                                                    .getClass()
                                                                    .getSimpleName())));
                        }
                    });
            throw unavailable;
        }
        evaluate();
        return collectedSummary(collected);
    }

    /** 管理导入也保留全部原修订；同一序列、日期、来源和修订只落一次。 */
    public Map<String, Object> importData(JsonNode batch, boolean manualCsv) {
        requireEnabled();
        JsonNode observations = batch.path("observations");
        if (!observations.isArray() || observations.size() > 20000) {
            throw new IllegalArgumentException("黄金数据须为数组且单批不超过20000行");
        }
        List<ObjectNode> checked = new ArrayList<>();
        for (JsonNode input : observations) {
            ObjectNode row = input.deepCopy();
            validateObservation(row, manualCsv);
            checked.add(row);
        }
        return tx.execute(
                status -> {
                    store.lock("gold:data-import");
                    int imported = 0;
                    for (ObjectNode row : checked) {
                        Integer count =
                                store.db.queryForObject(
                                        "select count(*) from gold_observation where series_id=? and observation_date=?"
                                                + " and source=? and revision=?",
                                        Integer.class,
                                        row.path("series_id").asText(),
                                        Date.valueOf(row.path("observation_date").asText()),
                                        row.path("source").asText(),
                                        row.path("revision").asText());
                        if (count > 0) {
                            // 重试相同修订必须值一致，拒绝用相同版本覆盖原始证据。
                            BigDecimal original =
                                    store.db.queryForObject(
                                            "select value from gold_observation where series_id=? and observation_date=?"
                                                    + " and source=? and revision=? limit 1",
                                            BigDecimal.class,
                                            row.path("series_id").asText(),
                                            Date.valueOf(row.path("observation_date").asText()),
                                            row.path("source").asText(),
                                            row.path("revision").asText());
                            if (original.compareTo(new BigDecimal(row.path("value").asText()))
                                    != 0) {
                                throw new IllegalArgumentException("相同黄金来源修订对应不同数值，请使用新修订号");
                            }
                            continue;
                        }
                        store.db.update(
                                "insert into gold_observation(id,series_id,observation_date,value,unit,source,source_url,"
                                        + "available_at,availability_precision,fetched_at,revision,quality,details)"
                                        + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                                UUID.randomUUID(),
                                row.path("series_id").asText(),
                                Date.valueOf(row.path("observation_date").asText()),
                                new BigDecimal(row.path("value").asText()),
                                row.path("unit").asText(),
                                row.path("source").asText(),
                                row.path("source_url").asText(),
                                row.hasNonNull("available_at")
                                        ? Timestamp.from(
                                                Instant.parse(row.path("available_at").asText()))
                                        : null,
                                row.path("availability_precision").asText(),
                                Timestamp.from(Instant.parse(row.path("fetched_at").asText())),
                                row.path("revision").asText(),
                                row.path("quality").asText(),
                                store.encode(row));
                        imported++;
                    }
                    if (!manualCsv) {
                        for (JsonNode source : batch.path("sources")) {
                            ObjectNode details = source.deepCopy();
                            if (BENCHMARK.equals(details.path("series_id").asText())
                                    && batch.has("calendar")) {
                                details.set("calendar", batch.path("calendar"));
                            }
                            saveSource(details);
                        }
                    }
                    store.audit(
                            "GOLD_DATA_IMPORTED",
                            Map.of("imported", imported, "manual_csv", manualCsv));
                    return Map.of(
                            "status",
                            "IMPORTED",
                            "imported",
                            imported,
                            "duplicates",
                            checked.size() - imported,
                            "manual_csv",
                            manualCsv);
                });
    }

    /** 来源健康是可更新投影，失败不能清空上次成功时间，也不能改写旧观测。 */
    private void saveSource(JsonNode source) {
        String series = source.path("series_id").asText();
        if (!series.matches("[A-Z0-9_]{3,80}")) {
            throw new IllegalArgumentException("来源序列标识无效");
        }
        String status = source.path("status").asText("UNKNOWN");
        boolean success =
                List.of("HEALTHY", "OK", "PARTIAL", "PARTIAL_COVERAGE", "AVAILABLE")
                        .contains(status);
        Timestamp checked = Timestamp.from(Instant.now());
        int changed =
                store.db.update(
                        "update gold_source set status=?,checked_at=?,last_success_at=case when ?"
                                + " then ? else last_success_at end,details=details||?::jsonb where series_id=?",
                        status,
                        checked,
                        success,
                        checked,
                        store.encode(source),
                        series);
        if (changed == 0) {
            store.db.update(
                    "insert into gold_source(series_id,status,checked_at,last_success_at,details)"
                            + " values(?,?,?,?,?::jsonb)",
                    series,
                    status,
                    checked,
                    success ? checked : null,
                    store.encode(source));
        }
    }

    /** 所有日期和来源先验证再入库；手填CSV不得自报已验证的点时历史。 */
    private void validateObservation(ObjectNode row, boolean manual) {
        for (String field :
                List.of(
                        "series_id",
                        "observation_date",
                        "value",
                        "unit",
                        "source",
                        "source_url",
                        "availability_precision",
                        "fetched_at",
                        "revision",
                        "quality")) {
            if (!row.hasNonNull(field) || row.path(field).asText().isBlank()) {
                throw new IllegalArgumentException("黄金数据缺少字段：" + field);
            }
        }
        if (!row.path("series_id").asText().matches("[A-Z0-9_]{3,80}")) {
            throw new IllegalArgumentException("黄金序列标识无效");
        }
        LocalDate date = LocalDate.parse(row.path("observation_date").asText());
        Instant fetched = Instant.parse(row.path("fetched_at").asText());
        if (date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                || fetched.isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("黄金观察日或抓取时间不得在未来");
        }
        BigDecimal value = new BigDecimal(row.path("value").asText());
        if (value.precision() > 24 || value.scale() > 10) {
            throw new IllegalArgumentException("黄金数据超出允许精度");
        }
        String series = row.path("series_id").asText();
        if (!series.equals("FRED_DFII10") && value.signum() <= 0) {
            throw new IllegalArgumentException("价格、汇率及指数必须大于0");
        }
        if (BENCHMARK.equals(series) && !"CNY/g".equals(row.path("unit").asText())) {
            throw new IllegalArgumentException("Au99.99收盘单位固定为CNY/g");
        }
        URI source = URI.create(row.path("source_url").asText());
        if (!List.of("https", "http").contains(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || source.getFragment() != null) {
            throw new IllegalArgumentException("来源须为不含凭据及片段的公开HTTP链接");
        }
        if (row.hasNonNull("available_at") && !row.path("available_at").asText().isBlank()) {
            Instant available = Instant.parse(row.path("available_at").asText());
            if (available.isAfter(fetched)) {
                throw new IllegalArgumentException("首次公开时间不能晚于本次抓取时间");
            }
        } else {
            row.putNull("available_at");
        }
        if (manual) {
            row.put("quality", "CSV_UNVERIFIED");
            row.put("availability_precision", "USER_REPORTED_UNVERIFIED");
            row.put("imported_at", Instant.now().toString());
        }
    }

    /** 只选每个交易日的最新官方有效版本；未知历史可用性继续由模型显式披露。 */
    public List<JsonNode> observations() {
        return store.db.query(
                "select details::text from (select distinct on(series_id,observation_date)"
                        + " series_id,observation_date,details from gold_observation"
                        + " where quality='REAL_OFFICIAL_CURRENT_VINTAGE' and fetched_at<=now()"
                        + " order by series_id,observation_date,fetched_at desc,id) o order by series_id,observation_date",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 必要数据未到齐不消耗本月/本周研究业务键，后续采集恢复后自然补入。 */
    public boolean readyForResearch() {
        if (!benchmarkSourceHealthy()) {
            return false;
        }
        return Boolean.TRUE.equals(
                store.db.queryForObject(
                        "select count(distinct observation_date)>=1200 and max(observation_date)>=?"
                                + " from gold_observation where series_id=?"
                                + " and quality='REAL_OFFICIAL_CURRENT_VINTAGE' and fetched_at<=now()",
                        Boolean.class,
                        Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(10)),
                        BENCHMARK));
    }

    /** 最近采集失败、空集或过期时不能把旧价生成新的正式预测，页面保留旧结果日期。 */
    private boolean benchmarkSourceHealthy() {
        Integer count =
                store.db.queryForObject(
                        "select count(*) from gold_source where series_id=?"
                                + " and status in ('HEALTHY','PARTIAL_COVERAGE','PARTIAL','OK','AVAILABLE')",
                        Integer.class,
                        BENCHMARK);
        return count != null && count > 0;
    }

    /** 两算法各63/126期限都属于本月，才可视为本月模型准备完整。 */
    public boolean currentMonthModelsReady(Instant now) {
        String month =
                now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString().substring(0, 7);
        List<JsonNode> registered = models();
        return registered.size() == 4
                && registered.stream()
                        .allMatch(
                                model -> {
                                    try {
                                        return Instant.parse(
                                                        model.path("metadata")
                                                                .path("fit_at")
                                                                .asText())
                                                .atZone(ZoneId.of("Asia/Shanghai"))
                                                .toLocalDate()
                                                .toString()
                                                .startsWith(month);
                                    } catch (RuntimeException invalid) {
                                        return false;
                                    }
                                });
    }

    /** 等待任务仅在真实依赖变化后恢复；轮询时间本身不会制造新的训练或预测。 */
    public String dependencyVersion() {
        return store.db.queryForObject(
                "select md5(jsonb_build_object('source',(select status from gold_source where series_id=?),"
                        + "'observations',(select jsonb_build_object('count',count(*),'last_date',max(observation_date),"
                        + "'fetched_at',max(fetched_at)) from gold_observation where series_id=?"
                        + " and quality='REAL_OFFICIAL_CURRENT_VINTAGE'),"
                        + "'models',(select coalesce(jsonb_agg(model_id order by model_id),'[]'::jsonb)"
                        + " from gold_model))::text)",
                String.class,
                BENCHMARK,
                BENCHMARK);
    }

    /** 固定协议训练；不足门槛返回阻塞事实，不伪造中性概率。 */
    public JsonNode train() {
        requireEnabled();
        if (!readyForResearch()) {
            return store.json.valueToTree(
                    Map.of("status", "BLOCKED_STALE_OR_INSUFFICIENT_DATA", "models", List.of()));
        }
        List<JsonNode> currentModels = models();
        if (currentMonthModelsReady(Instant.now())) {
            return store.json.valueToTree(
                    Map.of("status", "ALREADY_TRAINED_THIS_MONTH", "models", currentModels));
        }
        JsonNode trained =
                quant.post(
                        "/gold/train",
                        Map.of(
                                "observations",
                                observations(),
                                "algorithms",
                                List.of("lightgbm", "xgboost"),
                                "code_version",
                                codeVersion,
                                "fit_at",
                                Instant.now().toString()));
        if (trained.path("models").isArray()) {
            importModels(trained);
        }
        return trained;
    }

    /** 导入已在开发机验证的黄金工件登记，不接受任意文件路径或覆盖旧模型。 */
    public Map<String, Object> importModels(JsonNode batch) {
        requireEnabled();
        if (!batch.path("models").isArray() || batch.path("models").size() > 20) {
            throw new IllegalArgumentException("模型导入须为不超过20项的models数组");
        }
        return tx.execute(
                status -> {
                    store.lock("gold:models-import");
                    int imported = 0;
                    for (JsonNode model : batch.path("models")) {
                        String id = model.path("model_id").asText();
                        int horizon = model.path("horizon").asInt();
                        if (!List.of("lightgbm", "xgboost")
                                        .contains(model.path("algorithm").asText("lightgbm"))
                                || !id.matches("[A-Za-z0-9_-]{8,180}")
                                || !List.of(63, 126).contains(horizon)
                                || !model.path("metadata").isObject()) {
                            throw new IllegalArgumentException("黄金模型身份、期限或元数据无效");
                        }
                        String artifact = model.path("artifact").asText();
                        if (!artifact.startsWith("gold-")
                                || artifact.contains("/")
                                || artifact.contains("\\")
                                || artifact.contains("..")) {
                            throw new IllegalArgumentException("黄金工件只能引用模型目录内gold-前缀文件");
                        }
                        List<String> found =
                                store.db.query(
                                        "select details::text from gold_model where model_id=?",
                                        (rs, row) -> rs.getString(1),
                                        id);
                        if (!found.isEmpty()) {
                            if (!store.decode(found.get(0)).equals(model)) {
                                throw new IllegalArgumentException("不可覆盖已冻结黄金模型");
                            }
                            continue;
                        }
                        store.db.update(
                                "insert into gold_model(model_id,horizon,details) values(?,?,?::jsonb)",
                                id,
                                horizon,
                                store.encode(model));
                        imported++;
                    }
                    return Map.of("status", "REGISTERED", "imported", imported);
                });
    }

    /** 最新已登记模型按算法及63/126期限分别选择，防止同期限的另一算法被覆盖。 */
    public List<JsonNode> models() {
        return store.db.query(
                "select details::text from (select distinct on(horizon,coalesce(details->>'algorithm','lightgbm')) horizon,details"
                        + " from gold_model where coalesce(details->>'algorithm','lightgbm') in ('lightgbm','xgboost')"
                        + " order by horizon,coalesce(details->>'algorithm','lightgbm'),registered_at desc,model_id) m order by horizon",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 正式前瞻只从当前可用数据生成；过期数据保留历史预测，不刷新生成日。 */
    public JsonNode predict() {
        requireEnabled();
        if (!benchmarkSourceHealthy()) {
            return store.json.valueToTree(
                    Map.of("status", "BLOCKED_SOURCE_UNAVAILABLE", "predictions", List.of()));
        }
        List<JsonNode> observations = observations();
        LocalDate latest =
                observations.stream()
                        .filter(row -> BENCHMARK.equals(row.path("series_id").asText()))
                        .map(row -> LocalDate.parse(row.path("observation_date").asText()))
                        .max(LocalDate::compareTo)
                        .orElse(null);
        if (latest == null
                || latest.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(10))) {
            return store.json.valueToTree(
                    Map.of("status", "BLOCKED_STALE_OR_MISSING_DATA", "predictions", List.of()));
        }
        List<JsonNode> models = models();
        if (models.isEmpty()) {
            return store.json.valueToTree(
                    Map.of("status", "BLOCKED_NO_MODEL", "predictions", List.of()));
        }
        JsonNode predicted =
                quant.post(
                        "/gold/predict",
                        Map.of(
                                "observations",
                                observations,
                                "models",
                                models,
                                "generated_at",
                                Instant.now().toString(),
                                "code_version",
                                codeVersion));
        List<JsonNode> frozen = new ArrayList<>();
        for (JsonNode prediction : predicted.path("predictions")) {
            frozen.add(freeze(prediction));
        }
        ObjectNode result = predicted.deepCopy();
        result.set("predictions", store.json.valueToTree(frozen));
        return result;
    }

    /** 并发和重启采用相同参考日/模型业务键；数据库触发器拒绝任何后写覆盖。 */
    private JsonNode freeze(JsonNode prediction) {
        String model = prediction.path("model_id").asText();
        int horizon = prediction.path("horizon").asInt();
        LocalDate reference = LocalDate.parse(prediction.path("reference_date").asText());
        Instant generated = Instant.parse(prediction.path("generated_at").asText());
        if (!List.of(63, 126).contains(horizon)
                || generated.isAfter(Instant.now().plusSeconds(60))
                || reference.isAfter(generated.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())) {
            throw new IllegalArgumentException("黄金预测时间或期限无效");
        }
        validateForecastEvidence(prediction, model, horizon, reference, generated);
        String key = "GOLD:" + horizon + ":" + reference + ":" + model;
        return tx.execute(
                status -> {
                    store.lock("gold:prediction:" + key);
                    List<JsonNode> found =
                            store.db.query(
                                    "select details::text from gold_prediction where business_key=?",
                                    (rs, row) -> store.decode(rs.getString(1)),
                                    key);
                    if (!found.isEmpty()) {
                        return found.get(0);
                    }
                    UUID id = UUID.randomUUID();
                    ObjectNode record = prediction.deepCopy();
                    record.put("id", id.toString());
                    record.put("business_key", key);
                    record.put("evaluation_type", "LIVE_FORWARD");
                    record.put("evaluation_status", "PENDING");
                    record.put("code_version", codeVersion);
                    store.db.update(
                            "insert into gold_prediction(id,business_key,horizon,model_id,"
                                    + "reference_date,generated_at,details)"
                                    + " values(?,?,?,?,?,?,?::jsonb)",
                            id,
                            key,
                            horizon,
                            model,
                            Date.valueOf(reference),
                            Timestamp.from(generated),
                            store.encode(record));
                    return record;
                });
    }

    /** 在不可变记录落库前核对期限、参考价和数据截止点，防止内部协议漂移污染长期评价。 */
    private void validateForecastEvidence(
            JsonNode prediction,
            String modelId,
            int horizon,
            LocalDate reference,
            Instant generated) {
        for (String field :
                List.of(
                        "reference_price",
                        "data_cutoff",
                        "target_date",
                        "target_date_status",
                        "label_version",
                        "feature_version",
                        "calibration_version",
                        "training_cutoff",
                        "fit_at")) {
            if (!prediction.hasNonNull(field) || prediction.path(field).asText().isBlank()) {
                throw new IllegalArgumentException("黄金冻结证据缺少：" + field);
            }
        }
        Instant cutoff = Instant.parse(prediction.path("data_cutoff").asText());
        if (cutoff.isAfter(generated)
                || Instant.parse(prediction.path("fit_at").asText()).isAfter(generated)
                || LocalDate.parse(prediction.path("training_cutoff").asText()).isAfter(reference)
                || !LocalDate.parse(prediction.path("target_date").asText()).isAfter(reference)
                || !prediction.path("input_snapshot").isObject()) {
            throw new IllegalArgumentException("黄金冻结证据存在未来信息或缺少输入快照");
        }
        Integer models =
                store.db.queryForObject(
                        "select count(*) from gold_model where model_id=? and horizon=?",
                        Integer.class,
                        modelId,
                        horizon);
        if (models != 1) {
            throw new IllegalArgumentException("黄金预测与已登记模型期限不一致");
        }
        String artifactHash =
                store.db.queryForObject(
                        "select details->'metadata'->>'artifact_sha256' from gold_model where model_id=?",
                        String.class,
                        modelId);
        if (artifactHash == null
                || !artifactHash.matches("[a-f0-9]{64}")
                || !artifactHash.equals(prediction.path("artifact_sha256").asText())) {
            throw new IllegalArgumentException("黄金预测工件摘要与已登记模型不一致");
        }
        String revision = prediction.path("input_snapshot").path("reference_observation").asText();
        List<BigDecimal> prices =
                store.db.query(
                        "select value from gold_observation where series_id=?"
                                + " and observation_date=? and revision=? and fetched_at<=?"
                                + " and (available_at is null or available_at<=?)"
                                + " and quality='REAL_OFFICIAL_CURRENT_VINTAGE'",
                        (rs, row) -> rs.getBigDecimal(1),
                        BENCHMARK,
                        Date.valueOf(reference),
                        revision,
                        Timestamp.from(cutoff),
                        Timestamp.from(cutoff));
        BigDecimal referencePrice = new BigDecimal(prediction.path("reference_price").asText());
        if (prices.isEmpty()
                || prices.stream().noneMatch(price -> price.compareTo(referencePrice) == 0)) {
            throw new IllegalArgumentException("黄金参考价未匹配截止前已保存的官方修订");
        }
        if (prediction.hasNonNull("probability_up")) {
            BigDecimal probability = new BigDecimal(prediction.path("probability_up").asText());
            if (probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("黄金上涨概率超出0至1范围");
            }
        }
        if (prediction.hasNonNull("interval_lower") != prediction.hasNonNull("interval_upper")) {
            throw new IllegalArgumentException("黄金预测区间必须同时提供上下界");
        }
        if (prediction.hasNonNull("interval_lower")) {
            BigDecimal lower = new BigDecimal(prediction.path("interval_lower").asText());
            BigDecimal upper = new BigDecimal(prediction.path("interval_upper").asText());
            if (lower.signum() <= 0 || upper.compareTo(lower) < 0) {
                throw new IllegalArgumentException("黄金预测区间无效");
            }
        }
    }

    /** 到期按之后真实上金所交易观测计数，追加实际目标日，不改原来预计日期。 */
    public Map<String, Object> evaluate() {
        List<JsonNode> pending =
                store.db.query(
                        "select p.details::text from gold_prediction p"
                                + " left join gold_actual a on a.prediction_id=p.id where a.prediction_id is null",
                        (rs, row) -> store.decode(rs.getString(1)));
        List<JsonNode> bars =
                observations().stream()
                        .filter(row -> BENCHMARK.equals(row.path("series_id").asText()))
                        .toList();
        List<JsonNode> calendars =
                store.db.query(
                        "select details->'calendar'->'dates' from gold_source where series_id=?",
                        (rs, row) ->
                                rs.getString(1) == null
                                        ? store.json.createArrayNode()
                                        : store.decode(rs.getString(1)),
                        BENCHMARK);
        JsonNode calendar = calendars.isEmpty() ? store.json.createArrayNode() : calendars.get(0);
        if (calendar.isEmpty() && !pending.isEmpty()) {
            return Map.of(
                    "status",
                    "BLOCKED_CALENDAR_UNAVAILABLE",
                    "appended",
                    0,
                    "pending_before",
                    pending.size());
        }
        Map<String, JsonNode> pricesByDate = new LinkedHashMap<>();
        bars.forEach(row -> pricesByDate.put(row.path("observation_date").asText(), row));
        int appended = 0;
        int gaps = 0;
        for (JsonNode forecast : pending) {
            List<String> subsequent = new ArrayList<>();
            for (JsonNode date : calendar) {
                if (date.asText().compareTo(forecast.path("reference_date").asText()) > 0) {
                    subsequent.add(date.asText());
                }
            }
            subsequent = subsequent.stream().distinct().sorted().toList();
            int horizon = forecast.path("horizon").asInt();
            if (subsequent.size() < horizon) {
                continue;
            }
            if (subsequent.subList(0, horizon).stream()
                    .anyMatch(date -> !pricesByDate.containsKey(date))) {
                gaps++;
                continue;
            }
            JsonNode target = pricesByDate.get(subsequent.get(horizon - 1));
            BigDecimal price = new BigDecimal(target.path("value").asText());
            BigDecimal reference = new BigDecimal(forecast.path("reference_price").asText());
            BigDecimal actualReturn =
                    price.divide(reference, MathContext.DECIMAL128).subtract(BigDecimal.ONE);
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("actual_target_date", target.path("observation_date").asText());
            actual.put("target_price", price);
            actual.put("actual_return", actualReturn);
            actual.put("actual_up", actualReturn.signum() > 0);
            actual.put("target_observation", target);
            actual.put("calendar_basis", "OBSERVED_SGE_TRADING_DATES");
            actual.put("calendar_version", "SGE_OBSERVED_SESSIONS_V1");
            actual.put("calendar_boundary", "按官方实际交易观测计数，未逐年独立核对交易日历完整性；不改变原预计目标日");
            actual.put("status", "MATURED");
            actual.put("evaluated_at", Instant.now().toString());
            if (forecast.hasNonNull("probability_up")) {
                BigDecimal probability = new BigDecimal(forecast.path("probability_up").asText());
                BigDecimal residual =
                        probability.subtract(
                                actualReturn.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
                actual.put("brier_score", residual.multiply(residual));
            }
            UUID id = UUID.fromString(forecast.path("id").asText());
            Boolean added =
                    tx.execute(
                            status -> {
                                store.lock("gold:actual:" + id);
                                Integer count =
                                        store.db.queryForObject(
                                                "select count(*) from gold_actual where prediction_id=?",
                                                Integer.class,
                                                id);
                                if (count > 0) {
                                    return false;
                                }
                                store.db.update(
                                        "insert into gold_actual(prediction_id,details) values(?,?::jsonb)",
                                        id,
                                        store.encode(actual));
                                return true;
                            });
            if (Boolean.TRUE.equals(added)) {
                appended++;
            }
        }
        return Map.of(
                "status",
                gaps > 0 ? "BLOCKED_CALENDAR_GAPS" : "EVALUATED",
                "appended",
                appended,
                "pending_before",
                pending.size(),
                "calendar_gaps",
                gaps);
    }

    /** 页面只返回冻结预测和另存实际结果，未到期保持待验证。 */
    public List<JsonNode> predictions() {
        return store.db.query(
                "select (p.details||jsonb_build_object('actual',a.details))::text"
                        + " from gold_prediction p left join gold_actual a on a.prediction_id=p.id"
                        + " order by p.generated_at desc,p.horizon limit 100",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 任务结果不复制整批输入，完整来源和观测已分别持久化。 */
    private JsonNode collectedSummary(JsonNode batch) {
        ObjectNode summary = store.json.createObjectNode();
        summary.put("observations", batch.path("observations").size());
        summary.set("sources", batch.path("sources"));
        summary.set("calendar", batch.path("calendar"));
        summary.set("collected_at", batch.path("collected_at"));
        return summary;
    }

    /** 运维关闭模块时停止新副作用，旧记录与页面仍保留。 */
    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalArgumentException("黄金模块已关闭，仅保留历史只读查看");
        }
    }
}
