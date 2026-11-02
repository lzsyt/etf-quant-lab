package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;

import cn.etfquantlab.fund.FundNewsIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** 不连接数据库或模型，验证读取降级与冻结数值不变。 */
class FundNewsIdentityTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 新身份匹配可以展示，代码正确但类别或专属材料错误必须拒绝。 */
    @Test
    void currentIdentityAndMaterialMustMatch() {
        ObjectNode valid = current();
        assertThat(FundNewsIdentity.project(valid, "007169")).isSameAs(valid);
        ObjectNode wrongClass = valid.deepCopy();
        ((ObjectNode) wrongClass.path("fund_identity")).put("asset_class", "GOLD");
        assertThat(FundNewsIdentity.project(wrongClass, "007169").path("analysis_status").asText())
                .isEqualTo("INVALID");
        valid.putArray("documents").addObject().put("subject_fund_code", "008701");
        assertThat(FundNewsIdentity.project(valid, "007169").path("error_code").asText())
                .isEqualTo("FUND_IDENTITY_MISMATCH");
        assertThat(valid.path("analysis_status").asText()).isEqualTo("VALID");
    }

    /** 旧华夏公告的采集器归属已经明确，错配债基应标无效而非笼统待核验。 */
    @Test
    void legacyGoldDisclosureBoundToBondIsInvalid() {
        ObjectNode old = current().put("schema_version", "fund-news-evidence-v1");
        old.remove("fund_identity");
        old.putArray("documents").addObject().put("source_name", "CHINAAMC_008701");
        var result = FundNewsIdentity.project(old, "007169");
        assertThat(result.path("analysis_status").asText()).isEqualTo("INVALID");
        assertThat(result.path("error_code").asText()).isEqualTo("FUND_IDENTITY_MISMATCH");
    }

    /** 降级只影响新闻可用性；数值、日期和源对象逐字段保留。 */
    @Test
    void frozenForecastDoesNotChangeNumericPrediction() {
        ObjectNode record =
                json.createObjectNode()
                        .put("fund_code", "007169")
                        .put("point_return", "0.0123")
                        .put("anchor_date", "2026-10-29")
                        .put("news_usage", "DISPLAY_ONLY");
        record.set("news_snapshot", current().put("schema_version", "fund-news-evidence-v1"));
        var projected = FundNewsIdentity.forecast(record, "007169");
        assertThat(projected.path("news_snapshot").path("analysis_status").asText())
                .isEqualTo("UNVERIFIED");
        assertThat(projected.path("news_usage").asText()).isEqualTo("NOT_USED");
        assertThat(projected.path("point_return")).isEqualTo(record.path("point_return"));
        assertThat(projected.path("anchor_date")).isEqualTo(record.path("anchor_date"));
        assertThat(record.path("news_snapshot").path("analysis_status").asText())
                .isEqualTo("VALID");
    }

    /** 新版字段基于目标基金目录，不以模型自报名称推定类别。 */
    private ObjectNode current() {
        ObjectNode result =
                json.createObjectNode()
                        .put("fund_code", "007169")
                        .put("analysis_status", "VALID")
                        .put("schema_version", "fund-news-evidence-v2");
        result.putObject("fund_identity")
                .put("fund_code", "007169")
                .put("name", "易方达中债1–3年国开行债券指数A")
                .put("asset_class", "BOND")
                .put("investment_mechanism", "跟踪中债1–3年国开行债券指数");
        return result;
    }
}
