package cn.etfquantlab.forecast;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 显式生成与到期评价入口，沿用管理员会话和现有同源写入校验。 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastRunController {
    private final ForecastRunService runs;
    private final TaskService tasks;
    private final Store store;
    private final ForecastPreparation preparation;

    /** 控制器持有队列；执行服务不依赖队列，避免循环依赖。 */
    public ForecastRunController(
            ForecastRunService runs,
            TaskService tasks,
            Store store,
            ForecastPreparation preparation) {
        this.runs = runs;
        this.tasks = tasks;
        this.store = store;
        this.preparation = preparation;
    }

    /** 明确准备当前资产资料或量化模型，不借旧日终流程额外调用收费分析。 */
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody JsonNode request) {
        preparation.validate(request);
        String key = request.path("idempotency_key").asText(UUID.randomUUID().toString());
        if (!key.matches("[a-zA-Z0-9:_-]{1,160}")) {
            throw new IllegalArgumentException("准备任务幂等键不合法");
        }
        Map<String, Object> task =
                tasks.enqueue(
                        "FORECAST_PREPARE",
                        request,
                        request.path("asset").asText()
                                + ":"
                                + request.path("target").asText()
                                + ":"
                                + request.path("horizon").asInt()
                                + ":"
                                + request.path("action").asText()
                                + ":"
                                + key);
        String status =
                store.db.queryForObject(
                        "select status from task_execution where id=?",
                        String.class,
                        task.get("id"));
        return Map.of("task_id", task.get("id"), "status", status);
    }

    /** 批次与任务原子提交；同幂等键重试返回同一任务，不因网络重发重复收费。 */
    @PostMapping("/runs")
    @Transactional
    public Map<String, Object> create(@RequestBody JsonNode request) {
        UUID id = runs.create(request);
        Map<String, Object> task =
                tasks.enqueue(
                        "FORECAST_RUN",
                        store.json.valueToTree(Map.of("run_id", id)),
                        id.toString());
        return Map.of(
                "run_id",
                id,
                "task_id",
                task.get("id"),
                "status",
                runs.get(id).path("status").asText());
    }

    /** 读取近期批次及失败/弃权项，不触发任何模型或采集。 */
    @GetMapping("/runs")
    public Map<String, Object> list(
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) Integer horizon,
            @RequestParam(defaultValue = "REAL") String mode) {
        ObjectNode request = store.json.createObjectNode();
        request.put("mode", mode);
        if (asset != null) {
            request.put("asset", asset);
            request.put("target", target);
            if (horizon != null) request.put("horizon", horizon);
        }
        return runs.list(request);
    }

    /** 明确选择的批次保留其各个体状态及原预测引用。 */
    @GetMapping("/runs/{id}")
    public JsonNode get(@PathVariable UUID id) {
        return runs.get(id);
    }

    /** 到期评价仅追加事实，按当前选择或全矩阵执行，不调用外部模型。 */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody(required = false) JsonNode body) {
        JsonNode request = body == null ? store.json.createObjectNode() : body;
        runs.selections(request);
        Map<String, Object> task =
                tasks.enqueue(
                        "FORECAST_EVALUATE",
                        request,
                        request.path("idempotency_key").asText(UUID.randomUUID().toString()));
        return Map.of("task_id", task.get("id"), "status", "QUEUED");
    }
}
