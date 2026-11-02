package cn.etfquantlab.forward;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.scheduling.TaskService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forward")
public class ForwardController {
    private final ForwardView view;
    private final TaskService tasks;
    private final Store store;

    /** 复用现有会话鉴权和CSRF，不开放额外公网写入口。 */
    public ForwardController(ForwardView view, TaskService tasks, Store store) {
        this.view = view;
        this.tasks = tasks;
        this.store = store;
    }

    /** 返回新批次真实决策、独立账户及运行健康。 */
    @GetMapping
    public Map<String, Object> overview() {
        return view.overview();
    }

    /** 初始化排入既有串行队列，不在HTTP线程训练，不允许传入假历史时间。 */
    @PostMapping("/initialize")
    public Map<String, Object> initialize() {
        return tasks.enqueue(
                "FORWARD_INIT",
                store.json.valueToTree(Map.of()),
                ForwardService.EXPERIMENT + ":" + ForwardRules.VERSION);
    }
}
