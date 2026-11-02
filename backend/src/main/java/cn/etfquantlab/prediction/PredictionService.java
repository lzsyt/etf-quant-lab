package cn.etfquantlab.prediction;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PredictionService {
    private final Store store;
    private final MarketService market;
    private final ResearchService research;
    private final QuantClient quant;
    private final TransactionTemplate tx;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 冻结预测与实际结果分开保存，不允许事后修正原始判断。 */
    public PredictionService(
            Store store,
            MarketService market,
            ResearchService research,
            QuantClient quant,
            TransactionTemplate tx) {
        this.store = store;
        this.market = market;
        this.research = research;
        this.quant = quant;
        this.tx = tx;
    }

    /** 当前研究池来源于已核验的ETF登记。 */
    public List<String> symbols() {
        return store.db.query(
                "select symbol from etf_instrument where enabled order by symbol",
                (rs, row) -> rs.getString(1));
    }

    /** 仅在最新已收盘交易日有全量真实行情时生成，防止旧行情被当成今日预测。 */
    public LocalDate latestExpected(Instant now) {
        var local = now.atZone(BEIJING);
        LocalDate bound =
                local.getHour() >= 16 ? local.toLocalDate() : local.toLocalDate().minusDays(1);
        Date date =
                store.db.queryForObject(
                        "select max(trade_date) from trading_calendar where is_open and trade_date<=?",
                        Date.class,
                        Date.valueOf(bound));
        if (date == null || date.toLocalDate().isBefore(bound.minusDays(15))) {
            throw new IllegalArgumentException("可信交易日历缺失或过期");
        }
        Integer future =
                store.db.queryForObject(
                        "select count(*) from trading_calendar where is_open and trade_date>?",
                        Integer.class,
                        Date.valueOf(bound));
        if (future == 0) {
            throw new IllegalArgumentException("交易日历未来覆盖不足，停止预测");
        }
        return date.toLocalDate();
    }

    /** 规则、机器学习及LLM信号分别生成；融合固定权重明确标注实验性且不伪装概率。 */
    public Map<String, Object> generate(Instant cutoff, JsonNode analysis) {
        LocalDate day = latestExpected(cutoff);
        List<String> symbols = symbols();
        Map<String, List<JsonNode>> histories = new LinkedHashMap<>();
        List<JsonNode> allBars = new ArrayList<>();
        for (String symbol : symbols) {
            List<JsonNode> bars = market.bars(symbol, LocalDate.of(2021, 1, 1), day, "REAL", "RAW");
            if (bars.isEmpty()
                    || !day.toString()
                            .equals(bars.get(bars.size() - 1).path("trade_date").asText())) {
                throw new IllegalArgumentException("最新交易日行情不完整：" + symbol);
            }
            histories.put(symbol, bars);
            allBars.addAll(bars);
        }
        List<String> calendar = research.calendar(LocalDate.of(2021, 1, 1), day);
        JsonNode risk = research.risk();
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> futureDates = nextDates(day, 5);
        for (int horizon : new int[] {1, 5}) {
            Map<String, Map<String, Object>> mlReference = new HashMap<>();
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                List<Map<String, Object>> group = new ArrayList<>();
                for (String symbol : symbols) {
                    Map<String, Object> record =
                            base(symbol, algorithm, horizon, day, futureDates.get(horizon - 1));
                    try {
                        JsonNode model =
                                research.latestModel(symbol, algorithm, horizon, "REAL", day);
                        LocalDate modelCutoff = LocalDate.parse(model.path("data_cutoff").asText());
                        if (modelCutoff.isBefore(day.minusDays(45))) {
                            throw new IllegalArgumentException("模型超过45天未刷新");
                        }
                        JsonNode value =
                                quant.post(
                                        "/models/predict",
                                        Map.of(
                                                "model_id",
                                                model.path("id").asText(),
                                                "bars",
                                                histories.get(symbol),
                                                "benchmark",
                                                histories.get("510300"),
                                                "trading_dates",
                                                calendar));
                        record.put("probability", value.path("probability").decimalValue());
                        record.put("direction", value.path("direction").asText());
                        record.put("model_version", model.path("id").asText());
                        record.put("feature_version", model.path("feature_version").asText());
                        record.put("model_input_data_cutoff", modelCutoff.toString());
                        String fitCutoff = value.path("fit_data_cutoff").asText(null);
                        String calibrationCutoff =
                                value.path("calibration_data_cutoff").asText(null);
                        record.put(
                                "training_data_cutoff",
                                calibrationCutoff == null
                                        ? fitCutoff
                                        : fitCutoff == null
                                                        || calibrationCutoff.compareTo(fitCutoff)
                                                                >= 0
                                                ? calibrationCutoff
                                                : fitCutoff);
                        record.put("fit_data_cutoff", value.path("fit_data_cutoff").asText(null));
                        record.put(
                                "calibration_data_cutoff",
                                value.path("calibration_data_cutoff").asText(null));
                        record.put(
                                "model_parameter_version",
                                value.path("model_parameter_version")
                                        .asText(
                                                model.path("model_parameter_version")
                                                        .asText(null)));
                        record.put(
                                "raw_score",
                                value.hasNonNull("raw_score")
                                        ? value.path("raw_score").decimalValue()
                                        : null);
                        record.put(
                                "score_semantics",
                                value.path("score_meaning").asText("CALIBRATED_UP_PROBABILITY"));
                        record.put("signal_semantics", "CALIBRATED_UP_PROBABILITY");
                        record.put("eligible", value.path("probability").asDouble() >= 0.55);
                    } catch (RuntimeException error) {
                        record.put("direction", "MISSING");
                        record.put("error_code", "MODEL_UNAVAILABLE_OR_STALE");
                        failures.add(symbol + "/" + algorithm + "/h" + horizon);
                    }
                    group.add(record);
                    if ("lightgbm".equals(algorithm)) {
                        mlReference.put(symbol, record);
                    }
                }
                allocate(group, risk);
                records.addAll(group);
            }
            for (String strategy :
                    List.of("ma", "rsi", "macd", "momentum", "rotation", "buy_hold", "cash")) {
                Map<String, Object> input = new HashMap<>();
                input.put("strategy", strategy);
                input.put("bars", allBars);
                input.put("instruments", research.instruments(symbols));
                input.put("start", "2021-01-01");
                input.put("end", day.toString());
                input.put("trading_dates", calendar);
                input.put("risk", risk);
                JsonNode signals = quant.post("/strategies/signals", input).path("signals");
                for (JsonNode signal : signals) {
                    Map<String, Object> record =
                            base(
                                    signal.path("symbol").asText(),
                                    strategy,
                                    horizon,
                                    day,
                                    futureDates.get(horizon - 1));
                    BigDecimal weight = new BigDecimal(signal.path("target_weight").asText());
                    record.put("target_weight", weight.toPlainString());
                    record.put("direction", weight.signum() > 0 ? "UP" : "FLAT");
                    record.put("signal_semantics", "RULE_POSITION_SIGNAL_NOT_PROBABILITY");
                    record.put("raw_score", weight);
                    record.put("score_semantics", "TARGET_PORTFOLIO_WEIGHT_NOT_RETURN");
                    record.put("model_parameter_version", "rules-v1");
                    records.add(record);
                }
            }
            boolean validAnalysis =
                    "SUCCEEDED".equals(analysis.path("status").asText())
                            && "REAL".equals(analysis.path("nature").asText());
            for (String strategy : List.of("llm", "fusion")) {
                List<Map<String, Object>> group = new ArrayList<>();
                for (String symbol : symbols) {
                    Map<String, Object> record =
                            base(symbol, strategy, horizon, day, futureDates.get(horizon - 1));
                    record.put("analysis_id", analysis.path("id").asText());
                    record.put("news_cutoff", analysis.path("input_cutoff").asText(null));
                    Map<String, Object> ml = mlReference.get(symbol);
                    if (validAnalysis
                            && (!"fusion".equals(strategy) || ml.containsKey("probability"))) {
                        double sentiment =
                                analysis.path("result")
                                        .path("result")
                                        .path("sentiment_score")
                                        .asDouble();
                        double score =
                                "llm".equals(strategy)
                                        ? sentiment
                                        : 0.7
                                                        * (2
                                                                        * ((BigDecimal)
                                                                                        ml.get(
                                                                                                "probability"))
                                                                                .doubleValue()
                                                                - 1)
                                                + 0.3 * sentiment;
                        record.put("score", score);
                        record.put("raw_score", BigDecimal.valueOf(score));
                        record.put(
                                "score_semantics",
                                "fusion".equals(strategy)
                                        ? "0.7*(2*P_UP-1)+0.3*SENTIMENT; RANGE [-1,1]; NOT_RETURN_NOT_PROBABILITY"
                                        : "EVENT_SENTIMENT; RANGE [-1,1]; NOT_RETURN_NOT_PROBABILITY");
                        record.put(
                                "model_parameter_version",
                                "fusion".equals(strategy)
                                        ? "fusion-fixed-70-30-v1/"
                                                + analysis.path("prompt_version").asText()
                                        : analysis.path("prompt_version").asText());
                        record.put(
                                "eligible",
                                score > 0.1
                                        && !"high"
                                                .equals(
                                                        analysis.path("result")
                                                                .path("result")
                                                                .path("risk_level")
                                                                .asText()));
                        record.put("direction", score > 0 ? "UP" : score < 0 ? "DOWN" : "FLAT");
                        record.put("model_version", analysis.path("model_version").asText());
                        record.put(
                                "signal_semantics",
                                "fusion".equals(strategy)
                                        ? "EXPERIMENTAL_FIXED_70_30_NOT_CALIBRATED"
                                        : "EVENT_SENTIMENT_NOT_PROBABILITY");
                        if ("fusion".equals(strategy)) {
                            record.put("ml_model_version", ml.get("model_version"));
                            record.put("training_data_cutoff", ml.get("training_data_cutoff"));
                            record.put("fit_data_cutoff", ml.get("fit_data_cutoff"));
                            record.put(
                                    "calibration_data_cutoff", ml.get("calibration_data_cutoff"));
                            record.put("ml_parameter_version", ml.get("model_parameter_version"));
                            record.put(
                                    "model_input_data_cutoff", ml.get("model_input_data_cutoff"));
                        }
                    } else {
                        record.put("direction", "MISSING");
                        record.put("error_code", "LLM_OR_MODEL_MISSING");
                    }
                    group.add(record);
                }
                allocate(group, risk);
                records.addAll(group);
            }
        }
        int inserted =
                freeze(
                        records,
                        cutoff,
                        Map.of(
                                "bars",
                                allBars,
                                "calendar",
                                calendar,
                                "risk",
                                risk,
                                "analysis_id",
                                analysis.path("id").asText(),
                                "price_basis",
                                "RAW"));
        return Map.of(
                "signal_date",
                day,
                "created",
                inserted,
                "total",
                records.size(),
                "model_failures",
                failures,
                "evaluation_type",
                "FORWARD_PAPER");
    }

    /** 相同预测业务键只能插入一次；整个日批次原子提交，重试只返回原结果。 */
    public int freeze(List<Map<String, Object>> records, Instant cutoff) {
        return freeze(records, cutoff, Map.of("origin", "TEST_OR_INTERNAL"));
    }

    /** 每批输入只保存一份并禁止更新；相同业务键重试不产生多余输入快照。 */
    private int freeze(
            List<Map<String, Object>> records, Instant cutoff, Map<String, Object> input) {
        return tx.execute(
                status -> {
                    store.lock("prediction:freeze");
                    int inserted = 0;
                    UUID inputId = UUID.randomUUID();
                    for (Map<String, Object> record : records) {
                        // 缺失分析不是有效预测，只在任务/分析表保留错误；禁止冻结虚构方向。
                        if ("MISSING".equals(record.get("direction"))) {
                            continue;
                        }
                        String key =
                                record.get("strategy")
                                        + ":"
                                        + record.get("symbol")
                                        + ":"
                                        + record.get("horizon")
                                        + ":"
                                        + record.get("signal_date");
                        if (store.db.queryForObject(
                                        "select count(*) from prediction where business_key=?",
                                        Integer.class,
                                        key)
                                > 0) {
                            continue;
                        }
                        if (inserted == 0) {
                            store.db.update(
                                    "insert into prediction_input(id,input_cutoff,snapshot) values(?,?,?::jsonb)",
                                    inputId,
                                    Timestamp.from(cutoff),
                                    store.encode(input));
                        }
                        Map<String, Object> details = new HashMap<>(record);
                        details.put("input_snapshot_id", inputId.toString());
                        LocalDate signalDay = LocalDate.parse(record.get("signal_date").toString());
                        String target =
                                record.containsKey("target_date")
                                        ? record.get("target_date").toString()
                                        : nextDates(
                                                        signalDay,
                                                        ((Number) record.get("horizon")).intValue())
                                                .get(
                                                        ((Number) record.get("horizon")).intValue()
                                                                - 1);
                        Instant marketCutoff = signalDay.atTime(15, 0).atZone(BEIJING).toInstant();
                        if (marketCutoff.isAfter(cutoff) || cutoff.isAfter(Instant.now())) {
                            throw new IllegalArgumentException("行情截止或输入截止晚于实际生成时刻");
                        }
                        details.put("target_date", target);
                        details.put("market_cutoff", marketCutoff.toString());
                        details.put("evidence_version", "v1.1");
                        store.db.update(
                                "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                                        + "input_cutoff,model_version,nature,probability,direction,details,"
                                        + "target_date,market_cutoff,news_cutoff,training_data_cutoff,"
                                        + "model_parameter_version,raw_score,score_semantics)"
                                        + " values(?,?,?,?,?,?,?,?,'REAL',?,?,?::jsonb,?,?,?,?,?,?,?)",
                                UUID.randomUUID(),
                                key,
                                record.get("symbol"),
                                record.get("strategy"),
                                record.get("horizon"),
                                Date.valueOf(record.get("signal_date").toString()),
                                Timestamp.from(cutoff),
                                record.get("model_version"),
                                record.get("probability"),
                                record.get("direction"),
                                store.encode(details),
                                Date.valueOf(target),
                                Timestamp.from(marketCutoff),
                                record.get("news_cutoff") == null
                                        ? null
                                        : Timestamp.from(
                                                Instant.parse(
                                                        record.get("news_cutoff")
                                                                .toString()
                                                                .replace("+00:00", "Z"))),
                                record.get("training_data_cutoff") == null
                                        ? null
                                        : Date.valueOf(
                                                record.get("training_data_cutoff").toString()),
                                record.get("model_parameter_version"),
                                record.get("raw_score"),
                                record.get("score_semantics"));
                        inserted++;
                    }
                    return inserted;
                });
    }

    /** 统一保存真实输入口径和截止日期，不把规则分数转换为概率。 */
    private Map<String, Object> base(
            String symbol, String strategy, int horizon, LocalDate day, String targetDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("strategy", strategy);
        result.put("horizon", horizon);
        result.put("signal_date", day.toString());
        result.put("target_date", targetDate);
        result.put("model_version", "rules-v1");
        result.put("price_basis", "RAW");
        result.put("target_weight", "0");
        result.put("evaluation_type", "FORWARD_PAPER");
        return result;
    }

    /** 所有信号共用相同仓位上限；无效模型不会被旧概率替代。 */
    public static void allocate(List<Map<String, Object>> group, JsonNode risk) {
        long count = group.stream().filter(r -> Boolean.TRUE.equals(r.get("eligible"))).count();
        BigDecimal total = new BigDecimal(risk.path("max_total_weight").asText());
        BigDecimal single = new BigDecimal(risk.path("max_single_weight").asText());
        for (Map<String, Object> record : group) {
            BigDecimal weight =
                    count > 0 && Boolean.TRUE.equals(record.get("eligible"))
                            ? total.divide(BigDecimal.valueOf(count), 10, RoundingMode.DOWN)
                                    .min(single)
                            : BigDecimal.ZERO;
            record.put("target_weight", weight.toPlainString());
        }
    }

    /** 目标日期只取已登记的有效交易日；覆盖不足立即停止，绝不按自然日猜测。 */
    public List<String> nextDates(LocalDate signal, int horizon) {
        if (horizon != 1 && horizon != 5) {
            throw new IllegalArgumentException("只支持1或5个交易日预测");
        }
        List<String> dates =
                store.db.query(
                        "select trade_date::text from trading_calendar where is_open and "
                                + "trade_date>? order by trade_date limit ?",
                        (rs, row) -> rs.getString(1),
                        Date.valueOf(signal),
                        horizon);
        if (dates.size() != horizon) {
            throw new IllegalArgumentException("交易日历未来覆盖不足，不能冻结目标日期");
        }
        return dates;
    }

    /** 到达真实第1/5个交易日才对账；缺失价格保留未评估，绝不平移到其他日期。 */
    public int reconcile(LocalDate latest) {
        return tx.execute(
                status -> {
                    store.lock("prediction:actual");
                    List<JsonNode> pending =
                            store.db.query(
                                    "select row_to_json(p)::text from prediction p"
                                            + " left join prediction_actual a on a.prediction_id=p.id"
                                            + " where a.prediction_id is null"
                                            + " and p.nature='REAL' and p.direction<>'MISSING' and p.signal_date<?",
                                    (rs, row) -> store.decode(rs.getString(1)),
                                    Date.valueOf(latest));
                    int count = 0;
                    for (JsonNode prediction : pending) {
                        LocalDate signal = LocalDate.parse(prediction.path("signal_date").asText());
                        List<String> dates = research.calendar(signal.plusDays(1), latest);
                        int horizon = prediction.path("horizon").asInt();
                        if (dates.size() < horizon) {
                            continue;
                        }
                        LocalDate target =
                                prediction.hasNonNull("target_date")
                                        ? LocalDate.parse(prediction.path("target_date").asText())
                                        : LocalDate.parse(dates.get(horizon - 1));
                        if (target.isAfter(latest)) {
                            continue;
                        }
                        String symbol = prediction.path("symbol").asText();
                        List<JsonNode> from = market.bars(symbol, signal, signal, "REAL", "RAW");
                        List<JsonNode> to = market.bars(symbol, target, target, "REAL", "RAW");
                        if (from.isEmpty() || to.isEmpty()) {
                            continue;
                        }
                        BigDecimal change =
                                new BigDecimal(to.get(0).path("close").asText())
                                        .divide(
                                                new BigDecimal(from.get(0).path("close").asText()),
                                                10,
                                                RoundingMode.HALF_UP)
                                        .subtract(BigDecimal.ONE);
                        boolean up = change.signum() > 0;
                        BigDecimal brier =
                                prediction.hasNonNull("probability")
                                        ? prediction
                                                .path("probability")
                                                .decimalValue()
                                                .subtract(up ? BigDecimal.ONE : BigDecimal.ZERO)
                                                .pow(2)
                                        : null;
                        boolean timely =
                                Instant.parse(
                                                prediction
                                                        .path("created_at")
                                                        .asText()
                                                        .replace("+00:00", "Z"))
                                        .isBefore(
                                                LocalDate.parse(dates.get(0))
                                                        .atTime(9, 30)
                                                        .atZone(BEIJING)
                                                        .toInstant());
                        Instant firstOpen =
                                LocalDate.parse(dates.get(0))
                                        .atTime(9, 30)
                                        .atZone(BEIJING)
                                        .toInstant();
                        timely =
                                timely
                                        && Instant.parse(
                                                        prediction
                                                                .path("input_cutoff")
                                                                .asText()
                                                                .replace("+00:00", "Z"))
                                                .isBefore(firstOpen);
                        store.db.update(
                                "insert into prediction_actual(prediction_id,target_date,actual_return,actual_up,"
                                        + "correct,brier_score,price_basis,details) values(?,?,?,?,?,?,'RAW',?::jsonb)",
                                UUID.fromString(prediction.path("id").asText()),
                                Date.valueOf(target),
                                change,
                                up,
                                timely
                                                && List.of("UP", "DOWN")
                                                        .contains(
                                                                prediction
                                                                        .path("direction")
                                                                        .asText())
                                        ? ("UP".equals(prediction.path("direction").asText())
                                                ? change.signum() > 0
                                                : change.signum() < 0)
                                        : null,
                                timely ? brier : null,
                                store.encode(
                                        Map.of(
                                                "timely_before_next_open",
                                                timely,
                                                "return_basis",
                                                "PRICE_RETURN_ONLY",
                                                "target_date_origin",
                                                prediction.hasNonNull("target_date")
                                                        ? "FROZEN"
                                                        : "LEGACY_CALENDAR_DERIVED",
                                                "from",
                                                from.get(0),
                                                "to",
                                                to.get(0))));
                        count++;
                    }
                    return count;
                });
    }
}
