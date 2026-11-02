package cn.etfquantlab.forecast;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 多预测个体执行与到期验证；新事实单独冻结，原生历史不迁移或覆写。 */
@Service
public class ForecastRunService {
    private final Store store;
    private final TransactionTemplate tx;
    private final ForecastInputs inputs;
    private final ForecastDeepSeek deepSeek;
    private final FundResearchService funds;
    private final GoldService gold;
    private final MarketService market;
    private final boolean enabled;

    @Value("${etf.gpt-enabled:${ETF_GPT_ENABLED:false}}")
    private boolean gptEnabled;

    /** 一个开关明确接管新预测，默认不因升级自动开启收费调度。 */
    public ForecastRunService(
            Store store,
            TransactionTemplate tx,
            ForecastInputs inputs,
            ForecastDeepSeek deepSeek,
            FundResearchService funds,
            GoldService gold,
            MarketService market,
            @Value("${etf.forecast-enabled:${ETF_FORECAST_ENABLED:false}}") boolean enabled) {
        this.store = store;
        this.tx = tx;
        this.inputs = inputs;
        this.deepSeek = deepSeek;
        this.funds = funds;
        this.gold = gold;
        this.market = market;
        this.enabled = enabled;
    }

    /** 运行与调度共用明确门禁，读取页面不改变开关。 */
    public boolean enabled() {
        return enabled;
    }

    /** 首批支持七资产的原生目标，输出能力不支持的组合不入计划。 */
    public record Selection(String asset, String target, int horizon) {}

    /** 全矩阵与单上下文共用验证，演示数据不能进入真实付费预测。 */
    public List<Selection> selections(JsonNode request) {
        if (!"REAL".equals(request.path("mode").asText("REAL")))
            throw new IllegalArgumentException("统一执行只支持REAL");
        List<Selection> all = new ArrayList<>();
        for (String asset : List.of("460300", "007169", "008701")) {
            all.add(new Selection(asset, "FUND_NAV_UP", 5));
            for (int horizon : List.of(20, 60))
                all.add(new Selection(asset, "FUND_NAV_CHANGE", horizon));
        }
        for (String asset : List.of("510300", "510500", "159915")) {
            all.add(new Selection(asset, "ETF_FORWARD_OPEN", 1));
            for (int horizon : List.of(20, 60))
                all.add(new Selection(asset, "ETF_CLOSE_RETURN", horizon));
            for (int horizon : List.of(1, 5))
                all.add(new Selection(asset, "ETF_LEGACY_CLOSE", horizon));
        }
        for (int horizon : List.of(63, 126))
            all.add(new Selection("Au99.99", "GOLD_BENCHMARK_RETURN", horizon));
        if (!request.hasNonNull("asset"))
            return all.stream()
                    .filter(selection -> !"ETF_LEGACY_CLOSE".equals(selection.target()))
                    .toList();
        Selection selected =
                new Selection(
                        request.path("asset").asText(),
                        request.path("target").asText(),
                        request.path("horizon").asInt());
        if (!all.contains(selected)) throw new IllegalArgumentException("资产目标期限不受支持");
        return List.of(selected);
    }

    /** 客户端幂等键只复用同一请求，不让重试生成新的收费批次。 */
    public UUID create(JsonNode request) {
        selections(request);
        String key = request.path("idempotency_key").asText(UUID.randomUUID().toString());
        if (!key.matches("[a-zA-Z0-9:_-]{1,160}")) throw new IllegalArgumentException("运行幂等键不合法");
        ObjectNode normalized = store.json.createObjectNode();
        normalized.put("mode", "REAL");
        if (request.hasNonNull("asset")) {
            normalized.put("asset", request.path("asset").asText());
            normalized.put("target", request.path("target").asText());
            normalized.put("horizon", request.path("horizon").asInt());
        }
        return tx.execute(
                status -> {
                    store.lock("forecast-run:" + key);
                    List<JsonNode> prior =
                            rows(
                                    "select row_to_json(r)::text from forecast_run r where business_key=?",
                                    key);
                    if (!prior.isEmpty()) {
                        if (!prior.get(0).path("request").equals(normalized))
                            throw new IllegalArgumentException("幂等键已用于不同预测请求");
                        return UUID.fromString(prior.get(0).path("id").asText());
                    }
                    UUID id = UUID.randomUUID();
                    store.db.update(
                            "insert into forecast_run(id,business_key,status,request) values(?,?,'QUEUED',?::jsonb)",
                            id,
                            key,
                            store.encode(normalized));
                    return id;
                });
    }

