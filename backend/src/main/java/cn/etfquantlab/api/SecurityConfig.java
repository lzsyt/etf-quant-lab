package cn.etfquantlab.api;

import cn.etfquantlab.agent.AgentTokenFilter;
import cn.etfquantlab.agent.AgentTokenService;
import cn.etfquantlab.agent.BearerSecurityContextRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfig {
    /** 跟踪实际登录会话，改密后使同账号的所有旧会话失效。 */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** 会话退出或超时后及时清理注册信息，避免长期运行积累无效记录。 */
    @Bean
    HttpSessionEventPublisher sessionEvents() {
        return new HttpSessionEventPublisher();
    }

    /** 同源会话加 CSRF；健康检查只公开 UP/DOWN，其余业务 API 必须登录。 */
    @Bean
    SecurityFilterChain security(
            HttpSecurity http, SessionRegistry sessions, AgentTokenService tokens)
            throws Exception {
        return http.addFilterAfter(new AgentTokenFilter(tokens), SecurityContextHolderFilter.class)
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new BearerSecurityContextRepository()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/",
                                                "/index.html",
                                                "/assets/**",
                                                "/favicon.ico",
                                                "/api/auth/csrf",
                                                "/actuator/health",
                                                "/error")
                                        .permitAll()
                                        // 退役接口统一拒绝，保留历史表不会重新开放账本能力。
                                        .requestMatchers(
                                                "/api/fund-portfolios",
                                                "/api/fund-portfolios/**",
                                                "/api/fund-transactions/**",
                                                "/api/fund-plans/**",
                                                "/api/dca-comparisons",
                                                "/api/dca-comparisons/**")
                                        .denyAll()
                                        .anyRequest()
                                        .authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .sessionManagement(
                        management ->
                                management
                                        .maximumSessions(-1)
                                        .sessionRegistry(sessions)
                                        .expiredSessionStrategy(
                                                event -> event.getResponse().setStatus(401)))
                .formLogin(
                        form ->
                                form.loginProcessingUrl("/api/auth/login")
                                        .successHandler((req, res, user) -> res.setStatus(204))
                                        .failureHandler((req, res, error) -> res.setStatus(401))
                                        .permitAll())
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .logoutSuccessHandler(
                                                (req, res, user) -> res.setStatus(204)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
