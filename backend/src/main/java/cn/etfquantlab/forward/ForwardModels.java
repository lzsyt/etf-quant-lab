package cn.etfquantlab.forward;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ForwardModels {
    private final Store store;
    private final QuantClient quant;
    private final MarketService market;
    private final ResearchService research;
    private final TransactionTemplate tx;
    private final String codeVersion;

    /** 新模型使用独立登记，旧latestModel路径不可能误选新标签工件。 */
    public ForwardModels(
            Store store,
            QuantClient quant,
            MarketService market,
            ResearchService research,
            TransactionTemplate tx,
            @Value("${etf.code-version:LOCAL_UNRELEASED}") String codeVersion) {
        this.store = store;
        this.quant = quant;
        this.market = market;
        this.research = research;
        this.tx = tx;
        this.codeVersion = codeVersion;
    }

    /** 准备同一已确认日历和RAW行情；未来日期只用于定位，不加入特征。 */
    public Map<String, Object> input(String symbol, String algorithm, LocalDate day) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("algorithm", algorithm);
        payload.put("horizon", 1);
        payload.put("feature_version", "ohlcv-v2");
        payload.put("label_version", ForwardRules.LABEL);
        payload.put("protocol_version", ForwardRules.PROTOCOL);
        payload.put("bars", market.bars(symbol, LocalDate.of(2021, 1, 1), day, "REAL", "RAW"));
        payload.put(
                "benchmark", market.bars("510300", LocalDate.of(2021, 1, 1), day, "REAL", "RAW"));
        payload.put(
                "trading_dates",
                research.calendar(LocalDate.of(2021, 1, 1), LocalDate.of(2100, 1, 1)));
        return payload;
    }

    /** 所有已登记公司行动日期均隔离标签，不把尚未核实事件忽略成没有事件。 */
    public List<String> actionDates(String symbol) {
        return store.db.query(
                "select distinct ex_date::text from corporate_action where symbol=? order by 1",
                (rs, row) -> rs.getString(1),
                symbol);
    }

    /** 完整身份及可用时间参与选择，旧角色、未来工件和陈旧数据均不能消费。 */
    public JsonNode latest(String symbol, String algorithm, LocalDate day, Instant cutoff) {
        List<JsonNode> found =
                store.db.query(
                        "select metadata::text from forward_model where symbol=? and algorithm=? and available_at<=?"
                                + " and metadata->>'feature_version'='ohlcv-v2' and metadata->>'label_version'=?"
                                + " and metadata->>'protocol_version'=? and metadata->>'horizon'='1'"
                                + " and metadata->>'nature'='REAL' and metadata->>'price_basis'='RAW'"
                                + " and metadata->>'prediction_model_role'='FORWARD_PRODUCTION'"
                                + " and metadata->>'target_semantics'='NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP'"
                                + " and (metadata->>'data_cutoff')::date between ?::date-45 and ?::date"
                                + " order by available_at desc limit 1",
                        (rs, row) -> store.decode(rs.getString(1)),
                        symbol,
                        algorithm,
                        Timestamp.from(cutoff),
                        ForwardRules.LABEL,
                        ForwardRules.PROTOCOL,
                        day.toString(),
                        day.toString());
        return found.isEmpty() ? null : found.get(0);
    }

    /** 按三ETF两算法顺序训练，仅缺少当前合格工件时执行，不并发占用线上资源。 */
    public List<JsonNode> ensure(LocalDate day) {
        return ensure(ForwardRules.SYMBOLS, day);
    }

    /** 统一工作台只准备所选ETF的两种算法，继续遵守前瞻标签和时间分割规则。 */
    public List<JsonNode> ensure(String symbol, LocalDate day) {
        if (!ForwardRules.SYMBOLS.contains(symbol)) {
            throw new IllegalArgumentException("不支持的前瞻ETF");
        }
        return ensure(List.of(symbol), day);
    }

    /** 单资产与原日终批量入口共用同一训练和工件校验，避免目标协议漂移。 */
    private List<JsonNode> ensure(List<String> symbols, LocalDate day) {
        List<JsonNode> result = new ArrayList<>();
        for (String symbol : symbols) {
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                JsonNode existing = latest(symbol, algorithm, day, Instant.now());
                if (existing != null
                        && !LocalDate.parse(existing.path("data_cutoff").asText())
                                .isBefore(day.minusDays(30))) {
                    result.add(existing);
                    continue;
                }
                Map<String, Object> payload = input(symbol, algorithm, day);
                payload.put("training_cutoff_at", Instant.now().toString());
                payload.put("code_version", codeVersion);
                payload.put("corporate_action_dates", actionDates(symbol));
                payload.put("corporate_action_coverage", "UNKNOWN");
                JsonNode trained = quant.post("/forward/models/train", payload);
                verifyIdentity(trained, symbol, algorithm, "prediction_model_role");
                if (!trained.hasNonNull("id")
                        || !trained.path("artifact_sha256").isObject()
                        || trained.path("artifact_sha256").isEmpty()
                        || !trained.path("production_training_range").has("fit")
                        || !trained.hasNonNull("label_available_cutoff_at")) {
                    throw new IllegalArgumentException("新标签训练缺少工件、生产区间或标签可用证据");
                }
                tx.executeWithoutResult(
                        status -> {
                            String id = trained.path("id").asText();
                            store.lock("forward:model:" + id);
                            if (store.db.queryForObject(
                                            "select count(*) from forward_model where id=?",
                                            Integer.class,
                                            id)
                                    == 0) {
                                store.db.update(
                                        "insert into forward_model(id,symbol,algorithm,"
                                                + "available_at,metadata) values(?,?,?,?,?::jsonb)",
                                        id,
                                        symbol,
                                        algorithm,
                                        Timestamp.from(
                                                Instant.parse(
                                                        trained.path("available_at").asText())),
                                        store.encode(trained));
                                store.audit(
                                        "FORWARD_MODEL_TRAIN",
                                        Map.of(
                                                "model_id",
                                                id,
                                                "label_version",
                                                ForwardRules.LABEL));
                            }
                        });
                result.add(trained);
            }
        }
        return result;
    }

    /** 单个模型失败只返回异常状态，不伪造概率，也不将缺失解释为清仓。 */
    public JsonNode predict(String symbol, String algorithm, LocalDate day, Instant cutoff) {
        return predict(symbol, algorithm, day, cutoff, input(symbol, algorithm, day));
    }

    /** 同批四策略共享已取得行情快照；克隆请求字段，推理期间不重新查询可变行情。 */
    public JsonNode predict(
            String symbol,
            String algorithm,
            LocalDate day,
            Instant cutoff,
            Map<String, Object> frozenInput) {
        JsonNode model = latest(symbol, algorithm, day, cutoff);
        if (model == null) {
            return store.json.valueToTree(
                    Map.of("status", "MISSING", "error_code", "MODEL_MISSING_OR_STALE"));
        }
        Map<String, Object> payload = new LinkedHashMap<>(frozenInput);
        payload.put("symbol", symbol);
        payload.put("algorithm", algorithm);
        payload.put("model_id", model.path("id").asText());
        payload.put("signal_date", day.toString());
        payload.put("cutoff_at", cutoff.toString());
        payload.put("corporate_action_dates", actionDates(symbol));
        try {
            var predicted = quant.post("/forward/models/predict", payload).deepCopy();
            verifyIdentity(predicted, symbol, algorithm, "model_role");
            if (!model.path("id").asText().equals(predicted.path("model_id").asText())
                    || !day.toString().equals(predicted.path("signal_date").asText())
                    || !validProbability(predicted.path("probability"))
                    || !validProbability(predicted.path("raw_probability"))) {
                throw new IllegalArgumentException("新标签推理返回身份或概率不合法");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) predicted).put("status", "SUCCEEDED");
            return predicted;
        } catch (RuntimeException error) {
            store.audit(
                    "FORWARD_PREDICT_FAILED",
                    Map.of("model_id", model.path("id").asText(), "error", safeReason(error)));
            return store.json.valueToTree(
                    Map.of(
                            "status",
                            "FAILED",
                            "error_code",
                            "MODEL_DATA_ERROR",
                            "reason",
                            safeReason(error)));
        }
    }

    /** HTTP200仍须核对真实模型身份，不能把旧标签或错误角色登记为业务成功。 */
    private static void verifyIdentity(
            JsonNode result, String symbol, String algorithm, String roleField) {
        if (!result.isObject()
                || !symbol.equals(result.path("symbol").asText())
                || !algorithm.equals(result.path("algorithm").asText())
                || result.path("horizon").asInt() != 1
                || !"ohlcv-v2".equals(result.path("feature_version").asText())
                || !ForwardRules.LABEL.equals(result.path("label_version").asText())
                || !ForwardRules.PROTOCOL.equals(result.path("protocol_version").asText())
                || !"NEXT_OPEN_TO_FOLLOWING_OPEN_PRICE_UP"
                        .equals(result.path("target_semantics").asText())
                || !"FORWARD_PRODUCTION".equals(result.path(roleField).asText())
                || !"REAL".equals(result.path("nature").asText())
                || !"RAW".equals(result.path("price_basis").asText())) {
            throw new IllegalArgumentException("新实验模型身份不匹配，禁止沿用旧模型或测试模型");
        }
    }

    /** 模型概率须为有限数值，缺字段不能被Jackson默认值转换成零。 */
    private static boolean validProbability(JsonNode value) {
        return value.isNumber()
                && Double.isFinite(value.asDouble())
                && value.asDouble() >= 0
                && value.asDouble() <= 1;
    }

    /** 只展示已受控的业务校验信息，其余异常不传播连接参数或秘密。 */
    public static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return error instanceof IllegalArgumentException
                        && message != null
                        && message.length() <= 220
                ? message
                : error.getClass().getSimpleName();
    }
}
