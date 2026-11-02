package cn.etfquantlab.agent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/** 令牌认证仅属于当前请求；浏览器登录继续使用原有会话存储。 */
public class BearerSecurityContextRepository implements SecurityContextRepository {
    private final SecurityContextRepository requestOnly =
            new RequestAttributeSecurityContextRepository();
    private final SecurityContextRepository browser =
            new DelegatingSecurityContextRepository(
                    new RequestAttributeSecurityContextRepository(),
                    new HttpSessionSecurityContextRepository());

    /** Bearer请求绝不从浏览器会话读取或向会话保存认证状态。 */
    private SecurityContextRepository repository(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? requestOnly
                : browser;
    }

    /** 为兼容旧版Spring调用加载匹配当前请求的上下文。 */
    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return repository(holder.getRequest()).loadContext(holder);
    }

    /** 延迟加载时仍按Bearer或会话分别读取。 */
    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return repository(request).loadDeferredContext(request);
    }

    /** 浏览器登录可以持久化；Bearer身份只保存在本次请求属性。 */
    @Override
    public void saveContext(
            SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        repository(request).saveContext(context, request, response);
    }

    /** 不因同一浏览器携带旧会话而让Bearer请求继承其权限。 */
    @Override
    public boolean containsContext(HttpServletRequest request) {
        return repository(request).containsContext(request);
    }
}
