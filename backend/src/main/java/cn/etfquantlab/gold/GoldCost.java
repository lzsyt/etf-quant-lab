package cn.etfquantlab.gold;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 实物黄金仅作成本情景计算，不创建持仓、订单或交易账户。 */
@Component
public class GoldCost {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BENCHMARK_FINENESS = new BigDecimal("0.9999");

    /** 统一按成色换算计价克数，再做比例扣减、每克扣减、最后固定费用。 */
    public Map<String, Object> calculate(JsonNode request, List<JsonNode> forecasts, Instant now) {
        BigDecimal grams = positive(request, "quantity");
        String basis = request.path("weight_basis").asText("GROSS");
        if (!List.of("GROSS", "FINE").contains(basis)) {
            throw new IllegalArgumentException("计重口径仅支持GROSS毛重或FINE纯金克数");
        }
        BigDecimal fineness = number(request, "fineness", BENCHMARK_FINENESS);
        if (fineness.signum() <= 0 || fineness.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("成色须大于0且不超过1");
        }
        BigDecimal effective =
                grams.multiply("FINE".equals(basis) ? BigDecimal.ONE : fineness)
                        .divide(BENCHMARK_FINENESS, MC);
        BigDecimal premium = nonnegative(request, "purchase_premium_per_gram");
        BigDecimal fee = nonnegative(request, "purchase_fee");
        boolean premiumIncluded = request.path("premium_included").asBoolean(true);
        boolean feeIncluded = request.path("purchase_fee_included").asBoolean(false);
        boolean totalMode = request.hasNonNull("total_purchase_cost");
        if (totalMode == request.hasNonNull("unit_purchase_price")) {
            throw new IllegalArgumentException("买入总成本与每克成交价必须且只能填写一项");
        }
        BigDecimal total;
        if (totalMode) {
            if ((!premiumIncluded && premium.signum() > 0) || (!feeIncluded && fee.signum() > 0)) {
                throw new IllegalArgumentException("全包买入总成本不能再次叠加未包含费用或溢价");
            }
            total = positive(request, "total_purchase_cost");
        } else {
            BigDecimal unit = positive(request, "unit_purchase_price");
            total = grams.multiply(unit.add(premiumIncluded ? BigDecimal.ZERO : premium));
            total = total.add(feeIncluded ? BigDecimal.ZERO : fee);
        }
        BigDecimal benchmark = positive(request, "benchmark_price");
        BigDecimal deduction = nonnegative(request, "repurchase_deduction_per_gram");
        BigDecimal rate = nonnegative(request, "repurchase_deduction_rate");
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("回购比例扣减须在0（含）至1（不含）之间");
        }
        BigDecimal fixed = nonnegative(request, "sale_fixed_fee");
        BigDecimal retained = BigDecimal.ONE.subtract(rate);
        BigDecimal net = proceeds(effective, benchmark, retained, deduction, fixed);
        String kind = request.path("quote_kind").asText("USER_ASSUMPTION");
        if (!List.of("USER_ASSUMPTION", "VERIFIED_QUOTE").contains(kind)) {
            throw new IllegalArgumentException("报价性质须为用户假设或真实渠道报价");
        }
        Instant quotedAt = null;
        if (request.hasNonNull("quote_at") && !request.path("quote_at").asText().isBlank()) {
            try {
                quotedAt = Instant.parse(request.path("quote_at").asText());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("报价时间必须包含时区，使用ISO时间格式");
            }
            if (quotedAt.isAfter(now.plusSeconds(60))) {
                throw new IllegalArgumentException("报价时间不得在未来");
            }
        }
        if ("VERIFIED_QUOTE".equals(kind)
                && (quotedAt == null || request.path("channel").asText().isBlank())) {
            throw new IllegalArgumentException("真实报价必须填写渠道和报价时间；来源由用户负责核实");
        }
        Long ageHours =
                quotedAt == null ? null : Math.max(0, Duration.between(quotedAt, now).toHours());
        String quoteStatus =
                "USER_ASSUMPTION".equals(kind)
                        ? "USER_ASSUMPTION"
                        : quotedAt.isBefore(now.minus(Duration.ofHours(24)))
                                ? "STALE"
                                : "USER_REPORTED_QUOTE";
        List<Object> ranges = new ArrayList<>();
        for (JsonNode forecast : forecasts) {
            if (!forecast.hasNonNull("interval_lower") || !forecast.hasNonNull("interval_upper")) {
                continue;
            }
            BigDecimal low = number(forecast, "interval_lower", null);
            BigDecimal high = number(forecast, "interval_upper", null);
            if (low == null || high == null || low.signum() <= 0 || high.compareTo(low) < 0) {
                continue;
            }
            BigDecimal lowerProfit =
                    proceeds(effective, low, retained, deduction, fixed).subtract(total);
            BigDecimal upperProfit =
                    proceeds(effective, high, retained, deduction, fixed).subtract(total);
            ranges.add(
                    Map.of(
                            "horizon", forecast.path("horizon").asInt(),
                            "lower_net_profit", lowerProfit,
                            "upper_net_profit", upperProfit,
                            "lower_net_return", lowerProfit.divide(total, MC),
                            "upper_net_return", upperProfit.divide(total, MC),
                            "prediction_id", forecast.path("id").asText(),
                            "generated_at", forecast.path("generated_at").asText(),
                            "cost_assumption", "当前回购扣减、成色及费用保持不变"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actual_cost_per_gram", total.divide(grams, MC));
        result.put("total_purchase_cost", total);
        result.put("benchmark_equivalent_grams", effective);
        result.put("repurchase_net", net);
        result.put("net_profit", net.subtract(total));
        result.put("net_return", net.subtract(total).divide(total, MC));
        result.put(
                "break_even_benchmark_price",
                total.add(fixed).divide(effective, MC).add(deduction).divide(retained, MC));
        result.put("quote_status", quoteStatus);
        result.put("quote_age_hours", ageHours);
        result.put("quote_kind", kind);
        result.put("channel", request.path("channel").asText());
        result.put("profit_probability", null);
        result.put("forecast_ranges", ranges);
        result.put(
                "assumptions",
                List.of(
                        "上金所Au99.99基准按成色0.9999计价；渠道计重规则须用户核实",
                        "每克买入价按输入克数计费；毛重×成色÷0.9999或纯金克数÷0.9999为回购计价克数",
                        "回购先扣基准价比例，再减每计价克扣减，最后减固定卖出费",
                        "输入的已含溢价和已含费用仅作说明，未重复加收；全包总成本不追加费用",
                        "预测分位数净收益按成本不变假设换算；不提供未经验证的扣费后盈利概率"));
        return result;
    }

    /** 金额全程十进制；低价情景净额可能为负，表示固定成本无法覆盖。 */
    private BigDecimal proceeds(
            BigDecimal quantity,
            BigDecimal benchmark,
            BigDecimal retained,
            BigDecimal deduction,
            BigDecimal fixed) {
        return quantity.multiply(benchmark.multiply(retained).subtract(deduction)).subtract(fixed);
    }

    /** 只接受有限十进制数，并限制输入精度以防止异常大数耗尽计算资源。 */
    private BigDecimal number(JsonNode request, String field, BigDecimal fallback) {
        if (!request.hasNonNull(field) || request.path(field).asText().isBlank()) {
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(request.path(field).asText());
            if (value.precision() > 24 || Math.abs(value.scale()) > 12) {
                throw new NumberFormatException();
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field + "必须是有效十进制数");
        }
    }

    /** 克数、价格及总成本必须为正数。 */
    private BigDecimal positive(JsonNode request, String field) {
        BigDecimal value = number(request, field, null);
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须大于0");
        }
        return value;
    }

    /** 未填写的扣减及费用按零计算，负费率不能伪装为盈利。 */
    private BigDecimal nonnegative(JsonNode request, String field) {
        BigDecimal value = number(request, field, BigDecimal.ZERO);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + "不得为负数");
        }
        return value;
    }
}
