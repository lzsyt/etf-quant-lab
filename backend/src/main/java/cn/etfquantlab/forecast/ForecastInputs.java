package cn.etfquantlab.forecast;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardModels;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 冻结各资产原生输入；量化和大模型共享目标，但彼此不读取预测结果。 */
@Service
public class ForecastInputs {
    private final Store store;
    private final FundResearchService funds;
    private final GoldService gold;
    private final MarketService market;
    private final ResearchService research;
    private final ForwardModels forward;
    private final ForwardService forwardService;
    private final PredictionService legacy;
    private final AnalysisService news;
    private final QuantClient quant;
    private final CloseModels close;

    /** 复用已登记模型与真实观测，不在推理时训练或收费采集。 */
    public ForecastInputs(
            Store store,
            FundResearchService funds,
            GoldService gold,
            MarketService market,
            ResearchService research,
            ForwardModels forward,
            ForwardService forwardService,
            PredictionService legacy,
            AnalysisService news,
            QuantClient quant,
            CloseModels close) {
        this.store = store;
        this.funds = funds;
        this.gold = gold;
        this.market = market;
        this.research = research;
        this.forward = forward;
        this.forwardService = forwardService;
        this.legacy = legacy;
        this.news = news;
        this.quant = quant;
        this.close = close;
    }

