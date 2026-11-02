package cn.etfquantlab.account;

import cn.etfquantlab.api.Store;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminCredentialService implements UserDetailsService {
    private final Store store;
    private final TransactionTemplate transactions;
    private final String adminName;
    private final String bootstrapHash;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    /** 首次使用受保护部署哈希；网页改密后以数据库为准，不把环境旧值覆盖回来。 */
    public AdminCredentialService(
            Store store,
            TransactionTemplate transactions,
            @Value("${etf.admin-user}") String adminName,
            @Value("${etf.admin-password-hash}") String bootstrapHash) {
        if (!bootstrapHash.matches("\\$2[aby]\\$\\d{2}\\$.{53}")) {
            throw new IllegalStateException("必须配置有效的管理员BCrypt密码哈希");
        }
        this.store = store;
        this.transactions = transactions;
        this.adminName = adminName;
        this.bootstrapHash = bootstrapHash;
    }

    /** 每次认证读取持久化哈希，确保重启和重新登录始终使用新密码。 */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return User.withUsername(username)
                .password("{bcrypt}" + passwordHash(username))
                .roles("ADMIN")
                .build();
    }

    /** 事务锁下验证旧密码并更新哈希，两个并发改密请求不能互相覆盖。 */
    public void changePassword(
            String username, String currentPassword, String newPassword, String confirmation) {
        if (newPassword == null
                || newPassword.isBlank()
                || newPassword.length() < 8
                || newPassword.length() > 64) {
            throw new IllegalArgumentException("新密码需为8至64个字符");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("新密码过长，包含中文或特殊字符时请缩短");
        }
        if (!newPassword.equals(confirmation)) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
        transactions.executeWithoutResult(
                status -> {
                    store.lock("admin-password:" + username);
                    String hash = passwordHash(username);
                    if (currentPassword == null
                            || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                            || !encoder.matches(currentPassword, hash)) {
                        throw new IllegalArgumentException("当前密码不正确");
                    }
                    if (encoder.matches(newPassword, hash)) {
                        throw new IllegalArgumentException("新密码不能与当前密码相同");
                    }
                    String nextHash = encoder.encode(newPassword);
                    int updated =
                            store.db.update(
                                    "update admin_credential set password_hash=?,updated_at=now()"
                                            + " where username=?",
                                    nextHash,
                                    username);
                    if (updated == 0) {
                        store.db.update(
                                "insert into admin_credential(username,password_hash) values(?,?)",
                                username,
                                nextHash);
                    }
                    store.audit("ADMIN_PASSWORD_CHANGED", Map.of("username", username));
                });
    }

    /** 本版只支持配置指定的管理员；数据库尚无改密记录时兼容初始化密码。 */
    private String passwordHash(String username) {
        if (!adminName.equals(username)) {
            throw new UsernameNotFoundException("管理员不存在");
        }
        List<String> stored =
                store.db.query(
                        "select password_hash from admin_credential where username=?",
                        (rs, row) -> rs.getString(1),
                        username);
        return stored.isEmpty() ? bootstrapHash : stored.get(0);
    }
}