    /** 持久任务首次执行时固定截止；崩溃重试继续同批未完成个体。 */
    public JsonNode execute(UUID id) {
        JsonNode existingRun = get(id);
        if (!List.of("QUEUED", "RUNNING").contains(existingRun.path("status").asText()))
            return existingRun;
        tx.executeWithoutResult(
                status -> {
                    store.lock("forecast-run-id:" + id);
                    store.db.update(
                            "update forecast_run set cutoff_at=coalesce(cutoff_at,now()),status='RUNNING' where id=?",
                            id);
                });
        JsonNode run = get(id);
        Instant cutoff = Instant.parse(run.path("cutoff_at").asText());
        for (Selection selection : selections(run.path("request"))) prepare(id, selection, cutoff);
        for (JsonNode item :
                rows(
                        "select row_to_json(i)::text from forecast_run_item i where run_id=? "
                                + "order by case when scheme='GPT_DISAGREEMENT' then 1 else 0 end,asset,target,horizon,scheme",
                        id)) {
            if (!List.of("QUEUED", "RUNNING").contains(item.path("status").asText())) continue;
            UUID itemId = UUID.fromString(item.path("id").asText());
            UUID claim = UUID.randomUUID();
            int claimed =
                    store.db.update(
                            "update forecast_run_item set status='RUNNING',claim_token=?,updated_at=now() where id=? "
                                    + "and (status='QUEUED' or (status='RUNNING' and updated_at<now()-interval '10 minutes'))",
                            claim,
                            itemId);
            if (claimed == 0) continue;
            ((ObjectNode) item).put("claim_token", claim.toString());
            try {
                // 分歧分析已停用；旧排队或租约恢复项也不能再报价、预留费用或调用模型。
                if ("GPT_DISAGREEMENT".equals(item.path("scheme").asText())) {
                    finishItem(
                            item,
                            "BLOCKED",
                            "GPT_DISAGREEMENT_DISABLED",
                            store.json.createObjectNode());
                    continue;
                }
                JsonNode context = item.path("input_snapshot").path("context");
                if (context.hasNonNull("submission_deadline")
                        && !Instant.now()
                                .isBefore(
                                        Instant.parse(
                                                context.path("submission_deadline").asText()))) {
                    // 排队或恢复后目标开盘已过，必须在报价、预算预留和外呼之前阻断。
                    finishItem(
                            item,
                            "BLOCKED",
                            "PREDICTION_LATE_FOR_TARGET",
                            store.json.createObjectNode());
                    continue;
                }
                String scheme = item.path("scheme").asText();
                boolean review = "GPT_DISAGREEMENT".equals(scheme);
                boolean ai = List.of("DEEPSEEK", "GPT", "GPT_DISAGREEMENT").contains(scheme);
                JsonNode aiInput = item.path("input_snapshot").path("news_request");
                if (review) {
                    Integer pending =
                            store.db.queryForObject(
                                    "select count(*) from forecast_run_item where run_id=? and asset=? "
                                            + "and target=? and horizon=? and scheme<>'GPT_DISAGREEMENT' "
                                            + "and status in ('QUEUED','RUNNING')",
                                    Integer.class,
                                    id,
                                    item.path("asset").asText(),
                                    item.path("target").asText(),
                                    item.path("horizon").asInt());
                    if (pending > 0) {
                        // 其他worker仍在生成基础记录时不抢跑，下一轮只消费完成后的冻结结果。
                        store.db.update(
                                "update forecast_run_item set status='QUEUED',claim_token=null,updated_at=now() "
                                        + "where id=? and claim_token=? and status='RUNNING'",
                                itemId,
                                claim);
                        continue;
                    }
                    aiInput = reviewInput(item);
                    if (aiInput.path("predictions").size() < 2) {
                        finishItem(item, "BLOCKED", "INSUFFICIENT_COMPARABLE_PREDICTIONS", aiInput);
                        continue;
                    }
                }
                JsonNode response =
                        ai
                                ? "DEEPSEEK".equals(scheme)
                                        ? deepSeek.predict(itemId, aiInput)
                                        : deepSeek.predict(itemId, aiInput, scheme)
                                : inputs.infer(
                                        item.path("input_snapshot"), item.path("scheme").asText());
                if (ai && "REUSED".equals(response.path("status").asText())) {
                    store.db.update(
                            "update forecast_run_item set status='REUSED',result_id=?,error_code=?,"
                                    + "execution_result=?::jsonb,updated_at=now()"
                                    + " where id=? and claim_token=? and status='RUNNING'",
                            UUID.fromString(response.path("result_id").asText()),
                            "ORIGINAL_PREDICTION_TIME_PRESERVED",
                            store.encode(response),
                            itemId,
                            claim);
                } else if (ai
                        && (!"SUCCEEDED".equals(response.path("status").asText())
                                || !"AVAILABLE"
                                        .equals(response.path("result").path("status").asText()))) {
                    String state =
                            "ABSTAIN".equals(response.path("result").path("status").asText())
                                            || "ABSTAIN".equals(response.path("status").asText())
                                    ? "ABSTAIN"
                                    : "FAILED".equals(response.path("status").asText())
                                            ? "FAILED"
                                            : "BLOCKED";
                    finishItem(
                            item,
                            state,
                            response.path("error_code").asText("INSUFFICIENT_EVIDENCE"),
                            response);
                } else {
                    save(
                            item,
                            review
                                    ? normalizeReview(item, aiInput, response)
                                    : normalize(item, response, ai),
                            response);
                }
            } catch (RuntimeException error) {
                // 具体输入缺口保留受控代码；供应异常、连接字符串和凭据绝不落日志。
                String reason =
                        error instanceof IllegalArgumentException
                                        && error.getMessage() != null
                                        && error.getMessage().matches("[A-Z0-9_]{1,100}")
                                ? error.getMessage()
                                : "PREDICTOR_EXECUTION_FAILED";
                // 工件尚未训练属于准备条件不足，保留独立阻塞状态供页面明确展示。
                String status = "MODEL_NOT_READY".equals(reason) ? "BLOCKED" : "FAILED";
                finishItem(item, status, reason, store.json.createObjectNode());
            }
        }
        Integer good =
                store.db.queryForObject(
                        "select count(*) from forecast_run_item where run_id=? and status in ('SUCCEEDED','REUSED')",
                        Integer.class,
                        id);
        Integer total =
                store.db.queryForObject(
                        "select count(*) from forecast_run_item where run_id=?", Integer.class, id);
        Integer running =
                store.db.queryForObject(
                        "select count(*) from forecast_run_item where run_id=? and status in ('QUEUED','RUNNING')",
                        Integer.class,
                        id);
        store.db.update(
                "update forecast_run set status=? where id=?",
                running > 0
                        ? "RUNNING"
                        : good == 0 ? "BLOCKED" : good.equals(total) ? "COMPLETED" : "PARTIAL",
                id);
        return get(id);
    }

