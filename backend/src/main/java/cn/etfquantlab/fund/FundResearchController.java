package cn.etfquantlab.fund;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FundResearchController {
    private final FundResearchService funds;
    private final TaskService tasks;
    private final Store store;

    /** 新路由延用浏览器会话和CSRF，代理令牌白名单不扩大。 */
    public FundResearchController(FundResearchService funds, TaskService tasks, Store store) {
        this.funds = funds;
        this.tasks = tasks;
        this.store = store;
    }

    /** 核心基金目录及数据健康只读。 */
    @GetMapping("/api/funds")
    public Map<String, Object> catalog() {
        return funds.catalog();
    }

    /** 场外基金净值与旧场内ETF行情分开返回。 */
    @GetMapping("/api/funds/{code}/nav")
    public Map<String, Object> nav(@PathVariable String code) {
        return funds.nav(code);
    }

    /** 返回指定窗口的真实冻结记录，空记录属于合法等待状态。 */
    @GetMapping("/api/funds/{code}/forecasts")
    public Map<String, Object> forecasts(
            @PathVariable String code, @RequestParam(defaultValue = "20") int horizon) {
        return funds.forecasts(code, horizon);
    }

    /** 历史批次展开原始输入，不触发新模型。 */
    @GetMapping("/api/forecasts/{id}")
    public JsonNode forecast(@PathVariable UUID id) {
        return funds.forecast(id);
    }

    /** 研究结论对应同一冻结预测，最新新闻另行获取。 */
    @GetMapping("/api/funds/{code}/research-summary")
    public Map<String, Object> summary(
            @PathVariable String code, @RequestParam(defaultValue = "20") int horizon) {
        return funds.summary(code, horizon);
    }

    /** 新闻页只读取已有基金专属快照，不因筛选收费。 */
    @GetMapping("/api/funds/{code}/news")
    public JsonNode news(@PathVariable String code) {
        return funds.latestNews(code);
    }

    /** 显式任务排队，调用者无法通过此入口触发旧付费任务或其他资产。 */
    @PostMapping("/api/funds/{code}/tasks")
    public Map<String, Object> enqueue(@PathVariable String code, @RequestBody JsonNode request) {
        funds.requireCode(code);
        if (!funds.enabled()) {
            throw new IllegalArgumentException("基金研究任务已关闭");
        }
        String type = request.path("type").asText();
        if (!List.of("FUND_NAV", "FUND_TRAIN", "FUND_FORECAST", "FUND_EVALUATE", "FUND_NEWS")
                .contains(type)) {
            throw new IllegalArgumentException("不支持的基金任务");
        }
        String key = request.path("idempotency_key").asText();
        if (key.isBlank() || key.length() > 120) {
            throw new IllegalArgumentException("任务需要长度不超过120的幂等键");
        }
        return tasks.enqueue(
                type, store.json.valueToTree(Map.of("fund_code", code)), code + ":" + key);
    }
}
