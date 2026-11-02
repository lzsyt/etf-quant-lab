package cn.etfquantlab.agent;

import cn.etfquantlab.api.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentTokenService {
    private final Store store;
    private final SecureRandom random = new SecureRandom();

    /** 令牌数据存储在项目数据库中，不保存明文。 */
    public AgentTokenService(Store store) {
        this.store = store;
    }

    /** 使用随机密钥创建有限期令牌，明文仅返回给本次创建请求。 */
    public Map<String, Object> create(String name, int days) {
        if (name == null
                || name.isBlank()
                || name.trim().length() > 100
                || days < 1
                || days > 365) {
            throw new IllegalArgumentException("名称不能为空且不超过100字，有效期须为1至365天");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "etf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(days, ChronoUnit.DAYS);
        store.db.update(
                "insert into agent_access_token(id,name,token_hash,expires_at) values (?,?,?,?)",
                id,
                name.trim(),
                hash(token),
                Timestamp.from(expires));
        return Map.of("id", id, "token", token, "expires_at", expires);
    }

    /** 查询令牌元数据，绝不返回摘要或明文。 */
    public List<Map<String, Object>> list() {
        return store.db.queryForList(
                "select id,name,created_at,expires_at,revoked_at from agent_access_token order by created_at desc");
    }

    /** 撤销操作立即生效，重复操作不会恢复已撤销令牌。 */
    public void revoke(UUID id) {
        store.db.update(
                "update agent_access_token set revoked_at=now() where id=? and revoked_at is null",
                id);
    }

    /** 每次请求查验有效期与撤销状态，避免缓存延迟撤销。 */
    public boolean valid(String token) {
        if (token == null || !token.matches("etf_[A-Za-z0-9_-]{43}")) {
            return false;
        }
        Integer count =
                store.db.queryForObject(
                        "select count(*) from agent_access_token where token_hash=?"
                                + " and revoked_at is null and expires_at>now()",
                        Integer.class,
                        hash(token));
        return count != null && count > 0;
    }

    /** 高熵随机令牌使用SHA-256摘要定位，不记录原始值。 */
    private String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
