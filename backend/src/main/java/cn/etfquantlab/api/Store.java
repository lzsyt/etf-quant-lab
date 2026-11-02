package cn.etfquantlab.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Store {
    public final JdbcTemplate db;
    public final ObjectMapper json;

    /** 所有 SQL 保持参数化；领域服务显式控制事务和业务幂等锁。 */
    public Store(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** 事务级锁保护同一业务标识，避免用额外唯一索引替代执行语义。 */
    public void lock(String businessKey) {
        db.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", result -> {}, businessKey);
    }

    /** 统一序列化持久化结果，失败时阻止写入不完整记录。 */
    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("结果无法序列化");
        }
    }

    /** 读取已存储的结构化研究结果。 */
    public JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库结果格式错误");
        }
    }

    /** 记录管理操作；调用方不得传入密码、令牌或模型密钥。 */
    public void audit(String action, Object details) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication == null ? "SYSTEM" : authentication.getName();
        db.update(
                "insert into operation_audit(id, action, details, actor) values (?, ?, ?::jsonb, ?)",
                UUID.randomUUID(),
                action,
                encode(details),
                actor);
    }
}
