package cn.etfquantlab.scheduling;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forecast.ForecastPreparation;
import cn.etfquantlab.forecast.ForecastRunService;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldNews;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskService {
    private final Store store;
    private final MarketService market;
    private final ResearchService research;
    private final TransactionTemplate tx;
    private final DailyService daily;
    private final ResearchReportService reports;
    private final PredictionService predictions;
    private final ForwardService forward;
    private final AnalysisService news;
    private final ForwardNews forwardNews;
    // 保持已有任务构造契约，黄金仅在对应命名空间任务中使用。
    @org.springframework.beans.factory.annotation.Autowired private GoldService gold;
    @org.springframework.beans.factory.annotation.Autowired private GoldNews goldNews;
    @org.springframework.beans.factory.annotation.Autowired private FundResearchService funds;
    @org.springframework.beans.factory.annotation.Autowired private ForecastRunService forecastRuns;

    @org.springframework.beans.factory.annotation.Autowired
    private ForecastPreparation forecastPreparation;

    @org.springframework.beans.factory.annotation.Value("${etf.fund-scheduling-enabled:false}")
    private boolean fundScheduling;

    // 统一任务按资产保留黄金夜间窗口和队列优先级；手动run_id从原批次读取资产。
    private static final String GOLD_TASK =
            "(t.task_type like 'GOLD_%' or (t.task_type in ('FORECAST_RUN','FORECAST_PREPARE','FORECAST_EVALUATE') "
                    + "and coalesce(t.request->>'asset',(select r.request->>'asset' from forecast_run r where "
                    + "r.id::text=t.request->>'run_id'),'')='Au99.99'))";

    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "etf-task-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** 数据库队列替代内存队列；单个工作线程串行处理模型训练。 */
    public TaskService(
            Store store,
            MarketService market,
            ResearchService research,
            TransactionTemplate tx,
            DailyService daily,
            ResearchReportService reports,
            PredictionService predictions,
            ForwardService forward,
            AnalysisService news,
            ForwardNews forwardNews) {
        this.store = store;
        this.market = market;
        this.research = research;
        this.tx = tx;
        this.daily = daily;
        this.reports = reports;
        this.predictions = predictions;
        this.forward = forward;
        this.news = news;
        this.forwardNews = forwardNews;
    }

    /** 同一业务键在事务锁内只创建一条任务，重试返回原任务编号。 */
    public Map<String, Object> enqueue(String type, JsonNode payload, String key) {
        if (!List.of(
                        "TRAIN",
                        "BACKTEST",
                        "MARKET_SYNC",
                        "EOD",
                        "RESEARCH_REPORT",
                        "FORWARD_INIT",
                        "FORWARD_EOD",
                        "NEWS_COLLECT",
                        "NEWS_ANALYZE",
                        "GOLD_SYNC",
                        "GOLD_TRAIN",
                        "GOLD_PREDICT",
                        "GOLD_EVALUATE",
                        "GOLD_EXPLAIN",
                        "FUND_NAV",
                        "FUND_TRAIN",
                        "FUND_FORECAST",
                        "FUND_EVALUATE",
                        "FORECAST_RUN",
                        "FORECAST_EVALUATE",
                        "FORECAST_PREPARE",
                        "FUND_NEWS")
                .contains(type)) {
            throw new IllegalArgumentException("不支持的任务类型");
        }
        String businessKey = type + ":" + (key == null ? UUID.randomUUID() : key);
        UUID id =
                tx.execute(
                        status -> {
                            store.lock("task:" + businessKey);
                            List<UUID> found =
                                    store.db.query(
                                            "select id from task_execution where business_key=?",
                                            (rs, row) -> rs.getObject(1, UUID.class),
                                            businessKey);
                            if (!found.isEmpty()) {
                                if (List.of("FUND_TRAIN", "FUND_FORECAST").contains(type)
                                        && funds != null) {
                                    store.db.update(
                                            "update task_execution set status='QUEUED',updated_at=now() "
                                                    + "where id=? and status='WAITING_DATA' and "
                                                    + "result->>'dependency_version' is distinct from ?",
                                            found.get(0),
                                            funds.dependencyVersion(
                                                    payload.path("fund_code").asText()));
                                }
                                if (List.of("GOLD_TRAIN", "GOLD_PREDICT").contains(type)
                                        && gold != null
                                        && "WAITING_DATA"
                                                .equals(
                                                        store.db.queryForObject(
                                                                "select status from task_execution where id=?",
                                                                String.class,
                                                                found.get(0)))
                                        && gold.readyForResearch()
                                        && (!"GOLD_PREDICT".equals(type)
                                                || gold.currentMonthModelsReady(Instant.now()))) {
                                    // 只有数据/来源/模型真的变化才恢复同一任务，不按轮询时间无条件重试。
                                    store.db.update(
                                            "update task_execution set status='QUEUED',updated_at=now()"
                                                    + " where id=? and status='WAITING_DATA'"
                                                    + " and result->>'dependency_version' is distinct from ?",
                                            found.get(0),
                                            gold.dependencyVersion());
                                }
                                if ("EOD".equals(type) || "FORWARD_EOD".equals(type)) {
                                    // 连续失败后按小时恢复同一日终任务，免费源恢复后无需人工重建任务。
                                    store.db.update(
                                            "update task_execution set status='QUEUED',updated_at=now()"
                                                    + " where id=? and status='FAILED'"
                                                    + " and updated_at<now()-interval '1 hour'",
                                            found.get(0));
                                }
                                return found.get(0);
                            }
                            UUID created = UUID.randomUUID();
                            store.db.update(
                                    "insert into task_execution(id,business_key,task_type,status,request)"
                                            + " values(?,?,?,'QUEUED',?::jsonb)",
                                    created,
                                    businessKey,
                                    type,
                                    store.encode(payload));
                            return created;
                        });
        return Map.of("id", id, "business_key", businessKey);
    }

    /** 运行中的旧任务在租约过期后恢复；业务持久化还需各自执行幂等保护。 */
    @Scheduled(
            fixedDelayString = "${etf.worker-delay:2000}",
            initialDelayString = "${etf.worker-delay:2000}")
    public synchronized void consume() {
        consume(Instant.now());
    }

    /** 显式时钟仅用于黄金时段门禁；数据库租约仍以数据库当前时间计算。 */
    public synchronized void consume(Instant scheduledAt) {
        int hour = scheduledAt.atZone(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        UUID lease = UUID.randomUUID();
        JsonNode task;
        try {
            task =
                    tx.execute(
                            status -> {
                                List<JsonNode> tasks =
                                        store.db.query(
                                                "select row_to_json(t)::text from task_execution t"
                                                        + " where (status='QUEUED' or (status='RUNNING'"
                                                        + " and coalesce(heartbeat_at,"
                                                        + "updated_at)<now()-interval '10 minutes')"
                                                        + " or (status='FAILED' and attempts<3"
                                                        + " and updated_at<now()-interval '5 minutes'))"
                                                        // 定投比较已下线，历史任务原样保留且不再领取。
                                                        + " and task_type<>'FUND_COMPARE'"
                                                        // 统一黄金与原黄金同序等待同步/训练，避免抢跑后消耗周预测键。
                                                        + " and (not "
                                                        + GOLD_TASK
                                                        + " or ?)"
                                                        + " and (not "
                                                        + GOLD_TASK
                                                        + " or ?)"
                                                        + " order by case when "
                                                        + GOLD_TASK
                                                        + " then 1 else 0 end,"
                                                        + "updated_at limit 1 for update skip locked",
                                                (rs, row) -> store.decode(rs.getString(1)),
                                                gold != null && gold.enabled(),
                                                hour >= 20 || hour < 6);
                                if (tasks.isEmpty()) {
                                    return null;
                                }
                                JsonNode next = tasks.get(0);
                                store.db.update(
                                        "update task_execution set status='RUNNING',attempts=attempts+1,"
                                                + "result=null,error_code=null,"
                                                + "started_at=now(),updated_at=now(),"
                                                + "heartbeat_at=now(),lease_token=? where id=?",
                                        lease,
                                        UUID.fromString(next.path("id").asText()));
                                return next;
                            });
        } catch (RuntimeException error) {
            // 数据库短暂不可用时保留队列，下一轮轮询恢复；只记录类别，避免泄露连接凭据。
            LOG.warn("任务队列领取失败，将在下一轮重试：{}", error.getClass().getSimpleName());
            return;
        }
        if (task == null) {
            return;
        }
        UUID id = UUID.fromString(task.path("id").asText());
        MDC.put("trace_id", id.toString());
        var renew =
                heartbeat.scheduleAtFixedRate(
                        () -> renewLease(id, lease), 30, 30, TimeUnit.SECONDS);
        try {
            JsonNode payload = task.path("request");
            Object result =
                    switch (task.path("task_type").asText()) {
                        case "TRAIN" -> research.train(payload);
                        case "BACKTEST" ->
                                research.backtest(payload, task.path("business_key").asText());
                        case "MARKET_SYNC" ->
                                market.sync(store.json.convertValue(payload, Map.class));
                        case "EOD" -> daily.run();
                        case "FORWARD_INIT" -> forward.run(true);
                        case "FORWARD_EOD" -> forward.run(false);
                        case "FORECAST_RUN" -> executeForecast(payload, id);
                        case "FORECAST_EVALUATE" -> forecastRuns.evaluate(payload);
                        case "FORECAST_PREPARE" -> forecastPreparation.execute(payload);
                        // 新闻采集沿用串行队列，仅抓取公开信息，不收费、不生成交易决策。
                        case "NEWS_COLLECT" -> collectNews(id, payload);
                        case "GOLD_SYNC" -> gold.sync(payload);
                        case "GOLD_TRAIN" -> gold.train();
                        case "GOLD_PREDICT" -> gold.predict();
                        case "GOLD_EVALUATE" -> gold.evaluate();
                        case "GOLD_EXPLAIN" ->
                                unifiedEnabled()
                                        ? legacyAnalysisSkipped()
                                        : goldNews.analyze(Instant.now());
                        case "FUND_NAV" -> funds.sync(payload.path("fund_code").asText());
                        case "FUND_TRAIN" -> funds.train(payload.path("fund_code").asText());
                        case "FUND_FORECAST" -> funds.predict(payload.path("fund_code").asText());
                        case "FUND_EVALUATE" -> funds.evaluate(payload.path("fund_code").asText());
                        case "FUND_NEWS" ->
                                unifiedEnabled()
                                        ? legacyAnalysisSkipped()
                                        : funds.prepareNews(payload.path("fund_code").asText());
                        // 专项验证只分析已保存新闻，继续共用同日预算和去重；不产生交易决策。
                        case "NEWS_ANALYZE" ->
                                unifiedEnabled() ? legacyAnalysisSkipped() : analyzeNews(payload);
                        case "RESEARCH_REPORT" ->
                                reports.generate(
                                        payload.hasNonNull("trade_date")
                                                ? LocalDate.parse(
                                                        payload.path("trade_date").asText())
                                                : predictions.latestExpected(Instant.now()),
                                        Map.of(
                                                "status",
                                                "READ_ONLY_OBSERVATION",
                                                "reason",
                                                "按实际生成时刻归档，不触发行情抓取、预测、模拟交易或付费分析"));
                        default -> throw new IllegalArgumentException("未知任务");
                    };
            JsonNode resultNode = store.json.valueToTree(result);
            boolean waiting =
                    task.path("task_type").asText().startsWith("GOLD_")
                            && resultNode.path("status").asText().startsWith("BLOCKED");
            if (waiting) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) resultNode)
                        .put("dependency_version", gold.dependencyVersion());
            }
            boolean fundWaiting =
                    task.path("task_type").asText().startsWith("FUND_")
                            && (resultNode.path("status").asText().startsWith("BLOCKED")
                                    || "MODEL_NOT_READY"
                                            .equals(resultNode.path("status").asText()));
            if (fundWaiting) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) resultNode)
                        .put(
                                "dependency_version",
                                funds.dependencyVersion(payload.path("fund_code").asText()));
            }
            store.db.update(
                    "update task_execution set status=?,result=?::jsonb,"
                            + "error_code=null,updated_at=now(),heartbeat_at=now() where id=? and lease_token=?",
                    "FORECAST_RUN".equals(task.path("task_type").asText())
                                    && "RUNNING".equals(resultNode.path("status").asText())
                            ? "RUNNING"
                            : waiting || fundWaiting ? "WAITING_DATA" : "SUCCEEDED",
                    store.encode(resultNode),
                    id,
                    lease);
        } catch (RuntimeException error) {
            LOG.warn(
                    "任务执行失败：task={} type={} error={}",
                    id,
                    task.path("task_type").asText(),
                    error.getClass().getSimpleName());
            try {
                store.db.update(
                        "update task_execution set status='FAILED',error_code=?,result=?::jsonb,updated_at=now() "
                                + "where id=? and lease_token=?",
                        error.getClass().getSimpleName(),
                        store.encode(failureResult(id, task.path("task_type").asText())),
                        id,
                        lease);
                store.audit(
                        "TASK_FAILED",
                        Map.of("task_id", id, "error_code", error.getClass().getSimpleName()));
            } catch (RuntimeException unavailable) {
                // 数据库恢复后由过期租约恢复同一业务任务，不能将未落库状态误报成功。
                LOG.warn(
                        "任务失败状态暂不能持久化，将由租约恢复：task={} error={}",
                        id,
                        unavailable.getClass().getSimpleName());
            }
        } finally {
            renew.cancel(false);
            MDC.remove("trace_id");
        }
    }

    /** 免费采集成功且有真实已发布材料时才派生基金分析，重放父任务不会重复建子任务。 */
    private JsonNode collectNews(UUID taskId, JsonNode payload) {
        JsonNode result = news.collect();
        if (!payload.path("fund_followup").asBoolean(false)
                || !fundScheduling
                || funds == null
                || !funds.enabled()) {
            return result;
        }
        Instant now = Instant.now();
        boolean available = false;
        for (JsonNode item : result.path("items")) {
            if ("REAL".equals(item.path("nature").asText())
                    && item.path("publication_verified").asBoolean(false)
                    && item.hasNonNull("published_at")
                    && item.hasNonNull("fetched_at")
                    && !java.time.OffsetDateTime.parse(item.path("published_at").asText())
                            .toInstant()
                            .isAfter(now)
                    && !java.time.OffsetDateTime.parse(item.path("fetched_at").asText())
                            .toInstant()
                            .isAfter(now)) {
                available = true;
                break;
            }
        }
        if (available) {
            for (String code : FundResearchService.CODES) {
                if (unifiedEnabled()) {
                    enqueueForecast(code, "FUND_NAV_UP", 5, "news:" + taskId);
                    for (int horizon : List.of(20, 60))
                        enqueueForecast(code, "FUND_NAV_CHANGE", horizon, "news:" + taskId);
                    continue;
                }
                enqueue(
                        "FUND_NEWS",
                        store.json.valueToTree(Map.of("fund_code", code)),
                        "source-collection:" + taskId + ":" + code);
            }
        }
        return result;
    }

    /** 调度使用与手动请求相同的执行入口，各领域不再拥有独立收费预测链。 */
    public boolean unifiedEnabled() {
        return forecastRuns != null && forecastRuns.enabled();
    }

    /** 各数据准备完成后的同一组合以确定业务键排队，旧任务记录原样保留。 */
    public void enqueueForecast(String asset, String target, int horizon, String key) {
        enqueue(
                "FORECAST_RUN",
                store.json.valueToTree(
                        Map.of(
                                "asset", asset, "target", target, "horizon", horizon, "mode",
                                "REAL")),
                key + ":" + asset + ":" + target + ":" + horizon);
    }

    /** 调度任务首次执行再建立批次；任务重试以原任务ID保持幂等。 */
    private JsonNode executeForecast(JsonNode payload, UUID taskId) {
        UUID runId;
        if (payload.hasNonNull("run_id")) runId = UUID.fromString(payload.path("run_id").asText());
        else {
            var request = (com.fasterxml.jackson.databind.node.ObjectNode) payload.deepCopy();
            request.put("idempotency_key", "scheduled:" + taskId);
            runId = forecastRuns.create(request);
        }
        JsonNode result = forecastRuns.execute(runId);
        // 个体仍被有效租约持有时保留父任务恢复入口，不能提前结束任务或评价。
        if ("RUNNING".equals(result.path("status").asText())) return result;
        enqueue("FORECAST_EVALUATE", result.path("request"), "after-run:" + runId);
        return result;
    }

    /** 接管开关启用后旧解释任务不再收费，避免同资料同时进入两套大模型链。 */
    private Map<String, Object> legacyAnalysisSkipped() {
        return Map.of("status", "SKIPPED", "reason", "UNIFIED_FORECAST_TAKES_OVER");
    }

    /** 冻结本次前瞻失败的业务原因，避免健康投影被后续任务覆盖后丢失；不保存异常原文。 */
    private Object failureResult(UUID id, String type) {
        if (!List.of("FORWARD_EOD", "FORWARD_INIT").contains(type)) {
            return null;
        }
        List<JsonNode> failures =
                store.db.query(
                        "select jsonb_build_object('stage',h.stage,'reason',h.reason,"
                                + "'details',h.details)::text from forward_health h"
                                + " join task_execution t on h.trace_id=t.id::text"
                                + " where t.id=? and h.status='FAILED' and h.last_attempt_at>=t.started_at"
                                + " order by h.last_attempt_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        id);
        return failures.isEmpty() ? null : Map.of("failure", failures.get(0));
    }

    /** 人工明确请求新版本验证才使用额外一次资格；队列完成与模型验证结论分别返回。 */
    private Map<String, Object> analyzeNews(JsonNode payload) {
        JsonNode analysis =
                forwardNews.analyze(
                        Instant.now(), true, payload.path("validate_version").asBoolean(false));
        return Map.of(
                "mode",
                "LIVE_VALIDATION_ONLY",
                "analysis",
                analysis,
                "validation_outcome",
                analysis.path("validation_outcome").asText(),
                "reused",
                analysis.path("reused").asBoolean(),
                "requested_identity",
                analysis.path("requested_identity"),
                "decisions_generated",
                false);
    }

    /** 独立于执行线程续租，长训练不再被10分钟旧租约规则误判为崩溃。 */
    public void renewLease(UUID taskId, UUID lease) {
        try {
            store.db.update(
                    "update task_execution set heartbeat_at=now() where id=? and lease_token=? and status='RUNNING'",
                    taskId,
                    lease);
        } catch (RuntimeException error) {
            LOG.warn("任务心跳失败：task={} error={}", taskId, error.getClass().getSimpleName());
        }
    }

    /** 应用停止时退出守护线程；未完成任务留在数据库等待租约恢复。 */
    @PreDestroy
    public void closeHeartbeat() {
        heartbeat.shutdownNow();
    }
}
