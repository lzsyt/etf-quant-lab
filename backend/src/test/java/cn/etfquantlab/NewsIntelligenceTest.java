package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardNews;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.AnalysisService;
import cn.etfquantlab.prediction.IntelligenceController;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.research.ResearchReportService;
import cn.etfquantlab.scheduling.DailyService;
import cn.etfquantlab.scheduling.TaskService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "etf.worker-delay=3600000")
class NewsIntelligenceTest {
    @Autowired private Store store;
    @Autowired private AnalysisService news;
    @Autowired private IntelligenceController intelligence;
    @Autowired private TransactionTemplate tx;
    @Autowired private TaskService tasks;
    @Autowired private ApplicationContext context;
    @MockitoBean private QuantClient quant;

    /** 新闻、费用及账户夹具只允许存在隔离库，外部采集和模型均使用Mock。 */
    @BeforeEach
    void isolatedDatabaseOnly() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 重抓不改首次获取时间、原行或增强证据；同URL内容修订形成独立版本。 */
    @Test
    void repeatedCollectionAndRevisionPreserveSnapshotsAndTradingHistory() {
        Map<String, String> protectedBefore = protectedHistory();
        ObjectNode original = item("SOURCE_" + UUID.randomUUID(), Instant.now().minusSeconds(60));
        collect(List.of(original));
        String id = original.path("id").asText();
        String before = row("news_item", id);
        String metadata = metadata(id);
        ObjectNode duplicate = original.deepCopy();
        duplicate.put("fetched_at", Instant.now().toString());
        ((ObjectNode) duplicate.path("metadata")).put("importance_score", 99);
        collect(List.of(duplicate));
        assertThat(row("news_item", id)).isEqualTo(before);
        assertThat(metadata(id)).isEqualTo(metadata);

        ObjectNode revised = original.deepCopy();
        revised.put("id", UUID.randomUUID().toString());
        revised.put("summary", "同一篇公开资料的修订摘要，隔离样本。");
        revised.put("content_sha256", "b".repeat(64));
        collect(List.of(revised));
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from news_item where source_url=?",
                                Integer.class,
                                original.path("source_url").asText()))
                .isEqualTo(2);
        assertThat(row("news_item", id)).isEqualTo(before);
        assertThat(protectedHistory()).isEqualTo(protectedBefore);
    }

    /** 新候选上限独立于模型文章预算；发布时间、首次获取时间和核验状态都必须合格。 */
    @Test
    void candidatesHaveBoundedVolumeAndRejectFutureAndLateInformation() {
        Instant cutoff = Instant.parse("2031-08-18T09:00:00Z");
        String source = "CANDIDATE_" + UUID.randomUUID();
        List<ObjectNode> items = new ArrayList<>();
        for (int index = 0; index < 155; index++) {
            items.add(item(source, cutoff.minusSeconds(1000 + index)));
        }
        ObjectNode future = item(source, cutoff.minusSeconds(1));
        future.put("published_at", cutoff.plusSeconds(1).toString());
        ObjectNode late = item(source, cutoff.minusSeconds(1));
        late.put("fetched_at", cutoff.plusSeconds(1).toString());
        ObjectNode unverified = item(source, cutoff.minusSeconds(1));
        unverified.put("publication_verified", false);
        items.addAll(List.of(future, late, unverified));
        collect(items);
        List<JsonNode> candidates = news.candidates(cutoff);
        assertThat(candidates).hasSize(150);
        assertThat(news.eligible(cutoff)).hasSize(15);
        assertThat(candidates)
                .allMatch(
                        node ->
                                node.path("metadata")
                                        .path("category")
                                        .asText()
                                        .equals("MONETARY_POLICY"));
        assertThat(candidates.stream().map(node -> node.path("id").asText()).toList())
                .doesNotContain(
                        future.path("id").asText(),
                        late.path("id").asText(),
                        unverified.path("id").asText());
    }

    /** 同请求并发复用；新提示词受当日门禁时返回当前缺失，不能冒充旧分析。 */
    @Test
    void changedVersionGetsCurrentMissingInsteadOfOldResult() throws Exception {
        Instant cutoff = uniqueDay();
        mockQuote("v2.0", true);
        when(quant.post(eq("/analysis/forward/run"), any())).thenReturn(response("v2.0", "REAL"));
        ForwardNews forward = forwardNews();
        var pool = Executors.newFixedThreadPool(2);
        JsonNode first;
        try {
            var one = pool.submit(() -> forward.analyze(cutoff, true));
            var two = pool.submit(() -> forward.analyze(cutoff, true));
            first = one.get();
            assertThat(two.get().path("id")).isEqualTo(first.path("id"));
        } finally {
            pool.shutdownNow();
        }
        mockQuote("v2.1", true);
        JsonNode upgraded = forwardNews().analyze(cutoff.plusSeconds(60), true);
        assertThat(upgraded.path("id")).isNotEqualTo(first.path("id"));
        assertThat(upgraded.path("quote").path("prompt_version").asText()).isEqualTo("v2.1");
        assertThat(upgraded.path("error_code").asText())
                .isEqualTo("DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED");
        assertThat(upgraded.path("prior_analysis_id")).isEqualTo(first.path("id"));
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 收费是否发生未知时保留预留；更换提示词或重试不能绕过未知费用再次调用。 */
    @Test
    void failedCallKeepsReservationAndCannotBeRebilled() {
        Instant cutoff = uniqueDay();
        mockQuote("v2.0", true);
        when(quant.post(eq("/analysis/forward/run"), any()))
                .thenThrow(new IllegalStateException("ISOLATED_TIMEOUT"));
        JsonNode failed = forwardNews().analyze(cutoff, true);
        assertThat(failed.path("error_code").asText()).isEqualTo("API_FAILED");
        mockQuote("v2.1", true);
        JsonNode retried = forwardNews().analyze(cutoff, true);
        assertThat(retried.path("id")).isNotEqualTo(failed.path("id"));
        assertThat(retried.path("status").asText()).isEqualTo("MISSING");
        assertThat(retried.path("validation_outcome").asText()).isEqualTo("NOT_VALIDATED");
        assertThat(
                        store.db.queryForObject(
                                "select status from api_call_cost where id=?",
                                String.class,
                                UUID.fromString(failed.path("id").asText())))
                .isEqualTo("RESERVED");
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
    }

    /** 无新闻不预留、不收费；初始化关闭收费也不能被采集任务间接触发。 */
    @Test
    void noRelevantNewsAndCollectionTaskNeverCallModel() {
        mockQuote("v2.1", false);
        JsonNode result = forwardNews().analyze(uniqueDay(), true);
        assertThat(result.path("error_code").asText()).isEqualTo("NO_RELEVANT_NEWS");
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from api_call_cost where id=?",
                                Integer.class,
                                UUID.fromString(result.path("id").asText())))
                .isZero();
        String key = UUID.randomUUID().toString();
        var task = tasks.enqueue("NEWS_COLLECT", store.decode("{}"), key);
        assertThat(tasks.enqueue("NEWS_COLLECT", store.decode("{}"), key).get("id"))
                .isEqualTo(task.get("id"));
        verify(quant, never()).post(eq("/analysis/forward/run"), any());
    }

    /** 两个不同人工验证任务只分析一次，不采集、不训练、不冻结，也不改变四账户。 */
    @Test
    @Transactional
    void liveValidationTasksOnlyAnalyzeAndReuseDailyPaidRecord() {
        Map<String, String> protectedBefore = protectedHistory();
        mockQuote("v2.1-task-test", true);
        when(quant.post(eq("/analysis/forward/run"), any()))
                .thenReturn(response("v2.1-task-test", "MOCK"));
        TaskService worker =
                new TaskService(
                        store,
                        context.getBean(MarketService.class),
                        context.getBean(ResearchService.class),
                        tx,
                        context.getBean(DailyService.class),
                        context.getBean(ResearchReportService.class),
                        context.getBean(PredictionService.class),
                        context.getBean(ForwardService.class),
                        news,
                        forwardNews());
        try {
            String analysisId = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                UUID id =
                        (UUID)
                                worker.enqueue(
                                                "NEWS_ANALYZE",
                                                store.decode("{}"),
                                                UUID.randomUUID().toString())
                                        .get("id");
                // 只将本项新夹具排到隔离队列首位，不消费其他测试遗留的任务。
                store.db.update(
                        "update task_execution set updated_at=(select min(updated_at)"
                                + "-interval '1 day' from task_execution) where id=?",
                        id);
                worker.consume();
                JsonNode task =
                        store.db.queryForObject(
                                "select row_to_json(t)::text from task_execution t where id=?",
                                (rs, row) -> store.decode(rs.getString(1)),
                                id);
                assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
                JsonNode result = task.path("result");
                assertThat(result.path("mode").asText()).isEqualTo("LIVE_VALIDATION_ONLY");
                assertThat(result.path("decisions_generated").asBoolean()).isFalse();
                if (analysisId == null) {
                    analysisId = result.path("analysis").path("id").asText();
                } else {
                    assertThat(result.path("analysis").path("id").asText()).isEqualTo(analysisId);
                }
            }
        } finally {
            worker.closeHeartbeat();
        }
        verify(quant, times(2)).post(eq("/analysis/forward/quote"), any());
        verify(quant, times(1)).post(eq("/analysis/forward/run"), any());
        verifyNoMoreInteractions(quant);
        assertThat(protectedHistory()).isEqualTo(protectedBefore);
    }

    /** 新闻筛选采用北京时间自然日，页面不返回内部全文，旧无增强信息记录仍可读。 */
    @Test
    void intelligenceFiltersAndRedactsContentWithoutWritingHistory() {
        String source = "FILTER_" + UUID.randomUUID();
        ObjectNode selected = item(source, Instant.parse("2026-10-24T16:30:00Z"));
        ObjectNode otherDate = item(source, Instant.parse("2026-10-24T15:30:00Z"));
        collect(List.of(selected, otherDate));
        Map<String, String> before = protectedHistory();
        var result =
                intelligence.overview(
                        source,
                        "510300",
                        "2026-10-25",
                        "2026-10-25",
                        "MONETARY_POLICY",
                        "RELEVANT",
                        "high",
                        100,
                        0);
        JsonNode json = store.json.valueToTree(result);
        assertThat(json.path("news_total").asInt()).isEqualTo(1);
        assertThat(json.path("news").get(0).path("id")).isEqualTo(selected.path("id"));
        assertThat(json.path("news").get(0).path("metadata").has("content")).isFalse();
        assertThat(json.path("news").get(0).path("content_type").asText()).isEqualTo("SUMMARY");
        assertThat(protectedHistory()).isEqualTo(before);
        assertThatThrownBy(() -> intelligence.overview(source, "", "invalid", "", "", "", "", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        store.json
                                .valueToTree(
                                        intelligence.overview(
                                                "' OR true --", "", "", "", "", "", "", 1, 0))
                                .path("news_total")
                                .asInt())
                .isZero();
    }

    /** 每个来源失败后仍能展示最近成功时刻；整体采集失败独立报告而非伪造空新闻。 */
    @Test
    void sourceFailurePreservesLastSuccessAndCollectorFailureIsVisible() {
        String source = "MONITOR_" + UUID.randomUUID();
        Instant success =
                Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        audit(source, "PARTIAL", success);
        audit(source, "FAILED", success.plusSeconds(30));
        when(quant.post(eq("/news/collect"), any()))
                .thenThrow(new IllegalStateException("ISOLATED_COLLECTOR_UNAVAILABLE"));
        assertThatThrownBy(news::collect).isInstanceOf(IllegalStateException.class);
        JsonNode view = view();
        JsonNode found = find(view.path("sources"), "source", source);
        assertThat(found.path("status").asText()).isEqualTo("FAILED");
        assertThat(Instant.parse(found.path("last_success_at").asText()))
                .isEqualTo(success.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(
                        view.path("collection_failure")
                                .get(0)
                                .path("details")
                                .path("error_code")
                                .asText())
                .isEqualTo("IllegalStateException");
    }

    /** 被纯ML或回退决策引用不等于参与融合；只认实际FUSION模式且新闻有效的冻结决策。 */
    @Test
    void participationRequiresEffectiveFusionAndRealDecisionEvidence() {
        UUID analysis = UUID.randomUUID();
        store.db.update(
                "insert into forward_analysis(id,business_key,input_cutoff,status,trace_id,quote,"
                        + "input_snapshot,result) values(?,?,now(),'SUCCEEDED','ISOLATED','{}',"
                        + "'{\"news\":[{\"metadata\":{\"content\":\"INTERNAL_FULL_TEXT\"}}]}',"
                        + "'{\"nature\":\"MOCK\",\"input_text\":\"INTERNAL_PROMPT\",\"result\":{\"items\":[]}}')",
                analysis,
                "TEST_" + analysis);
        decision(analysis, "LGBM_BASE", "ML", true);
        decision(analysis, "FUSION_EXPERIMENT", "ML_FALLBACK", false);
        JsonNode before = find(view().path("analyses"), "id", analysis.toString());
        assertThat(before.path("participated_in_fusion").asBoolean()).isFalse();
        assertThat(before.path("nature").asText()).isEqualTo("MOCK");
        assertThat(before.toString()).doesNotContain("INTERNAL_FULL_TEXT", "INTERNAL_PROMPT");
        decision(analysis, "FUSION_EXPERIMENT", "FUSION", true);
        JsonNode after = find(view().path("analyses"), "id", analysis.toString());
        assertThat(after.path("participated_in_fusion").asBoolean()).isFalse();
        assertThat(after.path("usage")).hasSize(3);
        UUID real = UUID.randomUUID();
        store.db.update(
                "insert into forward_analysis(id,business_key,input_cutoff,status,trace_id,quote,"
                        + "input_snapshot,result) values(?,?,now(),'SUCCEEDED','ISOLATED','{}','{}',"
                        + "'{\"nature\":\"REAL\",\"result\":{\"items\":[]}}')",
                real,
                "TEST_" + real);
        decision(real, "FUSION_EXPERIMENT", "FUSION", true);
        assertThat(
                        find(view().path("analyses"), "id", real.toString())
                                .path("participated_in_fusion")
                                .asBoolean())
                .isTrue();
    }

    /** 构造带允许展示摘要和内部正文的隔离新闻，避免访问任何真实来源。 */
    private ObjectNode item(String source, Instant timestamp) {
        ObjectNode item = store.json.createObjectNode();
        String id = UUID.randomUUID().toString();
        item.put("id", id);
        item.put("title", "货币政策隔离测试");
        item.put("summary", "可展示的隔离新闻摘要。");
        item.put("source_url", "https://www.pbc.gov.cn/isolated/" + id);
        item.put("source_name", source);
        item.put("published_at", timestamp.toString());
        item.put("fetched_at", timestamp.toString());
        item.put("nature", "REAL");
        item.put("publication_verified", true);
        item.put("content_sha256", "a".repeat(64));
        ObjectNode metadata = item.putObject("metadata");
        metadata.put("content", "INTERNAL_FULL_TEXT");
        metadata.put("content_type", "SUMMARY");
        metadata.put("category", "MONETARY_POLICY");
        metadata.putArray("related_symbols").add("510300");
        metadata.put("relevance_status", "RELEVANT");
        metadata.put("relevance_reason", "隔离关键词命中");
        metadata.put("importance_score", 80);
        metadata.put("importance_level", "high");
        return item;
    }

    /** 采集只使用Mockito返回，不发起HTTP或付费请求。 */
    private void collect(List<ObjectNode> items) {
        when(quant.post(eq("/news/collect"), any()))
                .thenReturn(store.json.valueToTree(Map.of("items", items, "sources", List.of())));
        news.collect();
    }

    /** 返回无秘密的新闻整行内容，用于核对旧快照没有被重新写入。 */
    private String row(String table, String id) {
        return store.db.queryForObject(
                "select row_to_json(n)::text from " + table + " n where id=?", String.class, id);
    }

    /** 独立增强表首次值固定，不随重复抓取更新。 */
    private String metadata(String id) {
        return store.db.queryForObject(
                "select details::text from news_item_metadata where news_id=?", String.class, id);
    }

    /** 固定白名单表的整行摘要确保新闻操作未改变模型、账户、分析或冻结记录。 */
    private Map<String, String> protectedHistory() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table :
                List.of(
                        "prediction",
                        "llm_analysis",
                        "forward_decision",
                        "forward_plan",
                        "paper_account",
                        "paper_order",
                        "paper_fill",
                        "forward_account",
                        "forward_actual",
                        "forward_settlement",
                        "model_version",
                        "forward_model")) {
            result.put(
                    table,
                    store.db.queryForObject(
                            "select md5(coalesce(string_agg(to_jsonb(t)::text,"
                                    + "'' order by to_jsonb(t)::text),'')) from "
                                    + table
                                    + " t",
                            String.class));
        }
        return result;
    }

    /** 每次测试选独立自然日，重跑不受既有费用夹具影响。 */
    private Instant uniqueDay() {
        return LocalDate.of(2040, 1, 1)
                .plusDays(Math.floorMod(UUID.randomUUID().hashCode(), 150000))
                .atTime(12, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    }

    /** 报价仅提供可控协议，费用始终由隔离数据库进行预留。 */
    private void mockQuote(String version, boolean relevant) {
        ObjectNode quote = store.json.createObjectNode();
        quote.put("prompt_version", version);
        quote.put("schema_version", "TEST_SCHEMA");
        quote.put("provider", "deepseek");
        quote.put("model", "TEST_MODEL");
        quote.put("model_version", "TEST_MODEL_VERSION");
        quote.put(
                "request_fingerprint",
                String.format("%064x", Integer.toUnsignedLong((version + relevant).hashCode())));
        quote.put("input_sha256", "a".repeat(64));
        quote.put("reserved_usd", "0.01");
        quote.putObject("pricing");
        quote.put("enabled", true);
        quote.put("configured", true);
        if (relevant) {
            quote.putArray("eligible_source_ids").add("TEST");
        } else {
            quote.putArray("eligible_source_ids");
        }
        when(quant.post(eq("/analysis/forward/quote"), any())).thenReturn(quote);
    }

    /** 返回与报价身份一致的隔离响应，避免测试将缺失协议字段当成成功。 */
    private JsonNode response(String version, String nature) {
        ObjectNode response = store.json.createObjectNode();
        response.put("status", "SUCCEEDED");
        response.put("nature", nature);
        response.put("prompt_version", version);
        response.put("schema_version", "TEST_SCHEMA");
        response.put("request_model", "TEST_MODEL");
        response.put("model_version", "TEST_MODEL_VERSION");
        response.put(
                "request_fingerprint",
                String.format("%064x", Integer.toUnsignedLong((version + true).hashCode())));
        response.put("input_sha256", "a".repeat(64));
        response.put("billing_uncertain", false);
        response.put("cost_usd", "0.001");
        response.put("input_tokens", 100);
        response.put("output_tokens", 30);
        response.putObject("result").putArray("items");
        return response;
    }

    /** 重建服务对象验证幂等来自持久记录，不依赖进程内缓存。 */
    private ForwardNews forwardNews() {
        return new ForwardNews(store, quant, news, tx, BigDecimal.ONE, true);
    }

    /** 直接读取控制器，避免新闻查看测试触发任何工作队列。 */
    private JsonNode view() {
        return store.json.valueToTree(intelligence.overview("", "", "", "", "", "", "", 100, 0));
    }

    /** 从有界投影查找本项隔离夹具，未找到即失败。 */
    private JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("未找到隔离记录 " + value);
    }

    /** 追加有明确时间的来源审计，不更新旧来源记录。 */
    private void audit(String source, String status, Instant timestamp) {
        store.db.update(
                "insert into operation_audit(id,action,details,created_at) values(?,'NEWS_COLLECT',?::jsonb,?)",
                UUID.randomUUID(),
                store.encode(
                        List.of(
                                Map.of(
                                        "source",
                                        source,
                                        "status",
                                        status,
                                        "count",
                                        List.of("OK", "PARTIAL").contains(status) ? 2 : 0))),
                Timestamp.from(timestamp));
    }

    /** 独立实验夹具只用于验证关联，不调用撮合引擎或真实账户。 */
    private void decision(UUID analysis, String strategy, String mode, boolean available) {
        String experiment = "TEST_" + UUID.randomUUID();
        store.db.update(
                "insert into forward_experiment(id,strategy_version,protocol_version,label_version,"
                        + "nature,evaluation_type,start_date,configuration)"
                        + " values(?,'TEST','TEST','TEST','REAL','FORWARD_PAPER',current_date,'{}')",
                experiment);
        store.db.update(
                "insert into forward_account(id,experiment_id,strategy,initial_capital,state)"
                        + " values(?,?,?,100000,'{}')",
                experiment,
                experiment,
                strategy);
        UUID plan = UUID.randomUUID();
        store.db.update(
                "insert into forward_plan(id,experiment_id,account_id,entry_date,generated_at,details)"
                        + " values(?,?,?,current_date,now(),'{}')",
                plan,
                experiment,
                experiment);
        store.db.update(
                "insert into forward_decision(id,experiment_id,account_id,plan_id,symbol,signal_date,"
                        + "entry_date,evaluation_date,generated_at,details)"
                        + " values(?,?,?,?,'510300',current_date,current_date,current_date,now(),?::jsonb)",
                UUID.randomUUID(),
                experiment,
                experiment,
                plan,
                store.encode(
                        Map.of(
                                "analysis_id",
                                analysis.toString(),
                                "strategy",
                                strategy,
                                "effective_mode",
                                mode,
                                "news_available",
                                available)));
    }
}
