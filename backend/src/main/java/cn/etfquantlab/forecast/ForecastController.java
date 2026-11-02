package cn.etfquantlab.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 统一预测读取入口，沿用管理员会话；浏览页面不触发训练、收费或预测写入。 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {
    private final ForecastService forecasts;

    /** 仅依赖只读聚合服务，避免页面查询进入任务执行链。 */
    public ForecastController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    /** 返回实际支持的资产及原生目标，不强行补齐所有资产模型组合。 */
    @GetMapping("/assets")
    public Map<String, Object> assets() {
        return forecasts.assets();
    }

    /** 最新资讯与所选历史预测分开读取，不触发采集或收费。 */
    @GetMapping("/news")
    public JsonNode news(
            @RequestParam String asset,
            @RequestParam String target,
            @RequestParam int horizon,
            @RequestParam String mode) {
        return forecasts.news(asset, target, horizon, mode);
    }

    /** 筛选身份必须完整，列表按稳定顺序分页并明确下一页状态。 */
    @GetMapping("/records")
    public Map<String, Object> records(
            @RequestParam String asset,
            @RequestParam String target,
            @RequestParam int horizon,
            @RequestParam String mode,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return forecasts.records(asset, target, horizon, mode, limit, offset);
    }

    /** 历史证据以来源和主键定位，并再次核验筛选归属，不回退最新记录。 */
    @GetMapping("/records/{source}/{id}")
    public JsonNode detail(
            @PathVariable String source,
            @PathVariable UUID id,
            @RequestParam String asset,
            @RequestParam String target,
            @RequestParam int horizon,
            @RequestParam String mode) {
        return forecasts.detail(source, id, asset, target, horizon, mode);
    }

    /** 评价仅使用已保存真实结果；不调用行情采集或把模拟收益当预测准确率。 */
    @GetMapping("/evaluations")
    public Map<String, Object> evaluations(
            @RequestParam String asset,
            @RequestParam String target,
            @RequestParam int horizon,
            @RequestParam String mode) {
        return forecasts.evaluations(asset, target, horizon, mode);
    }
}
