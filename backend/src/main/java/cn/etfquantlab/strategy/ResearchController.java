package cn.etfquantlab.strategy;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ResearchController {
    private final Store store;
    private final TaskService tasks;

    /** 管理研究任务和只读结果，不在HTTP线程长时间训练。 */
    public ResearchController(Store store, TaskService tasks) {
        this.store = store;
        this.tasks = tasks;
    }

    /** 创建可恢复任务；客户端提供相同业务键即可安全重试。 */
    @PostMapping("/tasks")
    public Map<String, Object> submit(@RequestBody JsonNode request) {
        // 基金任务必须经过专属入口冻结服务端数据和拥有者，不能注入研究结果或他人身份。
        if (request.path("type").asText().startsWith("FUND_")) {
            throw new IllegalArgumentException("请通过基金研究专属入口创建任务");
        }
        return tasks.enqueue(
                request.path("type").asText(),
                request.path("payload"),
                request.hasNonNull("business_key") ? request.path("business_key").asText() : null);
    }

    /** 查询最近任务的执行状态，不返回模型密钥或密码。 */
    @GetMapping("/tasks")
    public List<JsonNode> tasks(Principal principal) {
        return store.db.query(
                "select row_to_json(t)::text from (select id,business_key,task_type,status,"
                        + "attempts,started_at,updated_at,error_code,"
                        + "result->'failure'->>'reason' as error_message from task_execution where (task_type<>'FUND_COMPARE' "
                        + "or request->>'owner_id'=?) order by"
                        + " updated_at desc limit 100) t",
                (rs, row) -> store.decode(rs.getString(1)),
                principal.getName());
    }

    /** 读取任务完整结果供前端轮询。 */
    @GetMapping("/tasks/{id}")
    public JsonNode task(@PathVariable UUID id, Principal principal) {
        return store.db.queryForObject(
                "select row_to_json(t)::text from task_execution t where id=? and "
                        + "(task_type<>'FUND_COMPARE' or request->>'owner_id'=?)",
                (rs, row) -> store.decode(rs.getString(1)),
                id,
                principal.getName());
    }

    /** 模型列表仅返回统计和训练边界，避免每次传输全部样本外预测。 */
    @GetMapping("/models")
    public List<JsonNode> models() {
        return store.db.query(
                "select row_to_json(m)::text from (select id,symbol,algorithm,horizon,"
                        + "feature_version,trained_at,training_range,hyp"
                        + "erparameters,metrics,metadata->>'nature' as n"
                        + "ature,"
                        + "metadata->>'data_cutoff' as data_cutoff from model_version order by trained_at desc) m",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 实验列表区分样本外研究和普通历史规则，不混入上线模拟结果。 */
    @GetMapping("/backtests")
    public List<JsonNode> experiments() {
        return store.db.query(
                "select row_to_json(e)::text from (select id,created_at,status,evaluation_type,nature,"
                        + "configuration->'request' as request,error_code from backtest_experiment"
                        + " order by created_at desc limit 100) e",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 返回单次冻结实验的资金曲线和成交明细。 */
    @GetMapping("/backtests/{id}")
    public JsonNode experiment(@PathVariable UUID id) {
        return store.db.queryForObject(
                "select row_to_json(e)::text from (select id,created_at,status,"
                        + "evaluation_type,nature,result,error_code from backtest_experiment where id=?) e",
                (rs, row) -> store.decode(rs.getString(1)),
                id);
    }

    /** 当前运行策略与配置。 */
    @GetMapping("/strategies")
    public List<JsonNode> strategies() {
        return store.db.query(
                "select row_to_json(s)::text from strategy_config s order by id",
                (rs, row) -> store.decode(rs.getString(1)));
    }
}
