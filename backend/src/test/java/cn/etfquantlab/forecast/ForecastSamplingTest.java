package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "etf.worker-delay=3600000")
@Transactional
class ForecastSamplingTest {
    @Autowired private Store store;
    @Autowired private ForecastService service;
    @MockitoBean private QuantClient quant;
    private UUID run;
    private String protocol;

    /** 专属测试库与回滚事务保证千条规模夹具不会进入业务历史或真实评分。 */
    @BeforeEach
    void isolatedDatabase() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
        run = UUID.randomUUID();
        protocol = "ISOLATED_SAMPLING_" + run;
        store.db.update(
                "insert into forecast_run(id,business_key,status,request) values(?,?,'COMPLETED','{}')",
                run,
                "sampling-" + run);
    }

    /** 最新两千条均未成熟时，已到期的共同机会仍需评分，新规则记录只决定当前身份。 */
    @Test
    void pendingRefreshesCannotHideMatureSharedHistory() {
        forecasts(1, "FUND_LIGHTGBM", "old-model", "old-policy", "2022-01-03T09:00:01Z", true);
        forecasts(1, "DEEPSEEK", "ds-model", "ds-policy", "2022-01-03T09:00:01Z", true);
        forecasts(2100, "FUND_LIGHTGBM", "new-model", "new-policy", "2026-10-01T09:00:01Z", false);
        JsonNode evaluation =
                store.json.valueToTree(
                        service.evaluations("008701", "FUND_NAV_CHANGE", 20, "REAL"));
        JsonNode group = group(evaluation);
        assertThat(individual(group, "old-model").path("evaluated_samples").asInt()).isEqualTo(1);
        assertThat(individual(group, "new-model").path("evaluated_samples").asInt()).isZero();
        assertThat(individual(group, "ds-model").path("evaluated_samples").asInt()).isEqualTo(1);
        assertThat(group.path("comparisons").path(0).path("matched_samples").asInt()).isEqualTo(1);
        assertThat(group.path("recommendation").path("nonoverlapping_samples").asInt()).isZero();
        assertThat(evaluation.path("mature_records_sampled").asInt()).isLessThan(20);
        assertThat(evaluation.path("current_policy_references").asInt()).isGreaterThanOrEqualTo(1);
        verifyNoInteractions(quant);
    }

    /** DS复用早期截止时，后来的量化轮询不能各取最新而破坏原本可比的共同样本。 */
    @Test
    void sharedCutoffWinsOverThousandsOfNewerSingleSchemeRefreshes() {
        forecasts(1, "FUND_LIGHTGBM", "quant-model", "quant-policy", "2022-01-03T09:00:01Z", true);
        forecasts(1, "DEEPSEEK", "ds-model", "ds-policy", "2022-01-03T09:00:01Z", true);
        forecasts(
                2100, "FUND_LIGHTGBM", "quant-model", "quant-policy", "2022-01-04T09:00:01Z", true);
        JsonNode evaluation =
                store.json.valueToTree(
                        service.evaluations("008701", "FUND_NAV_CHANGE", 20, "REAL"));
        JsonNode group = group(evaluation);
        assertThat(individual(group, "quant-model").path("evaluated_samples").asInt()).isEqualTo(1);
        assertThat(individual(group, "ds-model").path("evaluated_samples").asInt()).isEqualTo(1);
        assertThat(group.path("comparisons").path(0).path("matched_samples").asInt()).isEqualTo(1);
        assertThat(evaluation.path("mature_records_sampled").asInt()).isLessThan(20);
        assertThat(evaluation.path("truncated").asBoolean()).isFalse();
        verifyNoInteractions(quant);
    }

    /** H60日更需要约2400个交易日起点；限额必须优先留下独立机会，不能只取最近1000天。 */
    @Test
    void longHorizonPreservesFortyCommonNonoverlappingOpportunitiesWithinLimit() {
        dailyForecasts("FUND_LIGHTGBM", "0.1", 1);
        dailyForecasts("DEEPSEEK", "0", 1);
        JsonNode evaluation =
                store.json.valueToTree(
                        service.evaluations("008701", "FUND_NAV_CHANGE", 60, "REAL"));
        JsonNode recommendation = group(evaluation).path("recommendation");
        assertThat(evaluation.path("mature_records_sampled").asInt()).isEqualTo(2000);
        assertThat(evaluation.path("truncated").asBoolean()).isTrue();
        assertThat(evaluation.path("nonoverlapping_window_limit_per_protocol").asInt())
                .isEqualTo(100);
        assertThat(recommendation.path("nonoverlapping_samples").asInt())
                .isGreaterThanOrEqualTo(40);
        assertThat(recommendation.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(recommendation.path("selection_samples").asInt()).isEqualTo(30);
        assertThat(recommendation.path("validation_samples").asInt()).isEqualTo(10);
        assertThat(recommendation.path("model_versions_count").asInt()).isGreaterThan(1);
        verifyNoInteractions(quant);
    }

    /** 资讯不足导致DS隔日弃权时，优先递归共同窗口，不能恰好只选出量化单独执行的日子。 */
    @Test
    void sparseIndependentForecastsKeepSharedLongHorizonOpportunities() {
        dailyForecasts("FUND_LIGHTGBM", "0.1", 1);
        dailyForecasts("DEEPSEEK", "0", 2);
        JsonNode evaluation =
                store.json.valueToTree(
                        service.evaluations("008701", "FUND_NAV_CHANGE", 60, "REAL"));
        JsonNode recommendation = group(evaluation).path("recommendation");
        assertThat(evaluation.path("mature_records_sampled").asInt()).isEqualTo(2000);
        assertThat(recommendation.path("nonoverlapping_samples").asInt())
                .isGreaterThanOrEqualTo(40);
        assertThat(recommendation.path("status").asText()).isEqualTo("AVAILABLE");
        verifyNoInteractions(quant);
    }

    /** 两方案使用相同工作日目标和冻结截止，每20个观察点更新制品而训练规则保持不变。 */
    private void dailyForecasts(String scheme, String pointReturn, int interval) {
        UUID item = UUID.randomUUID();
        store.db.update(
                "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,status,input_snapshot) "
                        + "values(?,?,'008701','FUND_NAV_CHANGE',60,?,'SUCCEEDED','{}')",
                item,
                run,
                scheme);
        ObjectNode details = store.json.createObjectNode();
        details.put("asset", "008701");
        details.put("target", "FUND_NAV_CHANGE");
        details.put("horizon", 60);
        details.put("mode", "REAL");
        details.put("source", "unified");
        details.put("role", "PREDICTOR");
        details.put("status", "FROZEN");
        details.put("scheme", scheme);
        details.put("selection_policy", scheme + "-rolling-policy");
        details.put("target_version", protocol);
        details.put("output_type", "RETURN_POINT");
        details.putObject("output").put("point_return", pointReturn);
        store.db.update(
                "with dates as (select d::date as day,row_number() over(order by d) as position "
                        + "from generate_series('2010-01-04'::date,'2025-12-31'::date,interval '1 day') d "
                        + "where extract(isodow from d)<6),"
                        + "fixtures as (select gen_random_uuid() as id,a.day as anchor,t.day as target,a.position,"
                        + "(a.day+time '13:00:01') at time zone 'UTC' as generated "
                        + "from dates a join dates t on t.position=a.position+60 where a.position<=2600 and (a.position-1)%?=0) "
                        + "insert into forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,details) "
                        + "select id,?,?,'008701','FUND_NAV_CHANGE',60,?,generated,?::jsonb||"
                        + "jsonb_build_object('id',id,'generated_at',generated,'data_cutoff',generated-interval '1 second',"
                        + "'anchor_date',anchor,'target_date',target,'model_version',?||'-'||((position-1)/20)::text) "
                        + "from fixtures",
                interval, item, run, scheme, store.encode(details), scheme);
        store.db.update(
                "insert into forecast_result_actual(forecast_id,details) select id,"
                        + "jsonb_build_object('status','EVALUATED','target_version',details->>'target_version',"
                        + "'actual_target_date',details->>'target_date','actual_return','0.1',"
                        + "'actual_available_at',((details->>'target_date')::date+time '12:00:00') at time zone 'UTC') "
                        + "from forecast_result where item_id=?",
                item);
    }

    /** 数据库批量夹具包含明确身份和真实可得时刻，测试不会调用供应商或量化工件。 */
    private void forecasts(
            int count, String scheme, String model, String policy, String start, boolean mature) {
        UUID item = UUID.randomUUID();
        store.db.update(
                "insert into forecast_run_item(id,run_id,asset,target,horizon,scheme,status,input_snapshot) "
                        + "values(?,?,'008701','FUND_NAV_CHANGE',20,?,'SUCCEEDED','{}')",
                item,
                run,
                scheme);
        ObjectNode details = store.json.createObjectNode();
        details.put("asset", "008701");
        details.put("target", "FUND_NAV_CHANGE");
        details.put("horizon", 20);
        details.put("mode", "REAL");
        details.put("source", "unified");
        details.put("role", "PREDICTOR");
        details.put("status", "FROZEN");
        details.put("scheme", scheme);
        details.put("model_version", model);
        details.put("selection_policy", policy);
        details.put("target_version", protocol);
        details.put("anchor_date", "2022-01-03");
        details.put("target_date", mature ? "2022-02-01" : "2099-02-01");
        details.put("output_type", "RETURN_POINT");
        details.putObject("output").put("point_return", "0.1");
        store.db.update(
                "with fixtures as(select gen_random_uuid() id,?::timestamptz+(n*interval '1 second') generated "
                        + "from generate_series(0,?-1) n) "
                        + "insert into forecast_result(id,item_id,run_id,asset,target,horizon,scheme,generated_at,details) "
                        + "select id,?,?,'008701','FUND_NAV_CHANGE',20,?,generated,?::jsonb||"
                        + "jsonb_build_object('id',id,'generated_at',generated,'data_cutoff',generated-interval '1 second') from fixtures",
                Timestamp.from(Instant.parse(start)),
                count,
                item,
                run,
                scheme,
                store.encode(details));
        if (mature) {
            ObjectNode actual = store.json.createObjectNode();
            actual.put("status", "EVALUATED");
            actual.put("target_version", protocol);
            actual.put("actual_target_date", "2022-02-01");
            actual.put("actual_return", "0.1");
            actual.put("actual_available_at", "2022-02-01T12:00:00Z");
            store.db.update(
                    "insert into forecast_result_actual(forecast_id,details) select id,?::jsonb from forecast_result where item_id=?",
                    store.encode(actual),
                    item);
        }
    }

    /** 只选择本测试协议组，隔离库已有其他合法历史不会影响断言。 */
    private JsonNode group(JsonNode evaluation) {
        return StreamSupport.stream(evaluation.path("groups").spliterator(), false)
                .filter(row -> protocol.equals(row.path("target_version").asText()))
                .findFirst()
                .orElseThrow();
    }

    /** 制品成绩保持独立，测试不把当前规则引用误当作成熟样本。 */
    private JsonNode individual(JsonNode group, String model) {
        return StreamSupport.stream(group.path("individuals").spliterator(), false)
                .filter(row -> model.equals(row.path("model_version").asText()))
                .findFirst()
                .orElseThrow();
    }
}
