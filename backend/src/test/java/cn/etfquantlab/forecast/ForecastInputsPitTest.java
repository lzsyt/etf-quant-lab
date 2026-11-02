package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ForecastInputsPitTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-28T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    @Autowired private Store store;
    @Autowired private ForecastInputs inputs;
    @MockitoBean private QuantClient quant;
    @MockitoBean private FundResearchService funds;
    @MockitoBean private GoldService gold;
    @MockitoBean private PredictionService legacy;
    @MockitoBean private AnalysisService news;

    /** 写夹具前强制确认专属测试库，所有修订和日历在测试事务结束后回滚。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        when(funds.enabled()).thenReturn(true);
        when(gold.enabled()).thenReturn(true);
        when(news.candidates(CUTOFF)).thenReturn(List.of());
    }

    /** 后来基金修订不能把原本已知的净值日从固定截止输入中删除。 */
    @Test
    void futureNavRevisionDoesNotHideAvailableValue() {
        insertFund("1.20", 1, CUTOFF.minusSeconds(60));
        insertFund("1.30", 2, CUTOFF.plusSeconds(60));
        JsonNode snapshot = inputs.freeze("008701", "FUND_NAV_CHANGE", 20, CUTOFF);
        assertThat(snapshot.path("context").path("anchor_value").asText()).isEqualTo("1.20");
        assertThat(snapshot.path("news_request").path("prices")).hasSize(1);
        assertThat(snapshot.path("news_request").path("prices").path(0).path("value").asText())
                .isEqualTo("1.20");
        verifyNoInteractions(quant);
    }

    /** 官方黄金可缺历史首次公开时刻；按实际抓取准入并保留截止前修订。 */
    @Test
    void goldUnknownHistoricalAvailabilityUsesKnownFetchAndOldRevision() {
        insertGold("600.00", CUTOFF.minusSeconds(60));
        insertGold("610.00", CUTOFF.plusSeconds(60));
        JsonNode snapshot = inputs.freeze("Au99.99", "GOLD_BENCHMARK_RETURN", 63, CUTOFF);
        JsonNode price = snapshot.path("news_request").path("prices").path(0);
        assertThat(snapshot.path("context").path("anchor_value").asText()).isEqualTo("600.00");
        assertThat(price.path("available_at").asText())
                .isEqualTo(CUTOFF.minusSeconds(60).toString());
        assertThat(price.path("value").asText()).isEqualTo("600.00");
        verifyNoInteractions(quant);
    }

    /** 同日重新抓取的ETF修订不能遮蔽旧收盘，锚点与大模型价格必须保持相同。 */
    @Test
    void futureEtfRevisionDoesNotRemoveSignalDay() {
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) values(?,'ISOLATED_TEST',true) "
                        + "on conflict(trade_date) do update set is_open=true",
                Date.valueOf(DAY));
        when(legacy.latestExpected(CUTOFF)).thenReturn(DAY);
        when(legacy.nextDates(DAY, 1)).thenReturn(List.of(DAY.plusDays(1).toString()));
        insertBar("100", CUTOFF.minusSeconds(60));
        insertBar("105", CUTOFF.plusSeconds(60));
        JsonNode snapshot = inputs.freeze("510300", "ETF_LEGACY_CLOSE", 1, CUTOFF);
        assertThat(snapshot.path("context").path("anchor_value").asText()).startsWith("100");
        JsonNode prices = snapshot.path("news_request").path("prices");
        assertThat(prices.path(prices.size() - 1).path("date").asText()).isEqualTo(DAY.toString());
        assertThat(prices.path(prices.size() - 1).path("value").asText()).startsWith("100");
        verifyNoInteractions(quant);
    }

    /** 新中期只冻结正确工件和已知日历；未来模型及后来行情修订不得进入当前输入。 */
    @Test
    void closeReturnFreezesLatestEligibleArtifactWithoutInventingTargetDate() {
        store.db.update(
                "insert into trading_calendar(trade_date,source,is_open) values(?,'ISOLATED_TEST',true) on "
                        + "conflict(trade_date) do update set is_open=true",
                Date.valueOf(DAY));
        store.db.update(
                "delete from trading_calendar where trade_date>?", Date.valueOf(DAY.plusDays(10)));
        when(legacy.latestExpected(CUTOFF)).thenReturn(DAY);
        when(legacy.nextDates(DAY, 1)).thenReturn(List.of(DAY.plusDays(1).toString()));
        insertBar("100", CUTOFF.minusSeconds(60));
        insertBar("105", CUTOFF.plusSeconds(60));
        closeModel(
                "close-valid",
                "lightgbm",
                CloseModels.LABEL,
                CUTOFF.minusSeconds(30),
                CUTOFF.minusSeconds(30));
        closeModel(
                "close-future-registry",
                "lightgbm",
                CloseModels.LABEL,
                CUTOFF.plusSeconds(1),
                CUTOFF.minusSeconds(10));
        closeModel(
                "close-future-artifact",
                "lightgbm",
                CloseModels.LABEL,
                CUTOFF.minusSeconds(5),
                CUTOFF.plusSeconds(1));
        closeModel(
                "close-wrong-label",
                "lightgbm",
                "OLD_CLASSIFIER",
                CUTOFF.minusSeconds(1),
                CUTOFF.minusSeconds(1));
        JsonNode frozen = inputs.freeze("510300", "ETF_CLOSE_RETURN", 60, CUTOFF);
        assertThat(frozen.path("context").path("target_date").isNull()).isTrue();
        assertThat(frozen.path("context").path("calendar_status").asText())
                .isEqualTo("UNCONFIRMED");
        assertThat(frozen.path("context").path("reason_codes").toString())
                .contains("FUTURE_CALENDAR_UNAVAILABLE");
        JsonNode request = frozen.path("quant_requests").path("CLOSE_LIGHTGBM");
        assertThat(request.path("path").asText()).isEqualTo("/close/models/predict");
        assertThat(request.path("payload").path("model_id").asText()).isEqualTo("close-valid");
        assertThat(request.path("payload").path("cutoff_at").asText()).isEqualTo(CUTOFF.toString());
        assertThat(frozen.path("context").path("anchor_value").asText()).startsWith("100");
        assertThat(frozen.path("quant_requests").has("CLOSE_XGBOOST")).isFalse();
        assertThat(frozen.path("news_request").has("quant_requests")).isFalse();
        verifyNoInteractions(quant);
    }

    /** 工件可得时刻与登记时刻分开构造，回归验证两道时间约束。 */
    private void closeModel(
            String id, String algorithm, String label, Instant registered, Instant available) {
        ObjectNode metadata = store.json.createObjectNode();
        metadata.put("id", id)
                .put("symbol", "510300")
                .put("algorithm", algorithm)
                .put("horizon", 60)
                .put("feature_version", "ohlcv-v2")
                .put("label_version", label)
                .put("protocol_version", CloseModels.PROTOCOL)
                .put("prediction_model_role", CloseModels.ROLE)
                .put("nature", "REAL")
                .put("price_basis", "RAW")
                .put("data_cutoff", DAY.toString())
                .put("available_at", available.toString());
        store.db.update(
                "insert into "
                        + "model_version(id,symbol,algorithm,horizon,feature_version,trained_at,training_range,"
                        + "hyperparameters,metrics,metadata) values(?,'510300',?,60,'ohlcv-v2',?,'{}','{}','{}',"
                        + "?::jsonb)",
                id,
                algorithm,
                Timestamp.from(registered),
                store.encode(metadata));
    }

    /** 夹具保留数据库和JSON正文的同一身份，不借助真实采集或模型工件。 */
    private void insertFund(String value, int revision, Instant fetched) {
        ObjectNode row = store.json.createObjectNode();
        row.put("fund_code", "008701");
        row.put("nav_date", DAY.toString());
        row.put("unit_nav", value);
        row.put("first_observed_at", fetched.toString());
        row.put("fetched_at", fetched.toString());
        row.put("provider", "ISOLATED_TEST");
        store.db.update(
                "insert into fund_nav_observation(id,fund_code,nav_date,unit_nav,first_observed_at,"
                        + "fetched_at,provider,revision,revision_hash,quality_status,nature,details) "
                        + "values(?,'008701',?,?::numeric,?,?,'ISOLATED_TEST',?,?,'VALID','REAL',?::jsonb)",
                UUID.randomUUID(),
                Date.valueOf(DAY),
                value,
                Timestamp.from(fetched),
                Timestamp.from(fetched),
                revision,
                String.valueOf(revision).repeat(64),
                store.encode(row));
    }

    /** 空available_at遵守现有官方当前历史版本合同，而非伪造过去的公开时刻。 */
    private void insertGold(String value, Instant fetched) {
        ObjectNode row = store.json.createObjectNode();
        row.put("series_id", GoldService.BENCHMARK);
        row.put("observation_date", DAY.toString());
        row.put("value", value);
        row.putNull("available_at");
        row.put("fetched_at", fetched.toString());
        row.put("source", "ISOLATED_TEST");
        store.db.update(
                "insert into gold_observation(id,series_id,observation_date,value,unit,source,"
                        + "source_url,available_at,availability_precision,fetched_at,revision,quality,details) "
                        + "values(?,?,?,?::numeric,'CNY/g','ISOLATED_TEST','https://www.sge.com.cn',"
                        + "null,'UNKNOWN_HISTORICAL',?,?,'REAL_OFFICIAL_CURRENT_VINTAGE',?::jsonb)",
                UUID.randomUUID(),
                GoldService.BENCHMARK,
                Date.valueOf(DAY),
                value,
                Timestamp.from(fetched),
                UUID.randomUUID().toString(),
                store.encode(row));
    }

    /** 两条真实性质的测试修订只有可用时刻和数值不同，回归覆盖数据库去重顺序。 */
    private void insertBar(String value, Instant fetched) {
        store.db.update(
                "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                        + "price_basis,source,nature,fetched_at,quality_status,quality_flags,raw_values) "
                        + "values(?,'510300',?,?::numeric,?::numeric,?::numeric,?::numeric,100,100,"
                        + "'RAW','ISOLATED_TEST','REAL',?,'VALID','[]'::jsonb,'{}'::jsonb)",
                UUID.randomUUID(),
                Date.valueOf(DAY),
                value,
                value,
                value,
                value,
                Timestamp.from(fetched));
    }
}
