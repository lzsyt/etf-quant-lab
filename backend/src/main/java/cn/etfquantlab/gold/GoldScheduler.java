package cn.etfquantlab.gold;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 黄金独立夜间排队；不复用ETF开市枚举、不改变ETF日终触发与优先级。 */
@Component
public class GoldScheduler {
    private final TaskService tasks;
    private final Store store;
    private final boolean scheduling;
    private final GoldService gold;
    private final LocalTime syncTime;
    private final LocalTime researchTime;
    private final DayOfWeek researchDay;

    /** 日期和时刻均按北京时间配置，持久业务键负责启动补偿与重复轮询去重。 */
    public GoldScheduler(
            TaskService tasks,
            Store store,
            GoldService gold,
            @Value("${etf.scheduling-enabled:false}") boolean scheduling,
            @Value("${etf.gold-sync-time:21:00}") String syncTime,
            @Value("${etf.gold-research-time:22:30}") String researchTime,
            @Value("${etf.gold-research-day:MONDAY}") String researchDay) {
        this.tasks = tasks;
        this.store = store;
        this.gold = gold;
        this.scheduling = scheduling;
        this.syncTime = LocalTime.parse(syncTime);
        this.researchTime = LocalTime.parse(researchTime);
        this.researchDay = DayOfWeek.valueOf(researchDay);
    }

    /** 自动调度只入队，单worker串行消费并由ETF优先级保护原日终。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 120000)
    public void schedule() {
        schedule(Instant.now());
    }

    /** 可注入时钟验证周/月键，停机后当夜补入同一周期，不重复创建业务任务。 */
    public void schedule(Instant scheduledAt) {
        if (!scheduling || !gold.enabled()) {
            return;
        }
        var now = scheduledAt.atZone(ZoneId.of("Asia/Shanghai"));
        if (now.toLocalTime().isBefore(syncTime)) {
            return;
        }
        tasks.enqueue(
                "GOLD_SYNC",
                store.json.valueToTree(Map.of()),
                "gold-daily-v1:" + now.toLocalDate());
        if (now.toLocalTime().isBefore(researchTime)) return;
        boolean trainReady = gold.readyForResearch();
        // 训练资料门槛只约束量化模型，DeepSeek独立判断不能被缺失模型整批挡住。
        if (trainReady) {
            tasks.enqueue(
                    "GOLD_TRAIN",
                    store.json.valueToTree(Map.of()),
                    "gold-monthly-dual-ml-v2:" + now.toLocalDate().withDayOfMonth(1));
        }
        var week = now.toLocalDate().with(TemporalAdjusters.previousOrSame(researchDay));
        if (tasks.unifiedEnabled()) {
            for (int horizon : java.util.List.of(63, 126)) {
                tasks.enqueueForecast("Au99.99", "GOLD_BENCHMARK_RETURN", horizon, "gold:" + week);
            }
            return;
        }
        if (!trainReady || !gold.currentMonthModelsReady(scheduledAt)) return;
        tasks.enqueue("GOLD_PREDICT", store.json.valueToTree(Map.of()), "gold-weekly-v1:" + week);
        tasks.enqueue(
                "GOLD_EXPLAIN", store.json.valueToTree(Map.of()), "gold-explanation-v1:" + week);
    }
}
