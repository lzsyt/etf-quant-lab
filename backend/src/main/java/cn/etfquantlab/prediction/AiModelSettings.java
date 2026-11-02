package cn.etfquantlab.prediction;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 两个供应商的非敏感模型选择；密钥、代理和日预算仍由受保护部署配置管理。 */
@Service
public class AiModelSettings {
    private final Store store;
    private final TransactionTemplate tx;

    /** 设置与审计在同一数据库事务中保存，重启后不丢失。 */
    public AiModelSettings(Store store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    /** 未保存时返回空，由分析服务使用原部署默认值；不写入或清零旧配置。 */
    public JsonNode find(String provider) {
        List<JsonNode> rows =
                store.db.query(
                        "select jsonb_build_object('model',model,'reasoning_effort',reasoning_effort)::text "
                                + "from ai_model_setting where provider=?",
                        (rs, n) -> store.decode(rs.getString(1)),
                        provider);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 两条非敏感设置一次读取，不在同一个系统页请求中重复跨服务查询。 */
    public Map<String, JsonNode> all() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        store.db.query(
                "select provider,model,reasoning_effort from ai_model_setting",
                rs -> {
                    result.put(
                            rs.getString(1),
                            store.json
                                    .createObjectNode()
                                    .put("model", rs.getString(2))
                                    .put("reasoning_effort", rs.getString(3)));
                });
        return result;
    }

    /** 只接受分析服务已验证的两字段；供应商锁防止首次并发保存插入重复行。 */
    public JsonNode save(String provider, JsonNode selection) {
        if (!List.of("deepseek", "openai").contains(provider)) {
            throw new IllegalArgumentException("不支持的模型供应商");
        }
        return tx.execute(
                status -> {
                    store.lock("ai-model-settings:" + provider);
                    JsonNode previous = find(provider);
                    if (selection.equals(previous)) return previous;
                    if (previous == null) {
                        store.db.update(
                                "insert into ai_model_setting(provider,model,reasoning_effort) values(?,?,?)",
                                provider,
                                selection.path("model").asText(),
                                selection.path("reasoning_effort").asText());
                    } else {
                        store.db.update(
                                "update ai_model_setting set model=?,reasoning_effort=?,updated_at=now() where provider=?",
                                selection.path("model").asText(),
                                selection.path("reasoning_effort").asText(),
                                provider);
                    }
                    store.audit(
                            "AI_MODEL_SETTINGS_SAVED",
                            Map.of("provider", provider, "selection", selection));
                    return selection;
                });
    }
}
