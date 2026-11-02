package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.agent.AgentTokenService;
import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.prediction.AiModelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实隔离库验证保存、读取、授权和在途配置冻结，不连接外部模型。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
@AutoConfigureMockMvc
class AiModelSettingsTest {
    @Autowired private Store store;
    @Autowired private AiModelSettings settings;
    @Autowired private TransactionTemplate tx;
    @Autowired private MockMvc mvc;
    @Autowired private AgentTokenService tokens;
    @MockitoBean private QuantClient quant;

    /** 仅清理事务内测试设置，测试结束整体回滚。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        store.db.update("delete from ai_model_setting");
        when(quant.post(eq("/analysis/settings/validate"), any()))
                .thenAnswer(call -> store.json.valueToTree(call.getArgument(1)).path("selection"));
    }

    /** 保存不触发分析，重复保存不新增设置和审计，重建服务对象仍可读取。 */
    @Test
    void savedSelectionPersistsAndDoesNotChargeOrChangeOtherProvider() throws Exception {
        int costs = store.db.queryForObject("select count(*) from api_call_cost", Integer.class);
        int audit = store.db.queryForObject("select count(*) from operation_audit", Integer.class);
        String body = "{\"model\":\"gpt-6-luna\",\"reasoning_effort\":\"high\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(
                            post("/api/system/model-settings/openai")
                                    .with(user("admin"))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk());
        }
        assertThat(new AiModelSettings(store, tx).find("openai")).isEqualTo(store.decode(body));
        assertThat(settings.find("deepseek")).isNull();
        assertThat(store.db.queryForObject("select count(*) from ai_model_setting", Integer.class))
                .isEqualTo(1);
        assertThat(store.db.queryForObject("select count(*) from api_call_cost", Integer.class))
                .isEqualTo(costs);
        assertThat(store.db.queryForObject("select count(*) from operation_audit", Integer.class))
                .isEqualTo(audit + 1);
        verify(quant, never()).post(eq("/analysis/gpt/run"), any());
    }

    /** 管理设置沿用登录与CSRF；研究只读令牌即使请求附带CSRF仍不能写入。 */
    @Test
    void settingsRequireSessionAndCsrf() throws Exception {
        String body = "{\"model\":\"gpt-6.1-sol\",\"reasoning_effort\":\"low\"}";
        mvc.perform(
                        post("/api/system/model-settings/openai")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/system/model-settings/openai")
                                .with(user("admin"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/system/model-settings/openai")
                                .with(user("admin"))
                                .with(csrf())
                                .header(
                                        "Authorization",
                                        "Bearer " + tokens.create("模型设置只读测试", 1).get("token"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        assertThat(settings.all()).isEmpty();
        verify(quant, never()).post(eq("/analysis/settings/validate"), any());
    }

    /** 分析服务拒绝不支持的模型或强度时，原设置保持，不能先写库再补校验。 */
    @Test
    void invalidSelectionPreservesPreviousChoice() throws Exception {
        JsonNode original =
                store.decode("{\"model\":\"deepseek-flash\",\"reasoning_effort\":\"none\"}");
        settings.save("deepseek", original);
        when(quant.post(eq("/analysis/settings/validate"), any()))
                .thenThrow(new IllegalArgumentException("所选模型或推理强度不受支持"));
        mvc.perform(
                        post("/api/system/model-settings/deepseek")
                                .with(user("admin"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"model\":\"deepseek-flash\",\"reasoning_effort\":\"medium\"}"))
                .andExpect(status().isBadRequest());
        assertThat(settings.find("deepseek")).isEqualTo(original);
    }

    /** 实际Java HTTP边界只在quote读取最新设置，run沿用报价快照且不修改调用方对象。 */
    @Test
    void quotationAndExecutionKeepSameSelectionAcrossSave() throws Exception {
        JsonNode before = store.decode("{\"model\":\"gpt-6-luna\",\"reasoning_effort\":\"high\"}");
        JsonNode after = store.decode("{\"model\":\"gpt-6.1-sol\",\"reasoning_effort\":\"low\"}");
        settings.save("openai", before);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            QuantClient actual =
                    new QuantClient(
                            store.json,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "t".repeat(40),
                            settings);
            JsonNode quote = actual.post("/analysis/gpt/quote", Map.of());
            assertThat(quote.path("model_settings")).isEqualTo(before);
            settings.save("openai", after);
            JsonNode run =
                    actual.post(
                            "/analysis/gpt/run",
                            Map.of("model_settings", quote.path("model_settings")));
            assertThat(run.path("model_settings")).isEqualTo(before);
            assertThat(actual.post("/analysis/gpt-review/quote", Map.of()).path("model_settings"))
                    .isEqualTo(after);
            assertThat(actual.post("/analysis/forecast/quote", Map.of()).has("model_settings"))
                    .isFalse();
        } finally {
            server.stop(0);
        }
    }
}
