package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@AutoConfigureMockMvc
class AgentTokenTest {
    @Autowired private Store store;
    @Autowired private MockMvc mvc;
    @MockitoBean private QuantClient quant;

    /** 测试仅针对专用隔离数据库，避免触及研究数据。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 验证创建、查询、只读权限、过期和撤销的完整请求链。 */
    @Test
    void tokenLifecycleAndReadOnlyBoundary() throws Exception {
        mvc.perform(
                        post("/api/agent-tokens")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"test\",\"days\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/agent-tokens")
                                .with(user("admin"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"test\",\"days\":1}"))
                .andExpect(status().isForbidden());
        String body =
                mvc.perform(
                                post("/api/agent-tokens")
                                        .with(user("admin"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"isolated-test\",\"days\":1}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode created = store.json.readTree(body);
        String token = created.path("token").asText();
        UUID id = UUID.fromString(created.path("id").asText());
        try {
            assertThat(token).startsWith("etf_");
            assertThat(
                            store.db.queryForObject(
                                    "select token_hash from agent_access_token where id=?",
                                    String.class,
                                    id))
                    .doesNotContain(token);
            String list =
                    mvc.perform(get("/api/agent-tokens").with(user("admin")))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(list).doesNotContain(token).doesNotContain("token_hash");
            mvc.perform(get("/api/backtests").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mvc.perform(
                            get("/api/system")
                                    .header("Authorization", "Bearer " + token)
                                    .with(user("admin")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/agent-tokens").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/backtests")
                                    .header("Authorization", "Bearer etf_invalid")
                                    .with(user("admin")))
                    .andExpect(status().isUnauthorized());
            store.db.update(
                    "update agent_access_token set expires_at=now()-interval '1 second' where id=?",
                    id);
            mvc.perform(get("/api/backtests").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            store.db.update(
                    "update agent_access_token set expires_at=now()+interval '1 day' where id=?",
                    id);
            mvc.perform(
                            post("/api/agent-tokens/" + id + "/revoke")
                                    .with(user("admin"))
                                    .with(csrf()))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/backtests").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/backtests")).andExpect(status().isUnauthorized());
        } finally {
            store.db.update("delete from agent_access_token where id=?", id);
        }
    }

    /** 基金研究仍可只读访问；退役账本接口对令牌及管理员均不可用。 */
    @Test
    @Transactional
    void fundResearchRemainsReadableAndLedgerIsRetired() throws Exception {
        String body =
                mvc.perform(
                                post("/api/agent-tokens")
                                        .with(user("admin"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"retirement-test\",\"days\":1}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = store.json.readTree(body).path("token").asText();
        for (String path :
                java.util.List.of(
                        "/api/funds",
                        "/api/funds/460300/nav",
                        "/api/funds/007169/forecasts",
                        "/api/funds/008701/news",
                        "/api/funds/007169/research-summary")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        UUID account = UUID.randomUUID();
        store.db.update(
                "insert into fund_portfolio(id,owner_id,account_kind,name) values(?,?,'ACTUAL_MANUAL',?)",
                account,
                "admin",
                "退役历史数据隔离夹具");
        for (String path :
                java.util.List.of(
                        "/api/fund-portfolios",
                        "/api/fund-portfolios/" + account + "/summary",
                        "/api/fund-portfolios/" + account + "/dca-policy",
                        "/api/fund-portfolios/" + account + "/dca-plans",
                        "/api/fund-portfolios/" + account + "/cash-events",
                        "/api/fund-transactions/" + account + "/confirm",
                        "/api/fund-plans/" + account + "/accept",
                        "/api/dca-comparisons")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("admin"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("admin")).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(put(path).with(user("admin")).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        assertThat(
                        store.db.queryForObject(
                                "select name from fund_portfolio where id=?",
                                String.class,
                                account))
                .isEqualTo("退役历史数据隔离夹具");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/funds").session(session).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/funds").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/system").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
