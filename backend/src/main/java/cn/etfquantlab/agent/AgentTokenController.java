package cn.etfquantlab.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent-tokens")
public class AgentTokenController {
    private final AgentTokenService tokens;

    /** 令牌管理仅供已有管理员会话调用。 */
    public AgentTokenController(AgentTokenService tokens) {
        this.tokens = tokens;
    }

    /** 展示元数据，不返回令牌正文。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return tokens.list();
    }

    /** 首次返回明文时禁止响应缓存。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(tokens.create(input.name(), input.days()));
    }

    /** 管理员主动撤销指定令牌。 */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        tokens.revoke(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(String name, int days) {}
}
