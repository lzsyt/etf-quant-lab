package cn.etfquantlab.scheduling;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.paper.PaperService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DailyService {
    @org.springframework.beans.factory.annotation.Value(
            "${etf.forecast-enabled:${ETF_FORECAST_ENABLED:false}}")
    private boolean unifiedForecast;

    private final Store store;
    private final MarketService market;
    private final PaperService paper;
    private final AnalysisService analysis;
    private final PredictionService predictions;
    private final ResearchService research;
    private final ResearchReportService reports;

    /** 日终流程只协调已具备幂等边界的服务，进程重启可从数据库继续。 */
    public DailyService(
            Store store,
            MarketService market,
            PaperService paper,
            AnalysisService analysis,
            PredictionService predictions,
            ResearchService research,
            ResearchReportService reports) {
        this.store = store;
        this.market = market;
        this.paper = paper;
        this.analysis = analysis;
        this.predictions = predictions;
        this.research = research;
        this.reports = reports;
    }

    /** 增量行情、历史结算、实际对账与新预测按顺序执行，旧日不会补造当时的预测。 */
    public Map<String, Object> run() {
        return run(Instant.now());
    }

    /** 时钟参数只决定日终执行窗口；预测生成与存档仍使用实际时刻，不回填历史。 */
    public Map<String, Object> run(Instant scheduledAt) {
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            report.put("calendar", market.syncCalendar());
        } catch (RuntimeException error) {
            report.put("calendar", Map.of("status", "FETCH_FAILED_USING_VERIFIED_COVERAGE"));
        }
        var local = scheduledAt.atZone(java.time.ZoneId.of("Asia/Shanghai"));
        Boolean open = market.tradingDayStatus(local.toLocalDate());
        if (open == null) {
            throw new IllegalArgumentException("交易日历尚未覆盖运行日期，不能判定休市或执行日终");
        }
        report.put("run_calendar_date", local.toLocalDate());
        report.put("generated_at", Instant.now());
        report.put("mode", "PAPER_ONLY");
        if (!open) {
            report.put("status", "SKIPPED_NON_TRADING_DAY");
            report.put("reason", "官方日历休市；不采集新闻、不调用模型、不生成预测或模拟交易");
            return report;
        }
        if (local.toLocalTime().isBefore(java.time.LocalTime.of(16, 0))) {
            report.put("status", "SKIPPED_MARKET_NOT_CLOSED");
            report.put("reason", "尚未到收盘数据完整性窗口，等待北京时间16:00后");
            return report;
        }
        LocalDate day = predictions.latestExpected(scheduledAt);
        if (!day.equals(local.toLocalDate())) {
            throw new IllegalArgumentException("当前交易日尚未确认收盘，拒绝使用前一日代替日终");
        }
        List<Object> syncs = new ArrayList<>();
        for (String symbol : predictions.symbols()) {
            Date last =
                    store.db.queryForObject(
                            "select max(trade_date) from market_bar where symbol=?"
                                    + " and nature='REAL' and price_basis='RAW'",
                            Date.class,
                            symbol);
            LocalDate start =
                    last == null ? LocalDate.of(2021, 1, 1) : last.toLocalDate().minusDays(10);
            try {
                syncs.add(
                        market.sync(
                                Map.of(
                                        "symbol",
                                        symbol,
                                        "start",
                                        start.toString(),
                                        "end",
                                        day.toString(),
                                        "provider",
                                        "SINA",
                                        "price_basis",
                                        "RAW")));
            } catch (RuntimeException error) {
                syncs.add(Map.of("symbol", symbol, "status", "SYNC_FAILED"));
            }
            if (market.bars(symbol, day, day, "REAL", "RAW").isEmpty()) {
                throw new IllegalArgumentException("日终等待完整行情：" + symbol);
            }
        }
        report.put("market", syncs);
        List<Object> settlements = new ArrayList<>();
        for (Map<String, Object> account :
                store.db.queryForList("select id,last_trade_date from paper_account")) {
            LocalDate start =
                    account.get("last_trade_date") == null
                            ? day
                            : ((Date) account.get("last_trade_date")).toLocalDate().plusDays(1);
            for (String value : research.calendar(start, day)) {
                Map<String, Object> settlement =
                        paper.settleAccount(account.get("id").toString(), LocalDate.parse(value));
                settlements.add(settlement);
                if (!List.of("SETTLED", "VALUATION_ONLY", "ALREADY_PROCESSED")
                        .contains(settlement.get("status"))) {
                    break;
                }
            }
        }
        report.put("paper", settlements);
        report.put("reconciled", predictions.reconcile(day));
        try {
            report.put("news", analysis.collect().path("sources"));
        } catch (RuntimeException error) {
            report.put(
                    "news",
                    Map.of(
                            "status",
                            "COLLECTION_FAILED",
                            "error_code",
                            error.getClass().getSimpleName()));
        }
        Instant cutoff = Instant.now();
        JsonNode events;
        try {
            // 账户仍按旧协议结算；新的独立新闻预测由统一执行项承担，避免一份输入重复收费。
            events =
                    unifiedForecast
                            ? store.json.valueToTree(
                                    Map.of(
                                            "status",
                                            "MISSING",
                                            "error_code",
                                            "UNIFIED_FORECAST_TAKES_OVER"))
                            : analysis.analyze(cutoff);
        } catch (RuntimeException error) {
            // 新闻或供应商不可用只禁用事件/融合分支，技术和ML仍使用已验证行情。
            events =
                    store.json.valueToTree(
                            Map.of("status", "MISSING", "error_code", "ANALYSIS_UNAVAILABLE"));
        }
        report.put(
                "analysis",
                Map.of("id", events.path("id").asText(), "status", events.path("status").asText()));
        report.put("predictions", predictions.generate(cutoff, events));
        report.put("trade_date", day.toString());
        report.put("generated_at", Instant.now());
        report.put(
                "run_calendar_date",
                Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate());
        report.put(
                "schedule_semantics",
                "CURRENT_CONFIRMED_TRADING_DAY_AFTER_16_00; CLOSED_DAYS_SKIP; NO_BACKDATED_PREDICTION");
        report.put("mode", "PAPER_ONLY");
        try {
            JsonNode saved = reports.generate(day, report);
            report.put(
                    "research_report",
                    Map.of("status", "SUCCEEDED", "id", saved.path("id").asText()));
        } catch (RuntimeException error) {
            // 日报失败不回滚此前已独立提交的预测/交易；错误留在任务结果及审计中，可单独重生成。
            report.put(
                    "research_report",
                    Map.of("status", "FAILED", "error_code", error.getClass().getSimpleName()));
            store.audit(
                    "RESEARCH_REPORT_FAILED",
                    Map.of("trade_date", day, "error_code", error.getClass().getSimpleName()));
        }
        return report;
    }
}
