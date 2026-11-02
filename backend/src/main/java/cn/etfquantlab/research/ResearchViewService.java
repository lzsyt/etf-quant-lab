package cn.etfquantlab.research;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ResearchViewService {
    private final Store store;
    private final QuantClient quant;

    /** 展示层只读取冻结实验和真实账本，不触发训练、补预测或付费分析。 */
    public ResearchViewService(Store store, QuantClient quant) {
        this.store = store;
        this.quant = quant;
    }

    /** 四种证据分别查询；预测正确率不能被包装成账户投资收益。 */
    public ObjectNode rankings(String mode, int horizon) {
        if (!List.of("history", "oos", "forward", "paper").contains(mode)
                || (horizon != 1 && horizon != 5)) {
            throw new IllegalArgumentException("结果类型或预测窗口无效");
        }
        ObjectNode result = store.json.createObjectNode();
        result.put("mode", mode);
        result.putArray("rows");
        result.putArray("curves");
        result.putArray("calibration");
        ObjectNode comparison = result.putObject("comparability");
        comparison.put("horizon", horizon);
        comparison.put("return_unit", "小数比例，0.01代表1%");
        comparison.put("score_unit", "无量纲分数不等于收益率；只有校准概率进入概率校准图");
        result.putArray("limitations").add("样本不足无法得出可靠结论；历史表现不代表未来收益。");
        if (mode.equals("history") || mode.equals("oos")) {
            historical(result, horizon, mode.equals("oos"));
        } else if (mode.equals("forward")) {
            forward(result, horizon);
        } else {
            paper(result);
        }
        return result;
    }

    /** 同一张比较图只使用同一个冻结实验，拒绝拼接不同区间的最高收益。 */
    private void historical(ObjectNode response, int horizon, boolean oos) {
        List<JsonNode> experiments =
                store.db.query(
                        "select jsonb_build_object('id',id,'configuration',configuration,'result',result)::text"
                                + " from backtest_experiment where status='SUCCEEDED' and nature='REAL'"
                                + " and evaluation_type=? and coalesce((configuration->'request'->>'horizon')::int,1)=?"
                                + " order by created_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        oos ? "HISTORICAL_OOS" : "HISTORICAL_RULE",
                        horizon);
        if (experiments.isEmpty()) {
            ((ArrayNode) response.get("limitations")).add("当前类型没有已完成的真实行情实验。");
            return;
        }
        JsonNode experiment = experiments.get(0);
        JsonNode config = experiment.path("configuration");
        JsonNode result = experiment.path("result");
        ObjectNode comparable = (ObjectNode) response.get("comparability");
        comparable.put("experiment_id", experiment.path("id").asText());
        comparable.set("start", result.path("start"));
        comparable.set("end", result.path("end"));
        comparable.set("initial_capital", config.path("initial_capital"));
        comparable.set("risk", config.path("risk"));
        comparable.put("basis", "同一实验的共同窗口、资金和成本；买入持有受同样风控约束，非全仓基准");
        JsonNode strategies = result.path("results");
        double benchmark =
                strategies
                        .path("buy_hold")
                        .path("metrics")
                        .path("total_return")
                        .asDouble(Double.NaN);
        strategies
                .fields()
                .forEachRemaining(
                        entry -> {
                            ObjectNode row = baseRow(entry.getKey(), label(entry.getKey()));
                            JsonNode value = entry.getValue();
                            row.put("status", value.path("status").asText("COMPLETED"));
                            row.set("metrics", value.path("metrics"));
                            row.put("samples", value.path("curve").size());
                            row.put(
                                    "evaluation_type",
                                    oos
                                                    && List.of("lightgbm", "xgboost", "logistic")
                                                            .contains(entry.getKey())
                                            ? "STRICT_OOS"
                                            : "HISTORICAL_RULE");
                            metricFields(row, value.path("metrics"));
                            if (!Double.isNaN(benchmark)
                                    && value.path("metrics").hasNonNull("total_return")) {
                                row.put(
                                        "excess_return",
                                        value.path("metrics").path("total_return").asDouble()
                                                - benchmark);
                            }
                            row.set("reason", value.path("reason"));
                            ((ArrayNode) response.get("rows")).add(row);
                            addCurve(
                                    response,
                                    entry.getKey(),
                                    label(entry.getKey()),
                                    value.path("curve"),
                                    config.path("initial_capital").asDouble(100000));
                        });
        for (String name : List.of("llm", "fusion")) {
            if (!strategies.has(name)) {
                ObjectNode row = baseRow(name, label(name));
                row.put("status", "NOT_ELIGIBLE");
                row.put("reason", "缺少当时可得的新闻证据，只允许真实时间推进的前瞻验证");
                ((ArrayNode) response.get("rows")).add(row);
            }
        }
        ((ArrayNode) response.get("limitations")).add("旧实验缺失的活动指标保持空值；新版审计结果另建实验，保留旧记录。");
    }

    /** 只统计该窗口已到期且及时生成的真实预测，迟到与缺失记录不进入分母。 */
    private void forward(ObjectNode response, int horizon) {
        List<JsonNode> evaluated =
                store.db.query(
                        "select jsonb_build_object('strategy_id',p.strategy_id,'probability',p.probability,"
                                + "'correct',a.correct,'actual_up',a.actual_up,'target_date',a.target_date,"
                                + "'brier_score',a.brier_score)::text from prediction p"
                                + " join prediction_actual a on a.prediction_id=p.id"
                                + " where p.nature='REAL' and p.horizon=? and a.correct is not null"
                                + " and p.created_at < ((select min(c.trade_date) from trading_calendar c"
                                + " where c.is_open and c.trade_date>p.signal_date) + time '09:30') at "
                                + "time zone 'Asia/Shanghai'"
                                + " order by a.target_date,p.id",
                        (rs, row) -> store.decode(rs.getString(1)),
                        horizon);
        for (JsonNode strategy : strategies()) {
            String id = strategy.path("id").asText();
            List<JsonNode> selected =
                    evaluated.stream()
                            .filter(p -> id.equals(p.path("strategy_id").asText()))
                            .toList();
            ObjectNode row = baseRow(id, strategy.path("name").asText());
            row.put("samples", selected.size());
            row.put(
                    "activity_status",
                    selected.isEmpty() ? "INSUFFICIENT_SAMPLES" : "FORWARD_OBSERVATIONS");
            if (!selected.isEmpty()) {
                row.put(
                        "accuracy",
                        selected.stream().filter(p -> p.path("correct").asBoolean()).count()
                                / (double) selected.size());
            }
            ((ArrayNode) response.get("rows")).add(row);
            ObjectNode curve = ((ArrayNode) response.get("curves")).addObject();
            curve.put("strategy_id", id);
            curve.put("label", strategy.path("name").asText());
            ArrayNode points = curve.putArray("points");
            Map<String, List<JsonNode>> days = new LinkedHashMap<>();
            selected.forEach(
                    p ->
                            days.computeIfAbsent(
                                            p.path("target_date").asText(),
                                            ignored -> new ArrayList<>())
                                    .add(p));
            int count = 0;
            int correct = 0;
            for (var day : days.entrySet()) {
                count += day.getValue().size();
                correct +=
                        (int)
                                day.getValue().stream()
                                        .filter(p -> p.path("correct").asBoolean())
                                        .count();
                ObjectNode point = points.addObject();
                point.put("date", day.getKey());
                point.put("samples", count);
                point.put("accuracy", correct / (double) count);
                point.putNull("total_return");
                point.putNull("drawdown");
            }
            calibration((ArrayNode) response.get("calibration"), id, selected);
        }
        ((ObjectNode) response.get("comparability"))
                .put("basis", "按到期日累计，1日与5日窗口独立；收益请查看模拟账本，不能由正确率推算");
    }

    /** 每个校准桶同时报告预测均值、实际上涨频率和有效样本量。 */
    static void calibration(ArrayNode output, String strategy, List<JsonNode> values) {
        for (int bucket = 0; bucket < 10; bucket++) {
            final int index = bucket;
            List<JsonNode> eligible =
                    values.stream()
                            .filter(
                                    p ->
                                            p.hasNonNull("probability")
                                                    && Math.min(
                                                                    9,
                                                                    (int)
                                                                            (p.path("probability")
                                                                                            .asDouble()
                                                                                    * 10))
                                                            == index)
                            .toList();
            if (eligible.isEmpty()) {
                continue;
            }
            ObjectNode point = output.addObject();
            point.put("strategy_id", strategy);
            point.put("bucket", bucket / 10.0 + "–" + (bucket + 1) / 10.0);
            point.put("samples", eligible.size());
            point.put(
                    "predicted_probability",
                    eligible.stream()
                            .mapToDouble(p -> p.path("probability").asDouble())
                            .average()
                            .orElseThrow());
            point.put(
                    "observed_frequency",
                    eligible.stream().filter(p -> p.path("actual_up").asBoolean()).count()
                            / (double) eligible.size());
        }
    }

    /** 模拟收益只从实际账户快照和成交计算，净值曲线不使用历史回测替代。 */
    private void paper(ObjectNode response) {
        List<JsonNode> accounts =
                store.db.query(
                        "select row_to_json(t)::text from (select a.*,s.name from paper_account a"
                                + " join strategy_config s on s.id=a.strategy_id order by a.id) t",
                        (rs, row) -> store.decode(rs.getString(1)));
        List<Map<String, Object>> requests = new ArrayList<>();
        Map<String, List<JsonNode>> curves = new LinkedHashMap<>();
        for (JsonNode account : accounts) {
            String id = account.path("id").asText();
            List<JsonNode> curve =
                    store.db.query(
                            "select (details || "
                                    + "jsonb_build_object('trade_date',trade_date,'equity',equity,'cash',cash))::text"
                                    + " from account_snapshot where account_id=? order by trade_date",
                            (rs, row) -> store.decode(rs.getString(1)),
                            id);
            List<JsonNode> fills =
                    store.db.query(
                            "select (f.details || "
                                    + "jsonb_build_object('trade_date',o.trade_date,'symbol',"
                                    + "o.symbol,'side',o.side))::text"
                                    + " from paper_fill f join paper_order o on o.id=f.order_id where "
                                    + "o.account_id=? order by o.trade_date",
                            (rs, row) -> store.decode(rs.getString(1)),
                            id);
            curves.put(id, curve);
            requests.add(
                    Map.of(
                            "id",
                            id,
                            "curve",
                            curve,
                            "fills",
                            fills,
                            "initial_capital",
                            account.path("initial_capital").asText()));
        }
        JsonNode metrics;
        try {
            metrics = quant.post("/paper/metrics", Map.of("accounts", requests));
        } catch (RuntimeException error) {
            metrics = store.json.createObjectNode();
            ((ArrayNode) response.get("limitations")).add("Python统计服务暂不可用，保留账户实际余额和曲线；派生指标不伪造。");
        }
        JsonNode benchmark =
                accounts.stream()
                        .filter(a -> "buy_hold".equals(a.path("strategy_id").asText()))
                        .findFirst()
                        .orElse(null);
        for (JsonNode account : accounts) {
            String id = account.path("id").asText();
            ObjectNode row = baseRow(id, account.path("name").asText());
            row.set("metrics", metrics.path(id));
            metricFields(row, metrics.path(id));
            row.put(
                    "total_return",
                    account.path("equity").asDouble() / account.path("initial_capital").asDouble()
                            - 1);
            row.put("samples", curves.get(id).size());
            row.set("equity", account.path("equity"));
            row.set("initial_capital", account.path("initial_capital"));
            row.set("created_at", account.path("created_at"));
            if (benchmark != null
                    && sameDates(curves.get(id), curves.get(benchmark.path("id").asText()))) {
                double base =
                        benchmark.path("equity").asDouble()
                                        / benchmark.path("initial_capital").asDouble()
                                - 1;
                row.put("excess_return", row.path("total_return").asDouble() - base);
                row.put("benchmark_return", base);
            }
            ((ArrayNode) response.get("rows")).add(row);
            addCurve(
                    response,
                    id,
                    account.path("name").asText(),
                    store.json.valueToTree(curves.get(id)),
                    account.path("initial_capital").asDouble());
        }
        ((ObjectNode) response.get("comparability"))
                .put("basis", "每个账户独立虚拟本金；仅快照日期完全一致时显示相对约束型买入持有的超额");
    }

    /** 缺失日期不能填零，同一曲线的资金收益和回撤均以初始本金为起点。 */
    private void addCurve(
            ObjectNode response, String id, String label, JsonNode source, Double capital) {
        ObjectNode curve = ((ArrayNode) response.get("curves")).addObject();
        curve.put("strategy_id", id);
        curve.put("label", label);
        ArrayNode points = curve.putArray("points");
        double peak = capital == null ? 0 : capital;
        for (JsonNode value : source) {
            ObjectNode point = points.addObject();
            point.put("date", value.path("trade_date").asText(value.path("date").asText()));
            double equity = value.path("equity").asDouble();
            peak = Math.max(peak, equity);
            if (capital != null && capital > 0) {
                point.put("total_return", equity / capital - 1);
                point.put("drawdown", peak == 0 ? 0 : 1 - equity / peak);
            } else {
                point.set(
                        "total_return",
                        value.has("total_return")
                                ? value.get("total_return")
                                : value.path("cumulative_return"));
                point.set("drawdown", value.path("drawdown"));
            }
            point.putNull("accuracy");
        }
    }

    /** 只有相同实际估值日期集合才允许计算两个模拟账户的超额收益。 */
    static boolean sameDates(List<JsonNode> left, List<JsonNode> right) {
        return left != null
                && right != null
                && !left.isEmpty()
                && left.stream()
                        .map(v -> v.path("trade_date").asText())
                        .toList()
                        .equals(right.stream().map(v -> v.path("trade_date").asText()).toList());
    }

    /** 空指标显式为null，防止零交易被展示为零风险或百分之百胜率。 */
    private ObjectNode baseRow(String id, String name) {
        ObjectNode row = store.json.createObjectNode();
        row.put("strategy_id", id);
        row.put("label", name);
        for (String key :
                List.of(
                        "total_return",
                        "max_drawdown",
                        "accuracy",
                        "samples",
                        "trade_count",
                        "capital_utilization",
                        "excess_return",
                        "fees",
                        "slippage_cost",
                        "activity_status")) {
            row.putNull(key);
        }
        return row;
    }

    /** 直接暴露已计算指标，避免再次实现一套不同的回测统计公式。 */
    private void metricFields(ObjectNode row, JsonNode metrics) {
        for (String field :
                List.of(
                        "total_return",
                        "max_drawdown",
                        "trade_count",
                        "capital_utilization",
                        "fees",
                        "slippage_cost",
                        "activity_status",
                        "holding_sessions",
                        "win_rate",
                        "avg_win_amount",
                        "avg_loss_amount",
                        "profit_loss_ratio",
                        "max_consecutive_losses",
                        "turnover")) {
            if (metrics.has(field)) {
                row.set(field, metrics.get(field));
            }
        }
        if (metrics.has("excess_return_vs_buy_hold")) {
            row.set("excess_return", metrics.get("excess_return_vs_buy_hold"));
        }
    }

    /** 策略名称来自已登记的现有实验组。 */
    private List<JsonNode> strategies() {
        return store.db.query(
                "select row_to_json(s)::text from strategy_config s order by id",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 买入持有中文名称明确保留风险约束，避免被理解为全仓指数收益。 */
    private String label(String id) {
        return switch (id) {
            case "ma" -> "均线趋势";
            case "lightgbm" -> "LightGBM";
            case "xgboost" -> "XGBoost";
            case "llm" -> "DeepSeek事件研究";
            case "fusion" -> "机器学习与事件融合实验";
            case "buy_hold" -> "约束型买入持有";
            case "cash" -> "保持现金";
            default -> id;
        };
    }
}