    /** 先冻结一组共享输入，再分别创建量化与DeepSeek执行项，失败不隐去计划分母。 */
    private void prepare(UUID runId, Selection selection, Instant cutoff) {
        Integer exists =
                store.db.queryForObject(
                        "select count(*) from forecast_run_item where run_id=? and asset=? and target=? and horizon=?",
                        Integer.class,
                        runId,
                        selection.asset(),
                        selection.target(),
                        selection.horizon());
        if (exists > 0) return;
        ObjectNode snapshot;
        String failure = null;
        try {
            snapshot =
                    inputs.freeze(
                            selection.asset(), selection.target(), selection.horizon(), cutoff);
        } catch (RuntimeException error) {
            snapshot = store.json.createObjectNode();
            failure =
                    error instanceof IllegalArgumentException
                                    && error.getMessage() != null
                                    && error.getMessage().matches("[A-Z0-9_]{1,100}")
                            ? error.getMessage()
                            : "INPUT_UNAVAILABLE";
        }
        ObjectNode frozen = snapshot;
        String reason = failure;
        List<String> schemes =
                new ArrayList<>(
                        switch (selection.target()) {
                            case "FUND_NAV_CHANGE", "FUND_NAV_UP" ->
                                    List.of("FUND_LIGHTGBM", "FUND_XGBOOST", "DEEPSEEK");
                            case "GOLD_BENCHMARK_RETURN" ->
                                    List.of("GOLD_LIGHTGBM", "GOLD_XGBOOST", "DEEPSEEK");
                            case "ETF_FORWARD_OPEN" -> List.of("LGBM_BASE", "XGB_BASE", "DEEPSEEK");
                            case "ETF_CLOSE_RETURN" ->
                                    List.of("CLOSE_LIGHTGBM", "CLOSE_XGBOOST", "DEEPSEEK");
                            default -> List.of("lightgbm", "xgboost", "DEEPSEEK");
                        });
        if (gptEnabled
                && List.of("FUND_NAV_UP", "FUND_NAV_CHANGE", "ETF_FORWARD_OPEN", "ETF_CLOSE_RETURN")
                        .contains(selection.target())) {
            schemes.add("GPT");
        }
        tx.executeWithoutResult(
                status -> {
                    store.lock("forecast-prepare:" + runId + ":" + selection);
                    if (store.db.queryForObject(
                                    "select count(*) from forecast_run_item where run_id=? and asset=? and target=? and horizon=?",
                                    Integer.class,
                                    runId,
                                    selection.asset(),
                                    selection.target(),
                                    selection.horizon())
                            > 0) return;
                    for (String scheme : schemes) {
                        store.db.update(
                                "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,"
                                        + "status,input_snapshot,error_code) values(?,?,?,?,?,?,?,?::jsonb,?)",
                                UUID.randomUUID(),
                                runId,
                                selection.asset(),
                                selection.target(),
                                selection.horizon(),
                                scheme,
                                reason == null ? "QUEUED" : "BLOCKED",
                                store.encode(frozen),
                                reason);
                    }
                });
    }

