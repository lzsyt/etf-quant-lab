package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 在隔离数据库验证同期限双算法选择、旧工件兼容及统一冻结，不调用任何外部模型。 */
@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ForecastNativeAlgorithmsTest {
    @Autowired private Store store;
    @Autowired private FundResearchService funds;
    @Autowired private GoldService gold;
    @Autowired private ForecastInputs inputs;
    @MockitoBean private QuantClient quant;
    private final Instant cutoff = Instant.now();
    private final LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);

    /** 明确检查测试库，所有追加记录随事务回滚，禁止使用实际业务库验收。 */
    @BeforeEach
    void isolatedOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** XGBoost更晚训练也不能挤掉LightGBM；未来工件不能进入当前冻结。 */
    @Test
    void fundsChooseEachAlgorithmAtCutoffAndPreserveLegacyModels() {
        fundObservation();
        fundModel("test-fund-old", null, 20, cutoff.minusSeconds(30));
        fundModel("test-fund-xgb", "xgboost", 20, cutoff.minusSeconds(20));
        fundModel("test-fund-future", "xgboost", 20, cutoff.plusSeconds(30));
        assertThat(funds.models("460300")).hasSize(2);
        JsonNode frozen = inputs.freeze("460300", "FUND_NAV_CHANGE", 20, cutoff);
        assertThat(model(frozen, "FUND_LIGHTGBM").path("id").asText()).isEqualTo("test-fund-old");
        assertThat(model(frozen, "FUND_XGBOOST").path("id").asText()).isEqualTo("test-fund-xgb");
        assertThat(frozen.path("news_request").has("quant_requests")).isFalse();
        assertThat(store.json.valueToTree(funds.forecasts("460300", 20)).path("models")).hasSize(2);
        verifyNoInteractions(quant);
    }

    /** 新算法缺工件时保留旧算法输入，推理明确未就绪而不复用另一算法结果。 */
    @Test
    void missingFundXgboostDoesNotBorrowLightgbmArtifact() {
        fundObservation();
        fundModel("test-fund-legacy", null, 20, cutoff.minusSeconds(20));
        JsonNode frozen = inputs.freeze("460300", "FUND_NAV_CHANGE", 20, cutoff);
        assertThat(frozen.path("quant_requests").has("FUND_LIGHTGBM")).isTrue();
        assertThat(frozen.path("quant_requests").has("FUND_XGBOOST")).isFalse();
        assertThatThrownBy(() -> inputs.infer(frozen, "FUND_XGBOOST"))
                .hasMessage("MODEL_NOT_READY");
        verifyNoInteractions(quant);
    }

    /** h5工件有独立协议；更晚的错误协议或未来工件不能遮蔽截止前合法模型。 */
    @Test
    void shortFundSelectsOnlyProbabilityArtifactsAtCutoff() {
        fundObservation();
        fundModel("test-short-lgb", "lightgbm", 5, cutoff.minusSeconds(30));
        fundModel("test-short-xgb", "xgboost", 5, cutoff.minusSeconds(20));
        fundModel("test-short-future", "xgboost", 5, cutoff.plusSeconds(30));
        JsonNode frozen = inputs.freeze("460300", "FUND_NAV_UP", 5, cutoff);
        assertThat(frozen.path("context").path("target_version").asText())
                .isEqualTo("fund-nav-up-5-v1");
        assertThat(model(frozen, "FUND_LIGHTGBM").path("id").asText()).isEqualTo("test-short-lgb");
        assertThat(model(frozen, "FUND_XGBOOST").path("id").asText()).isEqualTo("test-short-xgb");
        verifyNoInteractions(quant);
    }

    /** 本月旧双期限不能冒充四个工件已齐备，补齐后两个算法分别冻结。 */
    @Test
    void goldNeedsBothAlgorithmsAndFreezesEachNativeArtifact() {
        goldObservation();
        for (int horizon : List.of(63, 126)) {
            goldModel("test-gold-lgb-" + horizon, null, horizon);
        }
        assertThat(gold.currentMonthModelsReady(cutoff)).isFalse();
        for (int horizon : List.of(63, 126)) {
            goldModel("test-gold-xgb-" + horizon, "xgboost", horizon);
        }
        assertThat(gold.models()).hasSize(4);
        assertThat(gold.currentMonthModelsReady(cutoff)).isTrue();
        JsonNode frozen = inputs.freeze("Au99.99", "GOLD_BENCHMARK_RETURN", 63, cutoff);
        assertThat(model(frozen, "GOLD_LIGHTGBM").path("model_id").asText())
                .isEqualTo("test-gold-lgb-63");
        assertThat(model(frozen, "GOLD_XGBOOST").path("model_id").asText())
                .isEqualTo("test-gold-xgb-63");
        verifyNoInteractions(quant);
    }

    /** 取当前冻结的模型引用，不能使用全局最近模型代替各个体输入。 */
    private JsonNode model(JsonNode snapshot, String scheme) {
        return snapshot.path("quant_requests").path(scheme).path("payload").path("models").path(0);
    }

    /** 基金测试净值只服务于输入选择，不作为真实行情或精度证据。 */
    private void fundObservation() {
        ObjectNode row =
                store.json
                        .createObjectNode()
                        .put("fund_code", "460300")
                        .put("nav_date", day.toString())
                        .put("unit_nav", "1.0")
                        .put("first_observed_at", cutoff.minusSeconds(60).toString())
                        .put("fetched_at", cutoff.minusSeconds(60).toString())
                        .put("provider", "TEST_ONLY")
                        .put("nature", "REAL");
        store.db.update(
                "insert into fund_nav_observation(id,fund_code,nav_date,unit_nav,first_observed_at,"
                        + "fetched_at,provider,revision,revision_hash,quality_status,nature,details)"
                        + " values(?,?,?,1,?,?,'TEST_ONLY',1,?,'VALID','REAL',?::jsonb)",
                UUID.randomUUID(),
                "460300",
                Date.valueOf(day),
                Timestamp.from(cutoff.minusSeconds(60)),
                Timestamp.from(cutoff.minusSeconds(60)),
                UUID.randomUUID().toString(),
                store.encode(row));
    }

    /** 历史LightGBM夹具故意不写algorithm，验证升级后仍然可以读取。 */
    private void fundModel(String id, String algorithm, int horizon, Instant created) {
        ObjectNode model =
                store.json
                        .createObjectNode()
                        .put("id", id)
                        .put("horizon", horizon)
                        .put("artifact_hash", "a".repeat(64));
        if (algorithm != null) model.put("algorithm", algorithm);
        if (horizon == 5)
            model.put("target_version", "fund-nav-up-5-v1").put("capability", "PROBABILITY");
        store.db.update(
                "insert into fund_model(id,fund_code,horizon,created_at,details) values(?,'460300',?,?,?::jsonb)",
                id,
                horizon,
                Timestamp.from(created),
                store.encode(model));
    }

    /** 黄金测试观测满足真实输入门禁，来源显式标记为测试专用。 */
    private void goldObservation() {
        ObjectNode row =
                store.json
                        .createObjectNode()
                        .put("series_id", GoldService.BENCHMARK)
                        .put("observation_date", day.toString())
                        .put("value", "900")
                        .put("fetched_at", cutoff.minusSeconds(60).toString())
                        .put("source", "TEST_ONLY");
        store.db.update(
                "insert into gold_observation(id,series_id,observation_date,value,unit,source,source_url,"
                        + "availability_precision,fetched_at,revision,quality,details)"
                        + " values(?,?,?,900,'CNY/g','TEST_ONLY','https://example.invalid','UNKNOWN',"
                        + "?,?,'REAL_OFFICIAL_CURRENT_VINTAGE',?::jsonb)",
                UUID.randomUUID(),
                GoldService.BENCHMARK,
                Date.valueOf(day),
                Timestamp.from(cutoff.minusSeconds(60)),
                UUID.randomUUID().toString(),
                store.encode(row));
    }

    /** 两算法在同月分别登记，保留各自模型ID和期限。 */
    private void goldModel(String id, String algorithm, int horizon) {
        ObjectNode model =
                store.json.createObjectNode().put("model_id", id).put("horizon", horizon);
        if (algorithm != null) model.put("algorithm", algorithm);
        model.putObject("metadata").put("fit_at", cutoff.toString());
        store.db.update(
                "insert into gold_model(model_id,horizon,registered_at,details) values(?,?,?,?::jsonb)",
                id,
                horizon,
                Timestamp.from(cutoff.minusSeconds(10)),
                store.encode(model));
    }
}
