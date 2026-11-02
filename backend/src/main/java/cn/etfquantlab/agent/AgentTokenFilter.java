package cn.etfquantlab.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AgentTokenFilter extends OncePerRequestFilter {
    private final AgentTokenService tokens;
    private static final String UUID_PATH =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> PATHS =
            Set.of(
                    "/api/dashboard",
                    "/api/rankings",
                    "/api/rankings/forward",
                    "/api/fusion/evaluation",
                    "/api/market/instruments",
                    "/api/market/bars",
                    "/api/market/quality",
                    "/api/market/quality-report",
                    "/api/market/indicators",
                    "/api/predictions",
                    "/api/news",
                    "/api/analyses",
                    "/api/models",
                    "/api/backtests",
                    "/api/strategies",
                    "/api/paper/accounts",
                    "/api/paper/positions",
                    "/api/paper/orders",
                    "/api/paper/fills",
                    "/api/paper/snapshots",
                    "/api/paper/reconstruction",
                    "/api/paper/diagnostics",
                    "/api/risk",
                    "/api/risk/events",
                    "/api/reports",
                    "/api/forward",
                    "/api/intelligence",
                    "/api/gold/overview",
                    "/api/funds");

    /** 访问令牌只适用于明确列出的研究数据接口。 */
    public AgentTokenFilter(AgentTokenService tokens) {
        this.tokens = tokens;
    }

    /** 携带令牌时不复用浏览器会话权限；拒绝非白名单及所有写请求。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.clearContext();
        if (!tokens.valid(authorization.substring(7))) {
            response.setStatus(401);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"GET".equals(request.getMethod()) || !allowed(path)) {
            response.setStatus(403);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "agent", null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 详情路径只接受UUID，避免路径前缀扩大授权范围。 */
    private boolean allowed(String path) {
        return PATHS.contains(path)
                || path.matches("/api/(backtests|reports)/[0-9a-fA-F-]{36}")
                || path.matches("/api/gold/predictions/[0-9a-fA-F-]{36}")
                || path.matches(
                        "/api/funds/(460300|007169|008701)/(nav|forecasts|news|research-summary)")
                || path.matches("/api/forecasts/" + UUID_PATH);
    }
}
