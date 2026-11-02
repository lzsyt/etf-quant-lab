package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.etfquantlab.agent.AgentTokenService;
import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@AutoConfigureMockMvc
@Transactional
class ForecastHttpTest {
    @Autowired private Store store;
    @Autowired private MockMvc mvc;
    @MockitoBean private QuantClient quant;
    @MockitoBean private AgentTokenService tokens;

    /** 固定使用工程隔离测试库，HTTP 查询直接执行真实 PostgreSQL 读取。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 七资产全部原生目标可查询；浏览不会排队、收费或调用模型。 */
    @Test
    void browserReadsAllNativeTargetsWithoutExecutingWork() throws Exception {
        Map<String, Object> before = sideEffectCounts();
        mvc.perform(get("/api/forecast/assets").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.length()").value(7));
        for (String asset : new String[] {"460300", "007169", "008701"}) {
            readContext(asset, "FUND_NAV_UP", 5, "REAL");
            for (int horizon : new int[] {20, 60}) {
                readContext(asset, "FUND_NAV_CHANGE", horizon, "REAL");
            }
        }
        for (String asset : new String[] {"510300", "510500", "159915"}) {
            readContext(asset, "ETF_FORWARD_OPEN", 1, "REAL");
            for (int horizon : new int[] {20, 60})
                readContext(asset, "ETF_CLOSE_RETURN", horizon, "REAL");
            for (int horizon : new int[] {1, 5}) {
                readContext(asset, "ETF_LEGACY_CLOSE", horizon, "REAL");
                readContext(asset, "ETF_LEGACY_CLOSE", horizon, "DEMO");
            }
        }
        for (int horizon : new int[] {63, 126}) {
            readContext("Au99.99", "GOLD_BENCHMARK_RETURN", horizon, "REAL");
        }
        assertThat(sideEffectCounts()).isEqualTo(before);
        verifyNoInteractions(quant);
    }

    /** 新聚合接口沿用浏览器登录；已有只读令牌不自动扩大权限。 */
    @Test
    void aggregateRoutesRequireBrowserSession() throws Exception {
        when(tokens.valid("test-only-token")).thenReturn(true);
        for (String path : new String[] {"assets", "records", "evaluations", "news", "runs"}) {
            mvc.perform(get("/api/forecast/" + path)).andExpect(status().isUnauthorized());
            mvc.perform(
                            get("/api/forecast/" + path)
                                    .with(user("admin"))
                                    .header("Authorization", "Bearer test-only-token"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(quant);
    }

    /** 不支持的资产目标组合、期限、模式以及伪造详情均不能退回其他资产。 */
    @Test
    void invalidContextIsRejectedInsteadOfReturningUnrelatedResults() throws Exception {
        for (String[] context :
                new String[][] {
                    {"460300", "ETF_FORWARD_OPEN", "1", "REAL"},
                    {"460300", "FUND_NAV_CHANGE", "1", "REAL"},
                    {"460300", "FUND_NAV_CHANGE", "20", "DEMO"},
                    {"Au99.99", "GOLD_BENCHMARK_RETURN", "20", "REAL"},
                    {"510300", "ETF_FORWARD_OPEN", "1", "DEMO"},
                    {"unknown", "ETF_LEGACY_CLOSE", "1", "REAL"}
                }) {
            mvc.perform(
                            get("/api/forecast/records")
                                    .with(user("admin"))
                                    .param("asset", context[0])
                                    .param("target", context[1])
                                    .param("horizon", context[2])
                                    .param("mode", context[3]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/forecast/records/fund/" + UUID.randomUUID())
                                .with(user("admin"))
                                .param("asset", "460300")
                                .param("target", "FUND_NAV_CHANGE")
                                .param("horizon", "20")
                                .param("mode", "REAL"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(quant);
    }

    /** 真实 SQL 返回原冻结依据；相同记录不能借用其他基金或期限读取。 */
    @Test
    void historyUsesFrozenEvidenceAndRejectsCrossAssetLinks() throws Exception {
        UUID id = UUID.randomUUID();
        String frozen =
                """
                {"nature":"REAL","capability":"RETURN_POINT",
                 "target_version":"fund-nav-change-20-60-v1",
                 "horizon_unit":"FUND_NAV_OBSERVATIONS","model_version_id":"http-fixture",
                 "input_cutoff_at":"2026-09-01T08:00:00Z","anchor_date":"2026-09-01",
                 "data_snapshot_id":"http-test-snapshot","points":[{"point_return":"0.02"}],
                 "news_snapshot":{"analysis_id":"frozen-old-news","summary":"原始冻结资料"},
                 "input_revisions":[{"revision_hash":"original-revision"}]}
                """;
        store.db.update(
                "insert into fund_forecast(id,fund_code,business_key,horizon,generated_at,details)"
                        + " values(?,'460300',?,20,?,?::jsonb)",
                id,
                "forecast-http-" + id,
                Timestamp.from(Instant.parse("2026-09-01T08:00:01Z")),
                frozen);
        String stored =
                store.db.queryForObject(
                        "select details::text from fund_forecast where id=?", String.class, id);
        mvc.perform(
                        get("/api/forecast/records/fund/" + id)
                                .with(user("admin"))
                                .param("asset", "460300")
                                .param("target", "FUND_NAV_CHANGE")
                                .param("horizon", "20")
                                .param("mode", "REAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.output.point_return").value("0.02"))
                .andExpect(
                        jsonPath("$.evidence.record.details.news_snapshot.analysis_id")
                                .value("frozen-old-news"))
                .andExpect(jsonPath("$.evaluation_eligible").value(false));
        for (String[] context : new String[][] {{"007169", "20"}, {"460300", "60"}}) {
            mvc.perform(
                            get("/api/forecast/records/fund/" + id)
                                    .with(user("admin"))
                                    .param("asset", context[0])
                                    .param("target", "FUND_NAV_CHANGE")
                                    .param("horizon", context[1])
                                    .param("mode", "REAL"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(
                        store.db.queryForObject(
                                "select details::text from fund_forecast where id=?",
                                String.class,
                                id))
                .isEqualTo(stored);
        verifyNoInteractions(quant);
    }

    /** 黄金区间是价格而非收益率；列表裁剪输入后仍保留同一评价身份。 */
    @Test
    void goldPriceIntervalAndEvidenceIdentitySurviveListProjection() throws Exception {
        UUID id = UUID.randomUUID();
        String model = "forecast-http-gold-" + id;
        store.db.update(
                "insert into gold_model(model_id,horizon,details) values(?,63,'{}')", model);
        store.db.update(
                "insert into gold_prediction(id,business_key,horizon,model_id,reference_date,"
                        + "generated_at,details) values(?,?,63,?,'2026-01-05',"
                        + "'2026-01-05T09:00:01Z',?::jsonb)",
                id,
                model,
                model,
                """
                {"label_version":"SGE_CLOSE_RETURN_63_126_V1",
                 "data_cutoff":"2026-01-05T09:00:00Z","median_return":"0.1",
                 "probability_up":"0.6","interval_lower":"850","interval_upper":"950",
                 "input_snapshot":{"observations_sha256":"frozen-gold-input"}}
                """);
        store.db.update(
                "insert into gold_actual(prediction_id,details) values(?,?::jsonb)",
                id,
                """
                {"status":"MATURED","actual_target_date":"2026-04-07",
                 "actual_return":"0.08","target_price":"900"}
                """);
        String query = "?asset=Au99.99&target=GOLD_BENCHMARK_RETURN&horizon=63&mode=REAL";
        String detail =
                mvc.perform(get("/api/forecast/records/gold/" + id + query).with(user("admin")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.output.interval_unit").value("CNY_PER_GRAM"))
                        .andExpect(jsonPath("$.output.interval_lower").value("850"))
                        .andExpect(jsonPath("$.evaluation_eligible").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String listed =
                mvc.perform(get("/api/forecast/records" + query).with(user("admin")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var records = store.decode(listed).path("records");
        var found =
                java.util.stream.StreamSupport.stream(records.spliterator(), false)
                        .filter(row -> id.toString().equals(row.path("id").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(found.path("sample_key")).isEqualTo(store.decode(detail).path("sample_key"));
        assertThat(found.path("evaluation_eligible").asBoolean()).isTrue();
        verifyNoInteractions(quant);
    }

    /** 旧 ETF 详情按原引用读取行情快照与新闻输入，不能只展示最新分析文本。 */
    @Test
    void legacyDetailsResolveOriginalMarketAndNewsReferences() throws Exception {
        UUID id = UUID.randomUUID();
        UUID input = UUID.randomUUID();
        UUID analysis = UUID.randomUUID();
        Timestamp cutoff = Timestamp.from(Instant.parse("2026-09-01T08:00:00Z"));
        store.db.update(
                "insert into prediction_input(id,input_cutoff,snapshot) values(?,?,?::jsonb)",
                input,
                cutoff,
                "{\"test_source\":\"original-market\"}");
        store.db.update(
                "insert into llm_analysis(id,business_key,input_cutoff,status,nature,"
                        + "prompt_version,model_version,source_ids,input_snapshot,result)"
                        + " values(?,?,?,'SUCCEEDED','REAL','test','test',?::jsonb,?::jsonb,'{}')",
                analysis,
                "forecast-http-" + analysis,
                cutoff,
                "[\"original-source\"]",
                "{\"test_source\":\"original-news\"}");
        store.db.update(
                "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                        + "input_cutoff,model_version,nature,probability,direction,details)"
                        + " values(?,?,'510300','lightgbm',1,'2026-09-01',?,'test','REAL',"
                        + "0.6,'UP',?::jsonb)",
                id,
                "forecast-http-" + id,
                cutoff,
                store.encode(Map.of("input_snapshot_id", input, "analysis_id", analysis)));
        mvc.perform(
                        get("/api/forecast/records/legacy/" + id)
                                .with(user("admin"))
                                .param("asset", "510300")
                                .param("target", "ETF_LEGACY_CLOSE")
                                .param("horizon", "1")
                                .param("mode", "REAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.frozen_input.test_source").value("original-market"))
                .andExpect(
                        jsonPath("$.evidence.frozen_analysis.input_snapshot.test_source")
                                .value("original-news"))
                .andExpect(
                        jsonPath("$.evidence.frozen_analysis.source_ids[0]")
                                .value("original-source"));
        verifyNoInteractions(quant);
    }

    /** 同一上下文读取列表与评价，校验真实 SQL 的列名、转换和空数据行为。 */
    private void readContext(String asset, String target, int horizon, String mode)
            throws Exception {
        for (String path : new String[] {"records", "evaluations", "news"}) {
            mvc.perform(
                            get("/api/forecast/" + path)
                                    .with(user("admin"))
                                    .param("asset", asset)
                                    .param("target", target)
                                    .param("horizon", Integer.toString(horizon))
                                    .param("mode", mode))
                    .andExpect(status().isOk());
        }
    }

    /** 记录可能由浏览操作误触发的两类副作用，使用真实库计数核对。 */
    private Map<String, Object> sideEffectCounts() {
        return store.db.queryForMap(
                "select (select count(*) from task_execution) as tasks,"
                        + "(select count(*) from api_call_cost) as costs,"
                        + "(select count(*) from forecast_run) as runs,"
                        + "(select count(*) from forecast_result) as forecasts");
    }
}
