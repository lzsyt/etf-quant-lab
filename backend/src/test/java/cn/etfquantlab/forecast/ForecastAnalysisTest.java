package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 以真实冻结数据结构验证业务阅读投影，不把模型原始封装直接呈现给用户。 */
class ForecastAnalysisTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 统一工作台的旧基金解释同样受身份门禁约束，原始对象不改写。 */
    @Test
    void legacyFundExplanationCannotBypassIdentityGate() throws Exception {
        JsonNode raw =
                json.readTree(
                        """
                {"status":"SUCCEEDED","result":{"result":{"fund_code":"007169",
                  "status":"AVAILABLE","summary":"本基金跟踪Au99.99",
                  "mechanisms":[{"mechanism":"净值主要由金价决定"}]}}}
                """);
        JsonNode displayed = ForecastAnalysis.project(json, raw, "007169");
        assertThat(displayed.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(displayed.path("mechanisms")).isEmpty();
        assertThat(displayed.path("summary").asText()).doesNotContain("Au99.99");
        assertThat(raw.path("result").path("result").path("summary").asText()).contains("Au99.99");
    }

    /** 现有量化接口的真实特征字段进入可读依据，不强加不存在的新闻或因果解释。 */
    @Test
    void frozenQuantFeaturesAndTrainingImportanceRemainReadableFacts() throws Exception {
        JsonNode record =
                json.readTree(
                        """
                {"asset":"510300","scheme":"LGBM_BASE","role":"PREDICTOR",
                 "model_version":"model-v1","evidence":{"result":{"feature_values":{"return_20":0.03}},
                  "input":{"model":{"global_feature_importance":{"return_20":12}}}}}
                """);
        JsonNode result = ForecastAnalysis.frozen(json, record);
        String facts = result.path("facts").toString();
        assertThat(facts).contains("收益窗口_20：0.03", "收益窗口_20：12", "不等于本次预测贡献");
        assertThat(result.path("positive_factors")).isEmpty();
        assertThat(result.path("documents")).isEmpty();
    }

    /** 独立AI预测从自己的输入和回答中读取，反向因素、失效条件与来源同时保留。 */
    @Test
    void independentPredictionKeepsItsOwnEvidenceAndTimestamps() throws Exception {
        JsonNode record =
                json.readTree(
                        """
                {"asset":"460300","scheme":"DEEPSEEK","role":"PREDICTOR",
                 "generated_at":"2026-10-29T12:01:00Z","data_cutoff":"2026-10-29T12:00:00Z",
                 "evidence":{"input":{"news":[{"id":"raw-news","title":"原始标题"}]},
                   "catalog":[{"evidence_id":"E1","title":"实际引用标题","source_id":"raw-news","excerpt":"原文摘录"}],
                   "result":{"status":"SUCCEEDED","result":{"status":"AVAILABLE",
                    "summary":"独立预测结论","counter_factors":["反向信息"],
                    "invalidation":"条件不成立则失效","uncertainty":"长期影响不确定","evidence_ids":["E1"]}}}}
                """);
        JsonNode result = ForecastAnalysis.frozen(json, record);
        assertThat(result.path("summary").asText()).isEqualTo("独立预测结论");
        assertThat(result.path("counter_factors").path(0).asText()).isEqualTo("反向信息");
        assertThat(result.path("invalidation_conditions").path(0).asText()).isEqualTo("条件不成立则失效");
        assertThat(result.path("documents").path(0).path("evidence_id").asText()).isEqualTo("E1");
        assertThat(result.path("documents").path(0).path("title").asText()).isEqualTo("实际引用标题");
        assertThat(result.path("generated_at")).isEqualTo(record.path("generated_at"));
        assertThat(result.path("input_cutoff")).isEqualTo(record.path("data_cutoff"));
        assertThat(result.has("input_payload")).isFalse();
    }

    /** ETF多资产历史分析只能展示当前资产；机制是中性描述，不能一律归为利好。 */
    @Test
    void legacyEtfItemsStayAssetScopedAndMechanismsAreNeutral() throws Exception {
        JsonNode raw =
                json.readTree(
                        """
                {"result":{"result":{"items":[
                  {"symbol":"510500","summary":"别的资产"},
                  {"symbol":"510300","status":"AVAILABLE","rationale":"当前资产依据",
                   "impact_logic":"市场传导机制","uncertainty":"政策影响不确定"}]}}}
                """);
        JsonNode result = ForecastAnalysis.project(json, raw, "510300");
        assertThat(result.path("summary").asText()).isEqualTo("当前资产依据");
        assertThat(result.path("mechanisms").path(0).asText()).isEqualTo("市场传导机制");
        assertThat(result.path("positive_factors")).isEmpty();
        assertThat(ForecastAnalysis.project(json, raw, "159915").path("summary").asText())
                .doesNotContain("别的资产", "当前资产依据");
    }

    /** 量化缺少新闻解释时只讲清模型输入身份，禁止补造新闻影响结论。 */
    @Test
    void quantOnlyRecordReportsKnownFactsWithoutInventingNews() throws Exception {
        JsonNode record =
                json.readTree(
                        """
                {"asset":"460300","scheme":"FUND_LIGHTGBM","role":"PREDICTOR",
                 "model_version":"frozen-model","anchor_date":"2026-10-28",
                 "target_version":"fund-nav-change-20-60-v1","model_validation_status":"FAILED"}
                """);
        JsonNode result = ForecastAnalysis.frozen(json, record);
        assertThat(result.path("analysis_status").asText()).isEqualTo("QUANT_INPUT_ONLY");
        assertThat(result.path("facts")).hasSize(3);
        assertThat(result.path("documents")).isEmpty();
        assertThat(result.path("summary").asText()).contains("frozen-model");
    }
}
