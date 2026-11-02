package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.account.AdminCredentialService;
import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"etf.worker-delay=3600000", "etf.admin-user=password-test-admin"})
@AutoConfigureMockMvc
class PasswordTest {
    // 仅隔离测试库使用的合成凭据，不是任何真实账户的密码。
    private static final String USER = "password-test-admin";
    private static final String ORIGINAL = "Original-test-only-42!";
    private static final String NEXT = "Changed-test-only-43!";
    private static final String HASH = new BCryptPasswordEncoder().encode(ORIGINAL);
    @Autowired private Store store;
    @Autowired private MockMvc mvc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private QuantClient quant;

    /** 合成测试配置与运行环境凭据分离，不输出或读取生产口令。 */
    @DynamicPropertySource
    static void testPassword(DynamicPropertyRegistry registry) {
        registry.add("etf.admin-password-hash", () -> HASH);
    }

    /** 只重置测试管理员的改密记录，禁止对研究库或生产库运行。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("delete from admin_credential where username=?", USER);
    }

    /** 未登录和缺少CSRF均不能改密，保护入口不因新增功能而降低。 */
    @Test
    void changeRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(
                        post("/api/auth/password")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ORIGINAL, NEXT, NEXT)))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = login(ORIGINAL);
        mvc.perform(
                        post("/api/auth/password")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ORIGINAL, NEXT, NEXT)))
                .andExpect(status().isForbidden());
    }

    /** 错旧密码、确认不一致及过短新密码均保留旧凭据和会话。 */
    @Test
    void invalidChangesLeaveCredentialUnchanged() throws Exception {
        MockHttpSession session = login(ORIGINAL);
        for (String value :
                new String[] {
                    body("incorrect-test-value", NEXT, NEXT),
                    body(ORIGINAL, NEXT, NEXT + "x"),
                    body(ORIGINAL, "short", "short"),
                    body(ORIGINAL, ORIGINAL, ORIGINAL)
                }) {
            mvc.perform(
                            post("/api/auth/password")
                                    .session(session)
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(value))
                    .andExpect(status().isBadRequest());
        }
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from admin_credential where username=?",
                                Integer.class,
                                USER))
                .isZero();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    /** 真实认证链验证旧密码与其他会话失效，新密码可登录且重新构造服务仍能读取。 */
    @Test
    void changePersistsAndExpiresAllOldSessions() throws Exception {
        MockHttpSession first = login(ORIGINAL);
        MockHttpSession other = login(ORIGINAL);
        mvc.perform(
                        post("/api/auth/password")
                                .session(first)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ORIGINAL, NEXT, NEXT)))
                .andExpect(status().isNoContent());
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .param("username", USER)
                                .param("password", ORIGINAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(NEXT))).andExpect(status().isOk());

        var reconstructed = new AdminCredentialService(store, transactions, USER, HASH);
        String hash =
                reconstructed.loadUserByUsername(USER).getPassword().substring("{bcrypt}".length());
        assertThat(new BCryptPasswordEncoder().matches(NEXT, hash)).isTrue();
        assertThat(new BCryptPasswordEncoder().matches(ORIGINAL, hash)).isFalse();
        String audit =
                store.db.queryForObject(
                        "select details::text from operation_audit where action='ADMIN_PASSWORD_CHANGED'"
                                + " and actor=? order by created_at desc limit 1",
                        String.class,
                        USER);
        assertThat(audit).doesNotContain(ORIGINAL, NEXT, hash).contains(USER);
    }

    /** 使用实际表单认证创建会话，确保会话失效逻辑并非Mock用户绕过。 */
    private MockHttpSession login(String password) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .param("username", USER)
                                        .param("password", password))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    /** 仅构造请求体，不在失败断言中输出敏感字段。 */
    private String body(String current, String next, String confirmation) {
        return store.encode(
                Map.of(
                        "currentPassword",
                        current,
                        "newPassword",
                        next,
                        "confirmation",
                        confirmation));
    }
}
