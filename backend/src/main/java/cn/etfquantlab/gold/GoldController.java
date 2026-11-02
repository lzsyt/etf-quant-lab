package cn.etfquantlab.gold;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gold")
public class GoldController {
    private final GoldView view;
    private final GoldService gold;
    private final GoldCost cost;
    private final GoldCsv csv;
    private final Store store;
    private final TaskService tasks;

    /** 沿用现有管理员会话和CSRF；所有有副作用入口必须显式POST。 */
    public GoldController(
            GoldView view,
            GoldService gold,
            GoldCost cost,
            GoldCsv csv,
            Store store,
            TaskService tasks) {
        this.view = view;
        this.gold = gold;
        this.cost = cost;
        this.csv = csv;
        this.store = store;
        this.tasks = tasks;
    }

    /** 浏览页面只读取已保存研究事实。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return view.overview();
    }

    /** 计算器只计算用户情景，不保存持仓且不触发报价或模型调用。 */
    @PostMapping("/cost")
    public Map<String, Object> calculate(@RequestBody JsonNode request) {
        List<JsonNode> latest = new ArrayList<>();
        HashSet<Integer> horizons = new HashSet<>();
        for (JsonNode prediction : gold.predictions()) {
            if (horizons.add(prediction.path("horizon").asInt())) {
                latest.add(prediction);
            }
        }
        return cost.calculate(request, latest, Instant.now());
    }

    /** 预测完整证据另行按精确ID获取，普通总览不传输整份训练输入。 */
    @GetMapping("/predictions/{id}")
    public JsonNode evidence(@PathVariable UUID id) {
        List<JsonNode> rows =
                store.db.query(
                        "select (p.details||jsonb_build_object('actual',a.details))::text"
                                + " from gold_prediction p left join gold_actual a on a.prediction_id=p.id where p.id=?",
                        (rs, row) -> store.decode(rs.getString(1)),
                        id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("黄金预测不存在");
        }
        return rows.get(0);
    }

    /** 空模板只包含协议字段，绝不夹带可误入真实数据集的样例。 */
    @GetMapping("/import/template")
    public ResponseEntity<String> template() {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=gold-observations-template.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(GoldCsv.HEADER + "\r\n");
    }

    /** 手工CSV无论自报何种质量都标记未核验，不参与官方基准模型。 */
    @PostMapping("/import")
    public Map<String, Object> importCsv(@RequestBody JsonNode request) {
        return gold.importData(csv.parse(request.path("csv").asText()), true);
    }

    /** 初始化管理入口接收已核验采集器结构，权限边界与其他管理员数据写入一致。 */
    @PostMapping("/data/import")
    public Map<String, Object> importData(@RequestBody JsonNode request) {
        return gold.importData(request, false);
    }

    /** 开发机完成长历史训练后注册已部署工件，服务器不重复重训练。 */
    @PostMapping("/models/import")
    public Map<String, Object> importModels(@RequestBody JsonNode request) {
        return gold.importModels(request);
    }

    /** 人工明确启动免费任务才排队；业务键可复用以验证重启与去重。 */
    @PostMapping("/tasks")
    public Map<String, Object> task(@RequestBody JsonNode request) {
        String type = request.path("type").asText();
        if (!List.of("GOLD_SYNC", "GOLD_TRAIN", "GOLD_PREDICT", "GOLD_EVALUATE").contains(type)) {
            throw new IllegalArgumentException("不支持的黄金任务类型");
        }
        if (!gold.enabled()) {
            throw new IllegalArgumentException("黄金模块已关闭");
        }
        String key = request.path("business_key").asText(UUID.randomUUID().toString());
        if (key.length() > 180) {
            throw new IllegalArgumentException("业务键过长");
        }
        return tasks.enqueue(
                type,
                request.has("payload") ? request.path("payload") : store.json.createObjectNode(),
                key);
    }
}
