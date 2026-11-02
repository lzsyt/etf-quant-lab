package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.etfquantlab.gold.GoldCost;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldCostTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GoldCost cost = new GoldCost();
    private final Instant now = Instant.parse("2026-10-26T13:00:00Z");

    /** 用户指定的测试例仅存在测试代码，1020买入加10回购扣减应在1030保本。 */
    @Test
    void requiredBreakEvenExampleUsesDecimal() {
        Map<String, Object> result = cost.calculate(base(), List.of(), now);
        assertThat((BigDecimal) result.get("break_even_benchmark_price"))
                .isEqualByComparingTo("1030");
        assertThat((BigDecimal) result.get("actual_cost_per_gram")).isEqualByComparingTo("1020");
        assertThat((BigDecimal) result.get("net_profit")).isEqualByComparingTo("-3000");
        assertThat(result.get("profit_probability")).isNull();
    }

    /** 百分比在每克扣减之前应用，固定费用只收一次，保本结果反算必须零利润。 */
    @Test
    void percentAndFixedFeesRoundTripAtBreakEven() {
        ObjectNode request = base();
        request.put("repurchase_deduction_rate", "0.02");
        request.put("sale_fixed_fee", "50");
        request.put("purchase_fee", "25");
        var first = cost.calculate(request, List.of(), now);
        request.put(
                "benchmark_price",
                ((BigDecimal) first.get("break_even_benchmark_price")).toPlainString());
        // 输入接口最多12位小数；实际报价精度以十进制截断，误差不得超过0.000001元。
        request.put(
                "benchmark_price",
                new BigDecimal(request.path("benchmark_price").asText())
                        .setScale(10, java.math.RoundingMode.HALF_UP)
                        .toPlainString());
        var second = cost.calculate(request, List.of(), now);
        assertThat(((BigDecimal) second.get("net_profit")).abs())
                .isLessThan(new BigDecimal("0.000001"));
    }

    /** 成色和净金克数对应同一Au99.99计价克数，不可再按毛重重复计价。 */
    @Test
    void finenessAndPureWeightAreExplicit() {
        ObjectNode request = base();
        request.put("fineness", "0.99");
        var result = cost.calculate(request, List.of(), now);
        assertThat((BigDecimal) result.get("benchmark_equivalent_grams"))
                .isEqualByComparingTo(
                        new BigDecimal("99")
                                .divide(
                                        new BigDecimal("0.9999"),
                                        java.math.MathContext.DECIMAL128));
        request.put("weight_basis", "FINE");
        var pure = cost.calculate(request, List.of(), now);
        assertThat((BigDecimal) pure.get("benchmark_equivalent_grams"))
                .isEqualByComparingTo(
                        new BigDecimal("100")
                                .divide(
                                        new BigDecimal("0.9999"),
                                        java.math.MathContext.DECIMAL128));
    }

    /** 已包含费用和溢价只作备注；全包总成本禁止再次加收未包含费用。 */
    @Test
    void includedFeesAreNotCountedTwiceAndTotalRejectsExtras() {
        ObjectNode request = base();
        request.put("purchase_fee", "100");
        request.put("purchase_fee_included", true);
        request.put("purchase_premium_per_gram", "20");
        request.put("premium_included", true);
        assertThat((BigDecimal) cost.calculate(request, List.of(), now).get("total_purchase_cost"))
                .isEqualByComparingTo("102000");
        request.remove("unit_purchase_price");
        request.put("total_purchase_cost", "102000");
        request.put("purchase_fee_included", false);
        assertThatThrownBy(() -> cost.calculate(request, List.of(), now))
                .hasMessageContaining("不能再次叠加");
    }

    /** 无效克数、价格、比例和成色全部在计算前失败，不返回伪造收益。 */
    @Test
    void invalidBoundariesFail() {
        for (Map.Entry<String, String> bad :
                Map.of(
                                "quantity",
                                "0",
                                "benchmark_price",
                                "NaN",
                                "fineness",
                                "1.1",
                                "repurchase_deduction_rate",
                                "1",
                                "sale_fixed_fee",
                                "-1")
                        .entrySet()) {
            ObjectNode request = base();
            request.put(bad.getKey(), bad.getValue());
            assertThatThrownBy(() -> cost.calculate(request, List.of(), now))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode both = base();
        both.put("total_purchase_cost", "102000");
        assertThatThrownBy(() -> cost.calculate(both, List.of(), now))
                .hasMessageContaining("只能填写一项");
    }

    /** 手填真实报价仍明确为用户报告；24小时以上标过期且不改原时间。 */
    @Test
    void staleQuotesAndFutureQuotesAreNotPresentedAsLive() {
        ObjectNode request = base();
        request.put("quote_kind", "VERIFIED_QUOTE");
        request.put("channel", "测试渠道");
        request.put("quote_at", "2026-10-24T12:00:00Z");
        assertThat(cost.calculate(request, List.of(), now).get("quote_status")).isEqualTo("STALE");
        request.put("quote_at", "2026-10-27T12:00:00Z");
        assertThatThrownBy(() -> cost.calculate(request, List.of(), now))
                .hasMessageContaining("未来");
    }

    /** 过期判断精确到秒，24小时加1秒不能被向下取整成仍有效报价。 */
    @Test
    void quoteExpiresImmediatelyAfter24Hours() {
        ObjectNode request = base();
        request.put("quote_kind", "VERIFIED_QUOTE");
        request.put("channel", "测试渠道");
        request.put("quote_at", now.minusSeconds(86400).toString());
        assertThat(cost.calculate(request, List.of(), now).get("quote_status"))
                .isEqualTo("USER_REPORTED_QUOTE");
        request.put("quote_at", now.minusSeconds(86401).toString());
        assertThat(cost.calculate(request, List.of(), now).get("quote_status")).isEqualTo("STALE");
    }

    /** 只把真实预测分位数转换为同成本情景净额，不生成扣费后盈利概率。 */
    @Test
    void forecastIntervalsConvertUsingSameCosts() {
        ObjectNode forecast =
                json.createObjectNode()
                        .put("horizon", 63)
                        .put("interval_lower", "900")
                        .put("interval_upper", "1200")
                        .put("id", "test")
                        .put("generated_at", now.toString());
        var result = cost.calculate(base(), List.of(forecast), now);
        var range = (Map<?, ?>) ((List<?>) result.get("forecast_ranges")).get(0);
        assertThat((BigDecimal) range.get("lower_net_profit")).isEqualByComparingTo("-13000");
        assertThat((BigDecimal) range.get("upper_net_profit")).isEqualByComparingTo("17000");
    }

    /** 所有数字仅用于隔离测试，不写行情和业务持仓。 */
    private ObjectNode base() {
        return json.createObjectNode()
                .put("quantity", "100")
                .put("unit_purchase_price", "1020")
                .put("benchmark_price", "1000")
                .put("repurchase_deduction_per_gram", "10");
    }
}
