package cn.etfquantlab.prediction;

import cn.etfquantlab.api.QuantClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 沿用管理员会话与CSRF；只保存选择，绝不通过保存操作触发付费分析。 */
@RestController
public class AiModelSettingsController {
    private final QuantClient quant;
    private final AiModelSettings settings;

    /** 模型目录和参数校验复用真正执行模型调用的分析服务。 */
    public AiModelSettingsController(QuantClient quant, AiModelSettings settings) {
        this.quant = quant;
        this.settings = settings;
    }

    /** 未知模型、强度或额外字段在落库前拒绝；仅对后续报价生效。 */
    @PostMapping("/api/system/model-settings/{provider}")
    public JsonNode save(@PathVariable String provider, @RequestBody JsonNode selection) {
        JsonNode validated =
                quant.post(
                        "/analysis/settings/validate",
                        Map.of("provider", provider, "selection", selection));
        return settings.save(provider, validated);
    }
}