    /** 一个机会包含各量化输入及独立新闻请求，所有可用性检查以固定截止为准。 */
    public ObjectNode freeze(String asset, String target, int horizon, Instant cutoff) {
        ObjectNode snapshot = store.json.createObjectNode();
        ObjectNode context = snapshot.putObject("context");
        context.put("asset", asset);
        context.put("target", target);
        context.put("horizon", horizon);
        context.put("mode", "REAL");
        context.put("data_cutoff", cutoff.toString());
        ObjectNode requests = snapshot.putObject("quant_requests");
        List<JsonNode> observations;
        List<Map<String, Object>> prices = new ArrayList<>();
        if ("FUND_NAV_CHANGE".equals(target) || "FUND_NAV_UP".equals(target)) {
            if (!funds.enabled()) throw new IllegalArgumentException("FUND_DISABLED");
            observations = fundObservations(asset, cutoff);
            if (observations.isEmpty()) throw new IllegalArgumentException("NAV_UNAVAILABLE");
            JsonNode last = observations.get(observations.size() - 1);
            context.put("anchor_date", last.path("nav_date").asText());
            context.put("anchor_value", last.path("unit_nav").asText());
            context.put(
                    "target_version",
                    "FUND_NAV_UP".equals(target) ? "fund-nav-up-5-v1" : "fund-nav-change-20-60-v1");
            context.put("horizon_unit", "FUND_NAV_OBSERVATIONS");
            context.putNull("target_date");
            requireFresh(context, cutoff, 7);
            for (JsonNode row : tail(observations)) {
                prices.add(price(row, "nav_date", "unit_nav", "first_observed_at", "provider"));
            }
            // 两个算法各选当前截止前的最近工件，不能被另一算法更晚的训练覆盖。
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                List<JsonNode> models =
                        store
                                .db
                                .query(
                                        "select details::text from fund_model where fund_code=? and horizon=?"
                                                + " and created_at<=? and coalesce(details->>'algorithm','lightgbm')=?"
                                                + " and (horizon<>5 or (details->>'target_version'='fund-nav-up-5-v1'"
                                                + " and details->>'capability'='PROBABILITY'))"
                                                + " order by created_at desc,id desc limit 1",
                                        (rs, n) -> store.decode(rs.getString(1)),
                                        asset,
                                        horizon,
                                        java.sql.Timestamp.from(cutoff),
                                        algorithm)
                                .stream()
                                .filter(
                                        row ->
                                                row.path("horizon").asInt() == horizon
                                                        && row.hasNonNull("artifact_hash"))
                                .toList();
                if (!models.isEmpty()) {
                    requests.set(
                            "lightgbm".equals(algorithm) ? "FUND_LIGHTGBM" : "FUND_XGBOOST",
                            store.json.valueToTree(
                                    Map.of(
                                            "path",
                                            "/funds/models/predict",
                                            "payload",
                                            Map.of(
                                                    "fund_code",
                                                    asset,
                                                    "observations",
                                                    observations,
                                                    "models",
                                                    models,
                                                    "generated_at",
                                                    cutoff.toString()))));
                }
            }
        } else if ("GOLD_BENCHMARK_RETURN".equals(target)) {
            if (!gold.enabled()) throw new IllegalArgumentException("GOLD_DISABLED");
            observations = goldObservations(cutoff);
            List<JsonNode> benchmark =
                    observations.stream()
                            .filter(
                                    row ->
                                            GoldService.BENCHMARK.equals(
                                                    row.path("series_id").asText()))
                            .toList();
            if (benchmark.isEmpty()) throw new IllegalArgumentException("GOLD_DATA_UNAVAILABLE");
            JsonNode last = benchmark.get(benchmark.size() - 1);
            context.put("anchor_date", last.path("observation_date").asText());
            context.put("anchor_value", last.path("value").asText());
            context.put("target_version", "SGE_CLOSE_RETURN_63_126_V1");
            context.put("horizon_unit", "SGE_TRADING_DAYS");
            context.putNull("target_date");
            requireFresh(context, cutoff, 10);
            for (JsonNode row : tail(benchmark)) {
                prices.add(price(row, "observation_date", "value", "available_at", "source"));
            }
            // 黄金同样按算法和期限冻结，旧未注明算法的工件继续归属LightGBM。
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                List<JsonNode> models =
                        store.db.query(
                                "select details::text from gold_model where horizon=? and registered_at<=?"
                                        + " and coalesce(details->>'algorithm','lightgbm')=?"
                                        + " order by registered_at desc,model_id limit 1",
                                (rs, n) -> store.decode(rs.getString(1)),
                                horizon,
                                java.sql.Timestamp.from(cutoff),
                                algorithm);
                if (!models.isEmpty())
                    requests.set(
                            "lightgbm".equals(algorithm) ? "GOLD_LIGHTGBM" : "GOLD_XGBOOST",
                            store.json.valueToTree(
                                    Map.of(
                                            "path",
                                            "/gold/predict",
                                            "payload",
                                            Map.of(
                                                    "observations",
                                                    observations,
                                                    "models",
                                                    models,
                                                    "generated_at",
                                                    cutoff.toString(),
                                                    "code_version",
                                                    "unified-v2"))));
            }
        } else {
            LocalDate day = legacy.latestExpected(cutoff);
            List<JsonNode> bars = marketBars(asset, day, cutoff);
            if (bars.isEmpty()
                    || !day.toString()
                            .equals(bars.get(bars.size() - 1).path("trade_date").asText())) {
                throw new IllegalArgumentException("LATEST_MARKET_UNAVAILABLE");
            }
            context.put("signal_date", day.toString());
            context.put("horizon_unit", "TRADING_DAYS");
            boolean open = "ETF_FORWARD_OPEN".equals(target);
            boolean closeReturn = "ETF_CLOSE_RETURN".equals(target);
            String nextOpen = legacy.nextDates(day, 1).get(0);
            Instant deadline = ForwardService.open(LocalDate.parse(nextOpen));
            if (!cutoff.isBefore(deadline))
                throw new IllegalArgumentException("PREDICTION_LATE_FOR_TARGET");
            context.put("submission_deadline", deadline.toString());
            if (open) {
                LocalDate[] dates = forwardService.following(day);
                context.put("anchor_date", dates[0].toString());
                context.put("target_date", dates[1].toString());
                context.put("target_version", "OPEN_TO_OPEN_H1_V1/OPEN_NET_REBALANCE_V1");
            } else {
                context.put("anchor_date", day.toString());
                context.put("anchor_value", bars.get(bars.size() - 1).path("close").asText());
                if (closeReturn) {
                    List<String> future =
                            research.calendar(day.plusDays(1), LocalDate.of(2100, 1, 1));
                    context.put(
                            "target_date",
                            future.size() >= horizon ? future.get(horizon - 1) : null);
                    context.put(
                            "calendar_status",
                            future.size() >= horizon ? "CONFIRMED" : "UNCONFIRMED");
                    if (future.size() < horizon)
                        context.putArray("reason_codes").add("FUTURE_CALENDAR_UNAVAILABLE");
                } else {
                    context.put("target_date", legacy.nextDates(day, horizon).get(horizon - 1));
                }
                context.put(
                        "target_version",
                        closeReturn
                                ? CloseModels.LABEL + "/" + CloseModels.PROTOCOL
                                : "LEGACY_CLOSE_PRICE_RETURN/v1.1/RAW");
            }
            for (JsonNode row : tail(bars))
                prices.add(price(row, "trade_date", "close", "fetched_at", "source"));
            List<JsonNode> benchmark = marketBars("510300", day, cutoff);
            for (String algorithm : List.of("lightgbm", "xgboost")) {
                JsonNode model;
                try {
                    if (open) model = forward.latest(asset, algorithm, day, cutoff);
                    else if (closeReturn)
                        model = close.latest(asset, algorithm, horizon, day, cutoff);
                    else {
                        List<JsonNode> versions =
                                store.db.query(
                                        "select metadata::text from model_version where symbol=? and algorithm=?"
                                                + " and horizon=? and metadata->>'nature'='REAL' and trained_at<=?"
                                                + " and (metadata->>'data_cutoff')::date between ? and ?"
                                                + " order by trained_at desc limit 1",
                                        (rs, n) -> store.decode(rs.getString(1)),
                                        asset,
                                        algorithm,
                                        horizon,
                                        java.sql.Timestamp.from(cutoff),
                                        java.sql.Date.valueOf(day.minusDays(45)),
                                        java.sql.Date.valueOf(day));
                        model = versions.isEmpty() ? null : versions.get(0);
                    }
                } catch (IllegalArgumentException missing) {
                    continue;
                }
                if (model == null) continue;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model_id", model.path("id").asText());
                payload.put("bars", bars);
                payload.put("benchmark", benchmark);
                payload.put(
                        "trading_dates",
                        research.calendar(
                                LocalDate.of(2021, 1, 1),
                                (open || closeReturn) ? LocalDate.of(2100, 1, 1) : day));
                if (open || closeReturn) {
                    payload.putAll(
                            Map.of(
                                    "symbol",
                                    asset,
                                    "algorithm",
                                    algorithm,
                                    "horizon",
                                    open ? 1 : horizon,
                                    "feature_version",
                                    "ohlcv-v2",
                                    "label_version",
                                    open ? "OPEN_TO_OPEN_H1_V1" : CloseModels.LABEL,
                                    "protocol_version",
                                    open ? "OPEN_NET_REBALANCE_V1" : CloseModels.PROTOCOL,
                                    "signal_date",
                                    day.toString(),
                                    "cutoff_at",
                                    cutoff.toString(),
                                    "corporate_action_dates",
                                    forward.actionDates(asset)));
                }
                String scheme =
                        open
                                ? ("lightgbm".equals(algorithm) ? "LGBM_BASE" : "XGB_BASE")
                                : closeReturn
                                        ? ("lightgbm".equals(algorithm)
                                                ? "CLOSE_LIGHTGBM"
                                                : "CLOSE_XGBOOST")
                                        : algorithm;
                requests.set(
                        scheme,
                        store.json.valueToTree(
                                Map.of(
                                        "path",
                                        open
                                                ? "/forward/models/predict"
                                                : closeReturn
                                                        ? "/close/models/predict"
                                                        : "/models/predict",
                                        "payload",
                                        payload,
                                        "model",
                                        model)));
            }
        }
        ObjectNode request = context.deepCopy();
        request.remove(
                List.of(
                        "mode",
                        "data_cutoff",
                        "horizon_unit",
                        "anchor_value",
                        "signal_date",
                        "submission_deadline",
                        "calendar_status",
                        "reason_codes"));
        request.put("cutoff", cutoff.toString());
        request.set("prices", store.json.valueToTree(prices));
        request.set("news", store.json.valueToTree(news.candidates(cutoff)));
        snapshot.set("news_request", request);
        return snapshot;
    }

    /** 在冻结的原生请求上重新推理，绝不读取旧预测并更改生成时间。 */
    public JsonNode infer(JsonNode snapshot, String scheme) {
        JsonNode request = snapshot.path("quant_requests").path(scheme);
        if (request.isMissingNode()) throw new IllegalArgumentException("MODEL_NOT_READY");
        return quant.post(request.path("path").asText(), request.path("payload"));
    }

    /** 先筛当时可得修订再按净值日取最新，后来修订不能遮蔽已冻结截止前的合法数据。 */
    private List<JsonNode> fundObservations(String asset, Instant cutoff) {
        return store.db.query(
                "select details::text from (select distinct on(nav_date) nav_date,details "
                        + "from fund_nav_observation where fund_code=? and nature='REAL' "
                        + "and quality_status in ('VALID','WARNING') and first_observed_at<=? "
                        + "and fetched_at<=? and nav_date<=? "
                        + "order by nav_date,revision desc,first_observed_at desc,id) n order by nav_date",
                (rs, n) -> store.decode(rs.getString(1)),
                asset,
                Timestamp.from(cutoff),
                Timestamp.from(cutoff),
                Date.valueOf(cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()));
    }

    /** 黄金历史首次公开时间允许未知；本次已抓取且完成日盘收盘才可作为实时预测资料。 */
    private List<JsonNode> goldObservations(Instant cutoff) {
        return store.db.query(
                "select details::text from (select distinct on(series_id,observation_date) "
                        + "series_id,observation_date,details from gold_observation "
                        + "where quality='REAL_OFFICIAL_CURRENT_VINTAGE' and fetched_at<=? "
                        + "and (available_at is null or available_at<=?) and observation_date<=? "
                        + "and (series_id<>? or (observation_date+time '15:30') "
                        + "at time zone 'Asia/Shanghai'<=?) "
                        + "order by series_id,observation_date,fetched_at desc,id) o "
                        + "order by series_id,observation_date",
                (rs, n) -> store.decode(rs.getString(1)),
                Timestamp.from(cutoff),
                Timestamp.from(cutoff),
                Date.valueOf(cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()),
                GoldService.BENCHMARK,
                Timestamp.from(cutoff));
    }

    /** ETF同样在去重前固定取得时刻，保留原行情服务的真实来源、质量及完整收盘门槛。 */
    private List<JsonNode> marketBars(String asset, LocalDate day, Instant cutoff) {
        return store.db.query(
                "select row_to_json(b)::text from (select distinct on(trade_date) symbol,"
                        + "trade_date,open::text,high::text,low::text,close::text,volume::text,amount::text,"
                        + "price_basis,source,nature,fetched_at,quality_status,quality_flags,raw_values "
                        + "from market_bar where symbol=? and trade_date between ? and ? "
                        + "and nature='REAL' and price_basis='RAW' "
                        + "and quality_status in ('VALID','WARNING') and fetched_at<=? "
                        + "and fetched_at>=(trade_date+time '15:15') at time zone 'Asia/Shanghai' "
                        + "and exists(select 1 from trading_calendar c "
                        + "where c.trade_date=market_bar.trade_date and c.is_open) "
                        + "order by trade_date,fetched_at desc,id) b order by trade_date",
                (rs, n) -> store.decode(rs.getString(1)),
                asset,
                Date.valueOf(LocalDate.of(2021, 1, 1)),
                Date.valueOf(day),
                Timestamp.from(cutoff));
    }

    /** 大模型只接收有界价格上下文，完整量化输入仍独立冻结。 */
    private List<JsonNode> tail(List<JsonNode> rows) {
        return rows.subList(Math.max(0, rows.size() - 60), rows.size());
    }

    /** 以十进制文本传递价格，不将价格转成二进制浮点。 */
    private Map<String, Object> price(
            JsonNode row, String day, String value, String available, String source) {
        String time =
                row.hasNonNull(available)
                        ? row.path(available).asText()
                        : row.path("fetched_at").asText();
        if (row.hasNonNull("fetched_at")
                && Instant.parse(row.path("fetched_at").asText()).isAfter(Instant.parse(time))) {
            time = row.path("fetched_at").asText();
        }
        return Map.of(
                "date",
                row.path(day).asText(),
                "value",
                row.path(value).asText(),
                "available_at",
                time,
                "source",
                row.path(source).asText("NATIVE_REAL"));
    }

    /** 数据陈旧时明示阻断，不制造刚生成所以有效的预测。 */
    private void requireFresh(JsonNode context, Instant cutoff, int maximumAge) {
        if (LocalDate.parse(context.path("anchor_date").asText())
                .isBefore(
                        cutoff.atZone(ZoneId.of("Asia/Shanghai"))
                                .toLocalDate()
                                .minusDays(maximumAge))) {
            throw new IllegalArgumentException("DATA_STALE");
        }
    }
}
