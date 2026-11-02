package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import cn.etfquantlab.paper.PaperService;
import cn.etfquantlab.scheduling.TaskService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "etf.worker-delay=3600000")
class PersistenceTest {
    @Autowired private Store store;
    @Autowired private PaperService paper;
    @Autowired private TaskService tasks;
    @MockitoBean private QuantClient quant;

    /** 所有模拟写入只允许发生在专属测试库。 */
    @BeforeEach
    void isolated() {
        assertThat(store.db.queryForObject("select current_database()", String.class))
                .isEqualTo("etf_quant_lab_test");
    }

    /** 两个并发结算请求仅产生一次现金变化、一条订单、一笔成交和一个快照。 */
    @Test
    void concurrentPaperSettlementIsAtomicAndIdempotent() throws Exception {
        String account = UUID.randomUUID().toString();
        String strategy = "TEST_" + account.substring(0, 8);
        store.db.update(
                "insert into strategy_config(id,name,category,enabled,parameters) values(?,?,'TEST',true,'{}')",
                strategy,
                strategy);
        String state =
                """
                {"cash":"100000.00","initial_capital":"100000.00","equity":"100000.00",
                 "peak_equity":"100000.00","locked":false,"lots":{},"receivables":[],
                 "last_prices":{},"last_trade_date":null,"cumulative_fees":"0.00","cumulative_dividends":"0.00"}
                """;
        store.db.update(
                "insert into paper_account(id,strategy_id,initial_capital,cash,equity,peak_equity,state)"
                        + " values(?,?,100000,100000,100000,100000,?::jsonb)",
                account,
                strategy,
                state);
        for (String symbol : new String[] {"510300", "510500", "159915"}) {
            for (String day : new String[] {"2024-01-04", "2024-01-05"}) {
                store.db.update(
                        "insert into market_bar(id,symbol,trade_date,open,high,low,close,volume,amount,"
                                + "price_basis,source,nature,fetched_at,quality_status,raw_values)"
                                + " values(?,?,?::date,10,11,9,10,1000000,100000"
                                + "00,'RAW','ISOLATED_TEST','REAL',now(),'VALID'"
                                + ",'{}')",
                        UUID.randomUUID(),
                        symbol,
                        day);
            }
            String details = store.encode(Map.of("symbol", symbol, "target_weight", "0.2"));
            store.db.update(
                    "insert into prediction(id,business_key,symbol,strategy_id,horizon,signal_date,"
                            + "input_cutoff,created_at,model_version,nature,direction,details)"
                            + " values(?,?,?,?,1,'2024-01-04','2024-01-04T08"
                            + ":00Z','2024-01-04T09:00Z','TEST','REAL','UP',"
                            + "?::jsonb)",
                    UUID.randomUUID(),
                    account + symbol,
                    symbol,
                    strategy,
                    details);
        }
        store.db.update(
                "insert into trading_calendar(trade_date,source) values('2024-01-04','TEST') on conflict do nothing");
        store.db.update(
                "insert into trading_calendar(trade_date,source) values('2024-01-05','TEST') on conflict do nothing");
        String result =
                """
                {"state":{"cash":"95995.00","equity":"99995.00","peak_equity":"100000.00","locked":false,
                "last_trade_date":"2024-01-05","lots":{"510300":[{"quantity":"400","cost":"4005.00",
                "acquired":"2024-01-05"}]}},
                "orders":[{"business_key":"TEST_ORDER","symbol":"510300","side":"BUY","status":"FILLED"}],
                "fills":[{"business_key":"TEST_ORDER","quantity":"400","price":"10.000","fee":"5.00"}],
                "snapshot":{"equity":"99995.00","cash":"95995.00"},"risk_events":[]}
                """;
        when(quant.post(eq("/paper/step"), any())).thenReturn(store.decode(result));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> paper.settleAccount(account, LocalDate.of(2024, 1, 5)));
            var second = pool.submit(() -> paper.settleAccount(account, LocalDate.of(2024, 1, 5)));
            assertThat(java.util.List.of(first.get().get("status"), second.get().get("status")))
                    .containsExactlyInAnyOrder("SETTLED", "ALREADY_PROCESSED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        store.db.queryForObject(
                                "select cash from paper_account where id=?",
                                BigDecimal.class,
                                account))
                .isEqualByComparingTo("95995.00");
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from paper_order where account_id=?",
                                Integer.class,
                                account))
                .isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from account_snapshot where account_id=?",
                                Integer.class,
                                account))
                .isEqualTo(1);
        assertThat(
                        store.db.queryForObject(
                                "select count(*) from paper_fill f join paper_order o on o.id=f.order_id"
                                        + " where o.account_id=?",
                                Integer.class,
                                account))
                .isEqualTo(1);
    }

    /** 模拟服务重启后恢复过期运行租约，重复提交同一业务键仍指向同一任务。 */
    @Test
    void persistentJobLeaseRecoversWithoutDuplicateEnqueue() {
        String key = UUID.randomUUID().toString();
        var payload = store.decode("{\"symbol\":\"510300\",\"provider\":\"DEMO\"}");
        var first = tasks.enqueue("MARKET_SYNC", payload, key);
        assertThat(tasks.enqueue("MARKET_SYNC", payload, key).get("id")).isEqualTo(first.get("id"));
        store.db.update(
                "update task_execution set status='RUNNING',updated_at='2000-01-01T00:00Z' where id=?",
                first.get("id"));
        when(quant.post(eq("/market/fetch"), any()))
                .thenReturn(
                        store.decode("{\"bars\":[],\"report\":{\"count\":0,\"nature\":\"DEMO\"}}"));
        tasks.consume();
        assertThat(
                        store.db.queryForObject(
                                "select status from task_execution where id=?",
                                String.class,
                                first.get("id")))
                .isEqualTo("SUCCEEDED");
    }
}
