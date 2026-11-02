package cn.etfquantlab.api;

import cn.etfquantlab.account.AdminCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AdminCredentialService credentials;
    private final SessionRegistry sessions;

    /** 认证接口只操作当前管理员，不接受客户端指定其他账号。 */
    public AuthController(AdminCredentialService credentials, SessionRegistry sessions) {
        this.credentials = credentials;
        this.sessions = sessions;
    }

    /** 登录前及登录后分别获取令牌，匹配会话认证的 CSRF 轮换。 */
    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }

    /** 仅返回当前登录身份，不返回配置和密钥。 */
    @GetMapping("/api/auth/me")
    public Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName(), "mode", "PAPER_ONLY");
    }

    /** 验证旧密码并持久化后注销全部旧会话；响应和审计均不包含密码。 */
    @PostMapping("/api/auth/password")
    public ResponseEntity<Void> changePassword(
            @RequestBody Map<String, String> input,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        credentials.changePassword(
                authentication.getName(),
                input.get("currentPassword"),
                input.get("newPassword"),
                input.get("confirmation"));
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails user
                    && authentication.getName().equals(user.getUsername())) {
                sessions.getAllSessions(principal, false).forEach(session -> session.expireNow());
            }
        }
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }
}
