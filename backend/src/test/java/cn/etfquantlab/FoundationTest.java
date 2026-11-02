package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@AutoConfigureMockMvc
class FoundationTest {
    @Autowired private Store store;
    @Autowired private MarketService market;
    @Autowired private MockMvc mvc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private QuantClient quant;

    /** 测试只能写专属验收数据库，禁止误连正式研究库。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 使用真实 PostgreSQL 校验 Flyway 完成和领域表存在。 */
    @Test
    void migrationsAndVerifiedInstruments() {
        assertThat(store.db.queryForObject("select count(*) from etf_instrument", Integer.class))
                .isEqualTo(3);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from flyway_schema_history where success=false",
                                Integer.class))
                .isZero();
        assertThat(
                        store.db.queryForObject(
                                "select settlement_days from etf_instrument where symbol='510300'",
                                Integer.class))
                .isEqualTo(1);
    }

    /** 未登录读请求与缺少 CSRF 的写请求均不能进入业务层。 */
    @Test
    void authenticatedAndCsrfProtected() throws Exception {
        mvc.perform(get("/api/market/instruments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/market/instruments").with(user("test").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/market/sync").with(user("test").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    /** 重复同步只修订同一行情，不重复插入，价格小数精度保持不变。 */
    @Test
    void marketSyncIsIdempotent() {
        String source = "TEST_" + UUID.randomUUID();
        String fixture =
                """
                {"bars":[{"symbol":"510300","trade_date":"2024-01-03","open":"3.001",
                "high":"3.300","low":"2.900","close":"3.123","volume":"10000",
                "amount":"31230.00","price_basis":"RAW","source":"%s","nature":"DEMO",
                "fetched_at":"2026-10-24T00:00:00Z","quality_status":"VALID",
                "quality_flags":[],"raw_values":{}}],"report":{"count":1,"nature":"DEMO"}}
                """
                        .formatted(source);
        when(quant.post(eq("/market/fetch"), any())).thenReturn(store.decode(fixture));
        Map<String, Object> request = Map.of("symbol", "510300", "provider", "DEMO");
        market.sync(request);
        market.sync(request);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from market_bar where source=?",
                                Integer.class,
                                source))
                .isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select close::text from market_bar where source=?",
                                String.class,
                                source))
                .isEqualTo("3.123000");
    }

    /** 数据库层拒绝覆盖冻结预测，实际结果必须保存到独立表。 */
    @Test
    void predictionCannotBeOverwritten() {
        UUID id = UUID.randomUUID();
        store.db.update(
                "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                        + "input_cutoff,model_version,nature,direction,details)"
                        + " values(?,?,'510300','ma',1,'2024-01-03',now(),'test','DEMO','UP','{}')",
                id,
                id.toString());
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status ->
                                                store.db.update(
                                                        "update prediction set direction='DOWN' where id=?",
                                                        id)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
