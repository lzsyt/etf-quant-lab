package cn.etfquantlab.fund;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 基金净值窗口与新闻源检查独立调度，付费分析只在采集任务完成后按证据触发。 */
@Component
public class FundScheduler {
    private final TaskService tasks;
    private final FundResearchService funds;
    private final Store store;
    private final boolean scheduling;

    @Value("${etf.fund-news-poll-minutes:15}")
    private int newsPollMinutes = 15;

    /** 两个开关同时启用才调度，不因发布新页面自动抓数据或训练。 */
    public FundScheduler(
            TaskService tasks,
            FundResearchService funds,
            Store store,
            @Value("${etf.fund-scheduling-enabled:false}") boolean scheduling) {
        this.tasks = tasks;
        this.funds = funds;
        this.store = store;
        this.scheduling = scheduling;
    }

    /** 每分钟检查新闻轮询桶与净值公布窗口，持久业务键在重启后继续去重。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 120000)
    public void schedule() {
        schedule(Instant.now());
    }

    /** 时钟可注入测试，训练依赖真实净值，失败保留上次工件与任务状态。 */
    public void schedule(Instant instant) {
        if (!scheduling || !funds.enabled()) {
            return;
        }
        // 15分钟是免费检查延迟参数，不是新闻发布规律或固定收费时点。
        // 下限5分钟防止配置错误变成高频爬取；付费端仍按实际相关证据去重和预算限制。
        long intervalSeconds = Math.max(5L, newsPollMinutes) * 60L;
        long bucket = Math.floorDiv(instant.getEpochSecond(), intervalSeconds);
        tasks.enqueue(
                "NEWS_COLLECT",
                store.json.valueToTree(Map.of("fund_followup", true)),
                "fund-source-poll:" + intervalSeconds + ":" + bucket);
        var now = instant.atZone(ZoneId.of("Asia/Shanghai"));
        int hour = now.getHour();
        if (hour != 7 && hour != 21 && hour != 23) {
            return;
        }
        for (String code : FundResearchService.CODES) {
            var payload = store.json.valueToTree(Map.of("fund_code", code));
            tasks.enqueue("FUND_NAV", payload, code + ":" + now.toLocalDate() + ":" + hour);
            boolean trainReady = funds.observations(code).size() >= 600;
            if (trainReady)
                tasks.enqueue(
                        "FUND_TRAIN",
                        payload,
                        code + ":short-mid-v3:" + now.toLocalDate().withDayOfMonth(1));
            if (tasks.unifiedEnabled()) {
                tasks.enqueueForecast(
                        code, "FUND_NAV_UP", 5, "fund:" + now.toLocalDate() + ":" + hour);
                for (int horizon : java.util.List.of(20, 60)) {
                    tasks.enqueueForecast(
                            code,
                            "FUND_NAV_CHANGE",
                            horizon,
                            "fund:" + now.toLocalDate() + ":" + hour);
                }
                continue;
            }
            if (!trainReady) continue;
            // 队列串行消费，先抓取/训练再预测；每期结果独立冻结。
            tasks.enqueue("FUND_FORECAST", payload, code + ":" + now.toLocalDate() + ":" + hour);
            tasks.enqueue("FUND_EVALUATE", payload, code + ":" + now.toLocalDate() + ":" + hour);
        }
    }
}