    /** 各原生响应转成同一目标下的实际新预测；不从情绪分数推导概率。 */
    private ObjectNode normalize(JsonNode item, JsonNode response, boolean ai) {
        JsonNode snapshot = item.path("input_snapshot");
        ObjectNode result = snapshot.path("context").deepCopy();
        String scheme = item.path("scheme").asText();
        String target = item.path("target").asText();
        if ((response.hasNonNull("nature") && !"REAL".equals(response.path("nature").asText()))
                || (ai && !"REAL".equals(response.path("nature").asText()))) {
            throw new IllegalArgumentException("NON_REAL_PREDICTION_REJECTED");
        }
        ObjectNode output = result.putObject("output");
        for (String name :
                List.of(
                        "point_return",
                        "probability_up",
                        "interval_lower",
                        "interval_upper",
                        "score")) output.putNull(name);
        JsonNode raw = response;
        if (ai) {
            if (!item.path("asset").asText().equals(response.path("asset").asText())
                    || !target.equals(response.path("target").asText())
                    || item.path("horizon").asInt() != response.path("horizon").asInt()
                    || !result.path("target_version")
                            .asText()
                            .equals(response.path("target_version").asText())) {
                throw new IllegalArgumentException("AI_TARGET_IDENTITY_MISMATCH");
            }
            JsonNode prediction = response.path("result");
            if (!prediction.path("output").isObject())
                throw new IllegalArgumentException("AI_OUTPUT_MISSING");
            output.setAll((ObjectNode) prediction.path("output"));
            result.put(
                    "model_version",
                    response.path("model_version").asText("deepseek")
                            + "/"
                            + response.path("prompt_version").asText("forecast-v2"));
        } else if ("FUND_NAV_CHANGE".equals(target) || "FUND_NAV_UP".equals(target)) {
            raw = response.path("forecasts").path(0);
            if (raw.path("points").path(0).path("horizon").asInt() != item.path("horizon").asInt()
                    || !item.path("asset").asText().equals(raw.path("fund_code").asText())
                    || !"REAL".equals(raw.path("nature").asText())
                    || !result.path("anchor_date").asText().equals(raw.path("anchor_date").asText())
                    || !result.path("target_version")
                            .asText()
                            .equals(raw.path("target_version").asText()))
                throw new IllegalArgumentException("QUANT_IDENTITY_MISMATCH");
            String model =
                    snapshot.path("quant_requests")
                            .path(scheme)
                            .path("payload")
                            .path("models")
                            .path(0)
                            .path("id")
                            .asText();
            if (model.isBlank() || !model.equals(raw.path("model_version_id").asText()))
                throw new IllegalArgumentException("QUANT_MODEL_IDENTITY_MISMATCH");
            verifyNativeAlgorithm(scheme, raw);
            String field = "FUND_NAV_UP".equals(target) ? "probability_up" : "point_return";
            output.set(field, raw.path("points").path(0).path(field));
            if ("FUND_NAV_UP".equals(target)) {
                result.put(
                        "probability_validation_status",
                        raw.path("probability_validation_status").asText("UNCALIBRATED"));
            }
            result.put("model_version", raw.path("model_version_id").asText());
            result.set("reason_codes", raw.path("reason_codes"));
            result.put("model_validation_status", raw.path("validation_status").asText("UNKNOWN"));
        } else if ("GOLD_BENCHMARK_RETURN".equals(target)) {
            raw = response.path("predictions").path(0);
            if (raw.path("horizon").asInt() != item.path("horizon").asInt()
                    || !result.path("anchor_date")
                            .asText()
                            .equals(raw.path("reference_date").asText())
                    || !result.path("target_version")
                            .asText()
                            .equals(raw.path("label_version").asText()))
                throw new IllegalArgumentException("QUANT_IDENTITY_MISMATCH");
            String model =
                    snapshot.path("quant_requests")
                            .path(scheme)
                            .path("payload")
                            .path("models")
                            .path(0)
                            .path("model_id")
                            .asText();
            if (model.isBlank() || !model.equals(raw.path("model_id").asText()))
                throw new IllegalArgumentException("QUANT_MODEL_IDENTITY_MISMATCH");
            verifyNativeAlgorithm(scheme, raw);
            output.set("point_return", raw.path("median_return"));
            for (String name : List.of("probability_up", "interval_lower", "interval_upper"))
                output.set(name, raw.path(name));
            output.put("interval_unit", "CNY_PER_GRAM");
            result.put("model_version", raw.path("model_id").asText());
            result.put(
                    "model_validation_status",
                    raw.path("quality").path("status").asText("UNKNOWN"));
        } else {
            String expectedModel =
                    snapshot.path("quant_requests")
                            .path(scheme)
                            .path("payload")
                            .path("model_id")
                            .asText();
            if (!"REAL".equals(response.path("nature").asText())
                    || !expectedModel.equals(response.path("model_id").asText())
                    || item.path("horizon").asInt() != response.path("horizon").asInt()) {
                throw new IllegalArgumentException("QUANT_IDENTITY_MISMATCH");
            }
            if ("ETF_CLOSE_RETURN".equals(target)) {
                verifyClosePrediction(item, result, response, scheme);
                output.set("point_return", response.path("point_return"));
                // 验证资格来自本次冻结工件，不能使用后来训练的模型状态改写本次研究结论。
                result.put(
                        "model_validation_status",
                        snapshot.path("quant_requests")
                                .path(scheme)
                                .path("model")
                                .path("validation_status")
                                .asText("UNKNOWN"));
                var reasons = new java.util.LinkedHashSet<String>();
                for (JsonNode existing : result.path("reason_codes"))
                    reasons.add(existing.asText());
                for (JsonNode limitation : response.path("limitations"))
                    reasons.add(limitation.asText());
                result.set("reason_codes", store.json.valueToTree(reasons));
            } else {
                output.set("probability_up", response.path("probability"));
            }
            result.put(
                    "model_version",
                    snapshot.path("quant_requests")
                            .path(scheme)
                            .path("payload")
                            .path("model_id")
                            .asText());
        }
        boolean probability =
                List.of("FUND_NAV_UP", "ETF_FORWARD_OPEN", "ETF_LEGACY_CLOSE").contains(target);
        BigDecimal value = decimal(output.path(probability ? "probability_up" : "point_return"));
        if (value == null
                || (probability && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0))
                || ("ETF_CLOSE_RETURN".equals(target)
                        && value.compareTo(BigDecimal.ONE.negate()) < 0))
            throw new IllegalArgumentException("INVALID_PREDICTION_OUTPUT");
        Instant generated = Instant.now();
        if ("ETF_FORWARD_OPEN".equals(target)
                && !generated.isBefore(
                        ForwardService.open(LocalDate.parse(result.path("anchor_date").asText()))))
            throw new IllegalArgumentException("PREDICTION_LATE_FOR_TARGET");
        if (result.hasNonNull("submission_deadline")
                && !generated.isBefore(
                        Instant.parse(result.path("submission_deadline").asText()))) {
            // 旧收盘协议只接受信号日当夜冻结，迟到后不再补成当时可用判断。
            throw new IllegalArgumentException("PREDICTION_LATE_FOR_TARGET");
        }
        result.put("id", UUID.randomUUID().toString());
        result.put("source", "unified");
        result.put("run_id", item.path("run_id").asText());
        result.put("scheme", scheme);
        result.put("role", "PREDICTOR");
        result.put("status", "FROZEN");
        result.put("generated_at", generated.toString());
        result.put(
                "output_type",
                probability
                        ? "PROBABILITY"
                        : output.hasNonNull("interval_lower")
                                ? "RETURN_INTERVAL_PROBABILITY"
                                : "RETURN_POINT");
        result.put(
                "opportunity_key",
                item.path("run_id").asText()
                        + ":"
                        + item.path("asset").asText()
                        + ":"
                        + target
                        + ":"
                        + item.path("horizon").asInt());
        result.put("selection_policy", ForecastPolicy.identity(snapshot, response, scheme));
        result.put("evidence_status", "FROZEN_RECORD_AVAILABLE");
        result.put(
                "explanation_status", ai ? "INDEPENDENT_PREDICTION_EVIDENCE" : "QUANT_INPUT_ONLY");
        ObjectNode evidence = result.putObject("evidence");
        evidence.set(
                "input",
                ai ? snapshot.path("news_request") : snapshot.path("quant_requests").path(scheme));
        evidence.set("result", raw);
        if (ai) {
            JsonNode quote =
                    rows(
                                    "select coalesce(quote,'{}'::jsonb)::text from forecast_run_item where id=?",
                                    UUID.fromString(item.path("id").asText()))
                            .get(0);
            evidence.set("catalog", quote.path("evidence_catalog"));
            result.set("analysis", response);
            result.put(
                    "probability_validation_status",
                    response.path("result")
                            .path("probability_validation_status")
                            .asText("UNCALIBRATED"));
        }
        return result;
    }

    /** 仅冻结同批同截止的可比较基础预测，不向分歧分析提供后续实际结果。 */
    private ObjectNode reviewInput(JsonNode item) {
        JsonNode snapshot = item.path("input_snapshot");
        if (snapshot.path("review_request").isObject())
            return snapshot.path("review_request").deepCopy();
        ObjectNode request = snapshot.path("news_request").deepCopy();
        JsonNode context = snapshot.path("context");
        var predictions = request.putArray("predictions");
        List<JsonNode> records =
                rows(
                        "select jsonb_build_object('id',r.id,'scheme',r.scheme,"
                                + "'data_cutoff',r.details->'data_cutoff','generated_at',r.generated_at,"
                                + "'output',jsonb_build_object('probability_up',r.details->'output'->>'probability_up',"
                                + "'point_return',r.details->'output'->>'point_return'),"
                                + "'summary',left(coalesce(r.details->'analysis'->'result'->>'summary',''),700),"
                                + "'model_validation_status',coalesce(r.details->>'model_validation_status','UNKNOWN'))::text "
                                + "from forecast_run_item i join forecast_result r on r.id=i.result_id "
                                + "where i.run_id=? and i.asset=? and i.target=? and i.horizon=? "
                                + "and i.status in ('SUCCEEDED','REUSED') and r.details->>'role'='PREDICTOR' "
                                + "and (r.details->>'data_cutoff')::timestamptz=? "
                                + "and r.details->>'anchor_date'=? and r.details->>'target_version'=? "
                                + "and r.details->>'target_date' is not distinct from ? "
                                + "order by r.scheme limit 4",
                        UUID.fromString(item.path("run_id").asText()),
                        item.path("asset").asText(),
                        item.path("target").asText(),
                        item.path("horizon").asInt(),
                        Timestamp.from(Instant.parse(context.path("data_cutoff").asText())),
                        context.path("anchor_date").asText(),
                        context.path("target_version").asText(),
                        context.path("target_date").asText(null));
        for (JsonNode record : records) {
            // 原生概率可为JSON数字，极小净值变化可为科学计数法；只规范本次输入表示，
            // 不改写已冻结基础事实，也不经过double转换丢失十进制精度。
            ObjectNode output = (ObjectNode) record.path("output");
            for (String field : List.of("probability_up", "point_return")) {
                if (output.hasNonNull(field))
                    output.put(field, new BigDecimal(output.path(field).asText()).toPlainString());
            }
            predictions.add(record);
        }
        ObjectNode frozen = snapshot.deepCopy();
        frozen.set("review_request", request);
        int changed =
                store.db.update(
                        "update forecast_run_item set input_snapshot=?::jsonb,updated_at=now() "
                                + "where id=? and claim_token=? and status='RUNNING'",
                        store.encode(frozen),
                        UUID.fromString(item.path("id").asText()),
                        UUID.fromString(item.path("claim_token").asText()));
        if (changed != 1) throw new IllegalArgumentException("REVIEW_CLAIM_EXPIRED");
        return request;
    }

    /** 分歧说明有独立记录身份，明确不属于数值预测或到期选优候选。 */
    private ObjectNode normalizeReview(JsonNode item, JsonNode input, JsonNode response) {
        ObjectNode result = item.path("input_snapshot").path("context").deepCopy();
        if (!"REAL".equals(response.path("nature").asText())
                || !"FORECAST_DISAGREEMENT_REVIEW".equals(response.path("domain").asText())
                || !item.path("asset").asText().equals(response.path("asset").asText())
                || !item.path("target").asText().equals(response.path("target").asText())
                || item.path("horizon").asInt() != response.path("horizon").asInt()
                || !result.path("target_version")
                        .asText()
                        .equals(response.path("target_version").asText())) {
            throw new IllegalArgumentException("REVIEW_OUTPUT_IDENTITY_MISMATCH");
        }
        result.put("id", UUID.randomUUID().toString());
        result.put("source", "unified");
        result.set("run_id", item.path("run_id"));
        result.put("scheme", "GPT_DISAGREEMENT");
        result.put("role", "REVIEW");
        result.put("status", "ANALYZED");
        result.put("output_type", "DISAGREEMENT_ANALYSIS");
        result.putObject("output");
        result.put("generated_at", Instant.now().toString());
        result.put(
                "model_version",
                response.path("model_version").asText()
                        + "/"
                        + response.path("prompt_version").asText());
        result.put("selection_policy", "");
        result.put("evidence_status", "FROZEN_RECORD_AVAILABLE");
        result.put("explanation_status", "DISAGREEMENT_ANALYSIS");
        result.set("analysis", response);
        ObjectNode evidence = result.putObject("evidence");
        evidence.set("input", input);
        evidence.set("result", response.path("result"));
        JsonNode quote =
                rows(
                                "select quote::text from forecast_run_item where id=?",
                                UUID.fromString(item.path("id").asText()))
                        .get(0);
        evidence.set("catalog", quote.path("evidence_catalog"));
        return result;
    }

    /** 预测与成功执行状态在同一事务冻结，崩溃不能留下半个成功或重复结果。 */
    private void save(JsonNode item, ObjectNode result, JsonNode response) {
        UUID itemId = UUID.fromString(item.path("id").asText());
        UUID id = UUID.fromString(result.path("id").asText());
        UUID claim = UUID.fromString(item.path("claim_token").asText());
        tx.executeWithoutResult(
                status -> {
                    store.lock("forecast-result:" + itemId);
                    if (store.db.queryForObject(
                                    "select count(*) from forecast_run_item where id=? and claim_token=? and status='RUNNING'",
                                    Integer.class,
                                    itemId,
                                    claim)
                            == 0) return;
                    List<UUID> saved =
                            store.db.query(
                                    "select id from forecast_result where item_id=? limit 1",
                                    (rs, n) -> rs.getObject(1, UUID.class),
                                    itemId);
                    if (!saved.isEmpty()) {
                        store.db.update(
                                "update forecast_run_item set "
                                        + "status='SUCCEEDED',result_id=?,error_code=null,updated_at=now() where "
                                        + "id=? and claim_token=?",
                                saved.get(0),
                                itemId,
                                claim);
                        return;
                    }
                    store.db.update(
                            "insert into "
                                    + "forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,"
                                    + "details) values(?,?,?,?,?,?,?,?,?::jsonb)",
                            id,
                            itemId,
                            UUID.fromString(item.path("run_id").asText()),
                            item.path("asset").asText(),
                            item.path("target").asText(),
                            item.path("horizon").asInt(),
                            item.path("scheme").asText(),
                            Timestamp.from(Instant.parse(result.path("generated_at").asText())),
                            store.encode(result));
                    store.db.update(
                            "update forecast_run_item set "
                                    + "status='SUCCEEDED',result_id=?,execution_result=?::jsonb,error_code=null,"
                                    + "updated_at=now() where id=?",
                            id,
                            store.encode(response),
                            itemId);
                });
    }

    /** 中期回归必须返回冻结的收盘锚点、终点及版本，不能误接旧概率输出。 */
    private void verifyClosePrediction(
            JsonNode item, JsonNode context, JsonNode response, String scheme) {
        String algorithm = "CLOSE_XGBOOST".equals(scheme) ? "xgboost" : "lightgbm";
        BigDecimal anchor = decimal(response.path("anchor_value"));
        BigDecimal expected = decimal(context.path("anchor_value"));
        if (!item.path("asset").asText().equals(response.path("symbol").asText())
                || !algorithm.equals(response.path("algorithm").asText())
                || !CloseModels.LABEL.equals(response.path("label_version").asText())
                || !CloseModels.PROTOCOL.equals(response.path("protocol_version").asText())
                || !CloseModels.ROLE.equals(response.path("model_role").asText())
                || !"RAW".equals(response.path("price_basis").asText())
                || !sameInstant(context.path("data_cutoff"), response.path("input_cutoff_at"))
                || response.hasNonNull("probability")
                || response.hasNonNull("probability_up")
                || !context.path("anchor_date")
                        .asText()
                        .equals(response.path("signal_date").asText())
                || !context.path("target_date")
                        .asText()
                        .equals(response.path("target_date").asText())
                || anchor == null
                || expected == null
                || anchor.compareTo(expected) != 0) {
            throw new IllegalArgumentException("QUANT_CLOSE_IDENTITY_MISMATCH");
        }
    }

    /** 截止时刻按UTC瞬间比较，兼容Z和等价时区表示；缺少或非法时间拒绝。 */
    private boolean sameInstant(JsonNode expected, JsonNode actual) {
        try {
            return Instant.parse(expected.asText()).equals(Instant.parse(actual.asText()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** 原生输出必须与本执行项算法一致，缺算法的历史响应仅兼容LightGBM。 */
    private void verifyNativeAlgorithm(String scheme, JsonNode result) {
        String expected = scheme.endsWith("XGBOOST") ? "xgboost" : "lightgbm";
        if (!expected.equals(result.path("algorithm").asText("lightgbm"))) {
            throw new IllegalArgumentException("QUANT_ALGORITHM_MISMATCH");
        }
    }

    /** 各个体失败独立留存，失败不会删除同批其他个体成功结果。 */
    private void finishItem(JsonNode item, String status, String reason, JsonNode response) {
        store.db.update(
                "update forecast_run_item set status=?,error_code=?,execution_result=?::jsonb,"
                        + "updated_at=now() where id=? and claim_token=? and status='RUNNING'",
                status,
                reason,
                store.encode(response),
                UUID.fromString(item.path("id").asText()),
                UUID.fromString(item.path("claim_token").asText()));
    }

    /** 批次查询只返回状态和结果引用，未公开完整量化工件路径或报价明细。 */
    public JsonNode get(UUID id) {
        List<JsonNode> found =
                rows("select row_to_json(r)::text from forecast_run r where id=?", id);
        if (found.isEmpty()) throw new IllegalArgumentException("预测批次不存在");
        ObjectNode run = found.get(0).deepCopy();
        run.set(
                "items",
                store.json.valueToTree(
                        rows(
                                "select jsonb_build_object('id',id,'asset',asset,'target',target,"
                                        + "'horizon',horizon,'scheme',scheme,'status',status,'result_id',result_id,"
                                        + "'error_code',error_code,'updated_at',updated_at)::text"
                                        + " from forecast_run_item where run_id=? order by asset,target,horizon,scheme",
                                id)));
        return run;
    }

    /** 按当前资产上下文列出最近批次，包括尚未成功的个体，不以有结果记录代替机会分母。 */
    public Map<String, Object> list(JsonNode selection) {
        List<JsonNode> result = new ArrayList<>();
        List<JsonNode> candidates;
        if (selection.hasNonNull("asset")) {
            selections(selection);
            candidates =
                    rows(
                            "select to_jsonb(r.id)::text from forecast_run r"
                                    + " where (r.request->>'asset'=? and r.request->>'target'=?"
                                    + " and (r.request->>'horizon')::integer=?)"
                                    + " or not(r.request ?? 'asset') order by r.created_at desc limit 20",
                            selection.path("asset").asText(),
                            selection.path("target").asText(),
                            selection.path("horizon").asInt());
        } else
            candidates =
                    rows(
                            "select to_jsonb(id)::text from forecast_run order by created_at desc limit 20");
        for (JsonNode candidate : candidates) result.add(get(UUID.fromString(candidate.asText())));
        return Map.of("runs", result, "enabled", true, "automatic_enabled", enabled);
    }

    /** 到期只读真实行情追加实际结果，不重新调用模型或回写预测。 */
    public Map<String, Object> evaluate(JsonNode request) {
        List<Selection> selected = selections(request);
        int appended = 0;
        List<JsonNode> candidates = new ArrayList<>();
        for (Selection selection : selected) {
            // 各组合独立推进，长周期未到期样本不能占满其他资产的评价窗口。
            // 到期只需冻结锚点与目标身份；完整证据含多年行情，批量加载会耗尽服务堆内存。
            candidates.addAll(
                    rows(
                            "select jsonb_build_object('id',r.details->'id','asset',r.details->'asset',"
                                    + "'target',r.details->'target','horizon',r.details->'horizon',"
                                    + "'anchor_date',r.details->'anchor_date','anchor_value',r.details->'anchor_value',"
                                    + "'target_date',r.details->'target_date','generated_at',r.details->'generated_at',"
                                    + "'target_version',r.details->'target_version')::text from forecast_result r "
                                    + "left join forecast_result_actual a on a.forecast_id=r.id "
                                    + "where a.forecast_id is null and r.asset=? and r.target=? and r.horizon=? "
                                    + "and coalesce(r.details->>'role','PREDICTOR')='PREDICTOR' "
                                    + "order by r.generated_at limit 5000",
                            selection.asset(),
                            selection.target(),
                            selection.horizon()));
        }
        for (JsonNode result : candidates) {
            ObjectNode actual = actual(result);
            if (actual == null) continue;
            UUID id = UUID.fromString(result.path("id").asText());
            Boolean inserted =
                    tx.execute(
                            status -> {
                                store.lock("forecast-actual:" + id);
                                if (store.db.queryForObject(
                                                "select count(*) from forecast_result_actual where forecast_id=?",
                                                Integer.class,
                                                id)
                                        > 0) return false;
                                store.db.update(
                                        "insert into forecast_result_actual(forecast_id,details) values(?,?::jsonb)",
                                        id,
                                        store.encode(actual));
                                return true;
                            });
            if (Boolean.TRUE.equals(inserted)) appended++;
        }
        return Map.of("status", "EVALUATED", "appended", appended);
    }

    /** 基金按真实后续净值观测、黄金按官方观测日历，ETF按冻结目标交易日计算。 */
    private ObjectNode actual(JsonNode forecast) {
        String target = forecast.path("target").asText();
        String asset = forecast.path("asset").asText();
        LocalDate anchor = LocalDate.parse(forecast.path("anchor_date").asText());
        int horizon = forecast.path("horizon").asInt();
        BigDecimal start = decimal(forecast.path("anchor_value"));
        BigDecimal end;
        String date;
        ObjectNode evidence = store.json.createObjectNode();
        Instant now = Instant.now();
        Instant availableAt;
        if (List.of("FUND_NAV_CHANGE", "FUND_NAV_UP", "GOLD_BENCHMARK_RETURN").contains(target)) {
            boolean fund = target.startsWith("FUND_NAV_");
            List<JsonNode> observations =
                    fund
                            ? funds.observations(asset)
                            : gold.observations().stream()
                                    .filter(
                                            row ->
                                                    GoldService.BENCHMARK.equals(
                                                            row.path("series_id").asText()))
                                    .toList();
            String dateField = fund ? "nav_date" : "observation_date";
            List<JsonNode> subsequent =
                    observations.stream()
                            .filter(
                                    row ->
                                            LocalDate.parse(row.path(dateField).asText())
                                                            .isAfter(anchor)
                                                    && !LocalDate.parse(
                                                                    row.path(dateField).asText())
                                                            .isAfter(
                                                                    LocalDate.now(
                                                                            ZoneId.of(
                                                                                    "Asia/Shanghai"))))
                            .toList();
            if (subsequent.size() < horizon) return null;
            JsonNode observation = subsequent.get(horizon - 1);
            // 标签按观察日期排序确定终点；资料真实可得时刻晚于当前时点时仍应等待。
            availableAt = observationAvailableAt(observation);
            if (availableAt == null || availableAt.isAfter(now)) return null;
            evidence.set("target_observation", observation);
            date = observation.path(dateField).asText();
            end = decimal(observation.path(fund ? "unit_nav" : "value"));
            if (!fund) {
                List<JsonNode> calendars =
                        rows(
                                "select details->'calendar'->'dates' from gold_source where series_id=?",
                                GoldService.BENCHMARK);
                if (calendars.isEmpty() || !calendars.get(0).isArray()) return null;
                List<String> dates = new ArrayList<>();
                calendars
                        .get(0)
                        .forEach(
                                d -> {
                                    if (d.asText().compareTo(anchor.toString()) > 0
                                            && d.asText().compareTo(date) <= 0)
                                        dates.add(d.asText());
                                });
                if (dates.stream().distinct().count() != horizon) return null;
            }
        } else {
            LocalDate endDay;
            if ("ETF_CLOSE_RETURN".equals(target) && !forecast.hasNonNull("target_date")) {
                // 跨年官方日历尚未公布时保留原预测空终点；日后只在实际结果追加确认日期。
                List<LocalDate> dates =
                        store.db.query(
                                "select trade_date from trading_calendar where is_open and trade_date>? order by "
                                        + "trade_date limit ?",
                                (rs, n) -> rs.getDate(1).toLocalDate(),
                                java.sql.Date.valueOf(anchor),
                                horizon);
                if (dates.size() < horizon) return null;
                endDay = dates.get(horizon - 1);
            } else {
                endDay = LocalDate.parse(forecast.path("target_date").asText());
            }
            if (endDay.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) return null;
            String field = "ETF_FORWARD_OPEN".equals(target) ? "open" : "close";
            List<JsonNode> from = market.bars(asset, anchor, anchor, "REAL", "RAW");
            List<JsonNode> to = market.bars(asset, endDay, endDay, "REAL", "RAW");
            if (from.size() != 1 || to.size() != 1) return null;
            if (store.db.queryForObject(
                            "select count(*) from corporate_action where symbol=? and ex_date between ? and ?",
                            Integer.class,
                            asset,
                            java.sql.Date.valueOf(anchor),
                            java.sql.Date.valueOf(endDay))
                    > 0) return null;
            if ("ETF_FORWARD_OPEN".equals(target)) start = decimal(from.get(0).path(field));
            end = decimal(to.get(0).path(field));
            availableAt = observationAvailableAt(to.get(0));
            if ("ETF_FORWARD_OPEN".equals(target)) {
                Instant anchorAvailable = observationAvailableAt(from.get(0));
                if (anchorAvailable == null || availableAt == null) return null;
                if (anchorAvailable.isAfter(availableAt)) availableAt = anchorAvailable;
            }
            if (availableAt == null || availableAt.isAfter(now)) return null;
            evidence.set("observed_anchor", from.get(0));
            evidence.set("target_observation", to.get(0));
            date = endDay.toString();
        }
        if (start == null || start.signum() <= 0 || end == null) return null;
        if (!Instant.parse(forecast.path("generated_at").asText())
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate()
                .isBefore(LocalDate.parse(date))) return null;
        ObjectNode result = store.json.createObjectNode();
        result.put("status", "EVALUATED");
        result.put("actual_target_date", date);
        result.put(
                "actual_return",
                end.divide(start, MathContext.DECIMAL128).subtract(BigDecimal.ONE));
        result.put("target_price", end);
        result.put("anchor_value_used", start);
        result.set("evidence", evidence);
        result.put("evaluated_at", now.toString());
        result.put("actual_available_at", availableAt.toString());
        result.put("target_version", forecast.path("target_version").asText());
        result.put(
                "price_basis",
                target.startsWith("ETF_")
                        ? "RAW"
                        : target.startsWith("FUND_NAV_") ? "UNIT_NAV" : "SGE_OFFICIAL_CLOSE");
        return result;
    }

    /** 可评分标签必须已被系统实际获取，未知首次公开时间不得伪造成历史可用。 */
    private Instant observationAvailableAt(JsonNode observation) {
        Instant available = null;
        for (String field : List.of("first_observed_at", "fetched_at", "available_at")) {
            if (!observation.hasNonNull(field)) continue;
            try {
                Instant timestamp = Instant.parse(observation.path(field).asText());
                if (available == null || timestamp.isAfter(available)) available = timestamp;
            } catch (RuntimeException invalidTime) {
                return null;
            }
        }
        return available;
    }

    /** 统一参数化查询入口，不接受外部表名和SQL片段。 */
    private List<JsonNode> rows(String sql, Object... args) {
        return store.db.query(sql, (rs, n) -> store.decode(rs.getString(1)), args);
    }

    /** 缺失或非法数值不转成零，不产生虚假预测或实际收益。 */
    private BigDecimal decimal(JsonNode value) {
        try {
            return value.isTextual() || value.isNumber() ? new BigDecimal(value.asText()) : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
