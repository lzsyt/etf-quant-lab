package cn.etfquantlab.scheduling;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardRules;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyScheduler {
    private final TaskService tasks;
    private final PredictionService predictions;
    private final Store store;
    private final boolean enabled;
    private final MarketService market;

    @Value("${etf.forward-enabled:false}")
    private boolean forwardEnabled;

    /** 部署环境显式开启自动调度，本地开发与测试默认关闭。 */
    public DailyScheduler(
            TaskService tasks,
            PredictionService predictions,
            Store store,
            MarketService market,
            @Value("${etf.scheduling-enabled:false}") boolean enabled) {
        this.tasks = tasks;
        this.predictions = predictions;
        this.store = store;
        this.enabled = enabled;
        this.market = market;
    }

    /** 只在已确认开市日17:30后入队；周末和官方休市不补旧任务或产生费用。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 90000)
    public void schedule() {
        schedule(Instant.now());
    }

    /** 可测试的调度时钟入口；真实运行仍由无参定时方法提供当前时刻。 */
    public void schedule(Instant scheduledAt) {
        if (!enabled) {
            return;
        }
        var now = scheduledAt.atZone(ZoneId.of("Asia/Shanghai"));
        if (now.toLocalTime().isBefore(LocalTime.of(17, 30))) {
            return;
        }
        try {
            Boolean open = market.tradingDayStatus(now.toLocalDate());
            if (open == null) {
                market.syncCalendar();
                open = market.tradingDayStatus(now.toLocalDate());
            }
            if (open == null) {
                throw new IllegalArgumentException("官方交易日历尚未覆盖调度日期");
            }
            if (!open) {
                return;
            }
            LocalDate latest = predictions.latestExpected(now.toInstant());
            if (!latest.equals(now.toLocalDate())) {
                throw new IllegalArgumentException("调度日期与最新已收盘交易日不符");
            }
            if (forwardEnabled) {
                // 新实验在单一任务内先行情再训练，旧策略只保留历史，不抢先训练或额外收费。
                tasks.enqueue(
                        "FORWARD_EOD",
                        store.json.valueToTree(Map.of()),
                        ForwardService.EXPERIMENT + ":" + ForwardRules.VERSION + ":" + latest);
                enqueueUnified(latest);
                return;
            }
            if (tasks.unifiedEnabled()) {
                // 统一预测默认使用开盘到开盘目标，准备对应模型后再预测，不启动旧收盘实验。
                for (String asset : java.util.List.of("510300", "510500", "159915")) {
                    for (String action : java.util.List.of("SYNC", "TRAIN")) {
                        tasks.enqueue(
                                "FORECAST_PREPARE",
                                store.json.valueToTree(
                                        Map.of(
                                                "asset",
                                                asset,
                                                "target",
                                                "ETF_FORWARD_OPEN",
                                                "horizon",
                                                1,
                                                "mode",
                                                "REAL",
                                                "action",
                                                action)),
                                "market-prepare:" + latest + ":" + asset + ":" + action);
                    }
                    tasks.enqueueForecast(asset, "ETF_FORWARD_OPEN", 1, "market:" + latest);
                    enqueueClose(asset, latest);
                }
                return;
            }
            // 每月按最新可用行情串行刷新；同月业务键使调度轮询不会反复训练。
            for (String symbol : predictions.symbols()) {
                for (String algorithm : java.util.List.of("lightgbm", "xgboost")) {
                    for (int horizon : new int[] {1, 5}) {
                        Integer fresh =
                                store.db.queryForObject(
                                        "select count(*) from model_version where symbol=?"
                                                + " and algorithm=? and horizon=? and metadata->>'nature'='REAL'"
                                                + " and metadata->>'model_parameter_version' is not null"
                                                + " and (metadata->>'data_cutoff')::date>=?",
                                        Integer.class,
                                        symbol,
                                        algorithm,
                                        horizon,
                                        java.sql.Date.valueOf(latest.minusDays(30)));
                        if (fresh == 0) {
                            tasks.enqueue(
                                    "TRAIN",
                                    store.json.valueToTree(
                                            Map.of(
                                                    "symbol",
                                                    symbol,
                                                    "algorithm",
                                                    algorithm,
                                                    "horizon",
                                                    horizon,
                                                    "start",
                                                    "2021-01-01",
                                                    "end",
                                                    latest.toString())),
                                    "monthly-v11:"
                                            + latest.toString().substring(0, 7)
                                            + ":"
                                            + symbol
                                            + ":"
                                            + algorithm
                                            + ":"
                                            + horizon);
                        }
                    }
                }
            }
            tasks.enqueue("EOD", store.json.valueToTree(Map.of()), "daily:" + latest);
            enqueueUnified(latest);
        } catch (IllegalArgumentException error) {
            // 日历未知时停止，不靠工作日猜测、不把旧行情日补成收费日终。
            store.audit(
                    "SCHEDULER_CALENDAR_UNAVAILABLE",
                    Map.of(
                            "date",
                            now.toLocalDate(),
                            "error_code",
                            error.getClass().getSimpleName()));
        }
    }

    /** 原市场同步和模拟结算完成后，统一入口生成独立预测，不另收旧解释费用。 */
    private void enqueueUnified(LocalDate day) {
        if (!tasks.unifiedEnabled()) return;
        for (String asset : java.util.List.of("510300", "510500", "159915")) {
            tasks.enqueueForecast(asset, "ETF_FORWARD_OPEN", 1, "market:" + day);
            enqueueClose(asset, day);
        }
    }

    /** 旧行情任务完成后补齐中期工件与预测；各期限有独立业务键，轮询不会重复训练。 */
    private void enqueueClose(String asset, LocalDate day) {
        for (int horizon : java.util.List.of(20, 60)) {
            tasks.enqueue(
                    "FORECAST_PREPARE",
                    store.json.valueToTree(
                            Map.of(
                                    "asset",
                                    asset,
                                    "target",
                                    "ETF_CLOSE_RETURN",
                                    "horizon",
                                    horizon,
                                    "mode",
                                    "REAL",
                                    "action",
                                    "TRAIN")),
                    "close-prepare:" + day + ":" + asset + ":" + horizon);
            tasks.enqueueForecast(asset, "ETF_CLOSE_RETURN", horizon, "market:" + day);
        }
    }
}
