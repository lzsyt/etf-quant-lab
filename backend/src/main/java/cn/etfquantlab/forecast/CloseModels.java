package cn.etfquantlab.forecast;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardModels;
import cn.etfquantlab.forward.ForwardRules;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
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

/** ETF中期收盘幅度工件共用版本表，但用完整目标身份与旧分类工件隔离。 */
@Service
public class CloseModels {
    public static final String LABEL = "CLOSE_TO_CLOSE_H20_H60_V1";
    public static final String PROTOCOL = "CLOSE_RETURN_RESEARCH_V1";
    public static final String ROLE = "CLOSE_RETURN_RESEARCH";
    private final Store store;
    private final QuantClient quant;
    private final MarketService market;
    private final ResearchService research;
    private final ForwardModels forward;
    private final TransactionTemplate tx;
    private final String codeVersion;

    /** 复用真实行情、日历与公司行动证据，不增加来源或训练时自动抓取。 */
    public CloseModels(
            Store store,
            QuantClient quant,
            MarketService market,
            ResearchService research,
            ForwardModels forward,
            TransactionTemplate tx,
            @Value("${etf.code-version:LOCAL_UNRELEASED}") String codeVersion) {
        this.store = store;
        this.quant = quant;
        this.market = market;
        this.research = research;
        this.forward = forward;
        this.tx = tx;
        this.codeVersion = codeVersion;
    }

    /** 只选截止前已登记、训练日期不过期、完整目标相同的真实RAW工件。 */
    public JsonNode latest(
            String asset, String algorithm, int horizon, LocalDate day, Instant cutoff) {
        List<JsonNode> found =
                store.db.query(
                        "select metadata::text from model_version where symbol=? and algorithm=? and horizon=?"
                                + " and trained_at<=? and (metadata->>'available_at')::timestamptz<=?"
                                + " and metadata->>'label_version'=?"
                                + " and metadata->>'protocol_version'=?"
                                + " and metadata->>'prediction_model_role'=?"
                                + " and metadata->>'nature'='REAL' and metadata->>'price_basis'='RAW'"
                                + " and metadata->>'feature_version'='ohlcv-v2'"
                                + " and (metadata->>'data_cutoff')::date between ? and ?"
                                + " order by trained_at desc,id desc limit 1",
                        (rs, n) -> store.decode(rs.getString(1)),
                        asset,
                        algorithm,
                        horizon,
                        Timestamp.from(cutoff),
                        Timestamp.from(cutoff),
                        LABEL,
                        PROTOCOL,
                        ROLE,
                        Date.valueOf(day.minusDays(45)),
                        Date.valueOf(day));
        return found.isEmpty() ? null : found.get(0);
    }

    /** 只准备用户所选资产与期限的两个算法；已有新鲜工件直接复用。 */
    public List<JsonNode> ensure(String asset, int horizon, LocalDate day) {
        if (!ForwardRules.SYMBOLS.contains(asset) || !List.of(20, 60).contains(horizon)) {
            throw new IllegalArgumentException("不支持的ETF中期目标");
        }
        List<JsonNode> result = new ArrayList<>();
        // 同一训练调用的两个算法共享真实历史快照，避免重复查询大段行情。
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("symbol", asset);
        common.put("horizon", horizon);
        common.put("feature_version", "ohlcv-v2");
        common.put("label_version", LABEL);
        common.put("protocol_version", PROTOCOL);
        common.put("bars", market.bars(asset, LocalDate.of(2021, 1, 1), day, "REAL", "RAW"));
        common.put(
                "benchmark", market.bars("510300", LocalDate.of(2021, 1, 1), day, "REAL", "RAW"));
        common.put(
                "trading_dates",
                research.calendar(LocalDate.of(2021, 1, 1), LocalDate.of(2100, 1, 1)));
        common.put("corporate_action_dates", forward.actionDates(asset));
        common.put("code_version", codeVersion);
        common.put("training_cutoff_at", Instant.now().toString());
        for (String algorithm : List.of("lightgbm", "xgboost")) {
            JsonNode model = latest(asset, algorithm, horizon, day, Instant.now());
            if (model != null) {
                result.add(model);
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>(common);
            payload.put("algorithm", algorithm);
            JsonNode trained = quant.post("/close/models/train", payload);
            verify(trained, asset, algorithm, horizon);
            tx.executeWithoutResult(
                    status -> {
                        String id = trained.path("id").asText();
                        store.lock("model:" + id);
                        if (store.db.queryForObject(
                                        "select count(*) from model_version where id=?",
                                        Integer.class,
                                        id)
                                > 0) return;
                        store.db.update(
                                "insert into model_version(id,symbol,algorithm,horizon,feature_version,"
                                        + "training_range,hyperparameters,metrics,metadata)"
                                        + " values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                                id,
                                asset,
                                algorithm,
                                horizon,
                                "ohlcv-v2",
                                store.encode(trained.path("training_range")),
                                store.encode(trained.path("hyperparameters")),
                                store.encode(trained.path("metrics")),
                                store.encode(trained));
                        store.audit(
                                "CLOSE_MODEL_TRAIN",
                                Map.of("model_id", id, "label_version", LABEL));
                    });
            result.add(trained);
        }
        return result;
    }

    /** HTTP成功仍核对目标身份，防止旧分类器或其他资产工件被登记到幅度研究。 */
    private void verify(JsonNode model, String asset, String algorithm, int horizon) {
        if (!asset.equals(model.path("symbol").asText())
                || !algorithm.equals(model.path("algorithm").asText())
                || horizon != model.path("horizon").asInt()
                || model.path("id").asText().isBlank()
                || !LABEL.equals(model.path("label_version").asText())
                || !PROTOCOL.equals(model.path("protocol_version").asText())
                || !ROLE.equals(model.path("prediction_model_role").asText())
                || !"ohlcv-v2".equals(model.path("feature_version").asText())
                || !"REAL".equals(model.path("nature").asText())
                || !"RAW".equals(model.path("price_basis").asText())) {
            throw new IllegalArgumentException("ETF中期模型身份不匹配");
        }
    }
}
