package cn.etfquantlab.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ResearchReportController {
    private final ResearchViewService views;
    private final ResearchReportService reports;

    /** 只读研究页面复用现有会话鉴权，不增加可调用付费能力的按钮。 */
    public ResearchReportController(ResearchViewService views, ResearchReportService reports) {
        this.views = views;
        this.reports = reports;
    }

    /** 历史、严格样本外、前瞻预测、模拟账户由显式模式隔离。 */
    @GetMapping("/rankings")
    public JsonNode rankings(
            @RequestParam(defaultValue = "forward") String mode,
            @RequestParam(defaultValue = "1") int horizon) {
        return views.rankings(mode, horizon);
    }

    /** 列出已保存报告版本，无数据时返回空数组。 */
    @GetMapping("/reports")
    public List<JsonNode> reports() {
        return reports.list();
    }

    /** 读取指定不可变历史版本，绝不重新计算覆盖旧报告。 */
    @GetMapping("/reports/{id}")
    public JsonNode report(@PathVariable UUID id) {
        return reports.get(id);
    }
}
