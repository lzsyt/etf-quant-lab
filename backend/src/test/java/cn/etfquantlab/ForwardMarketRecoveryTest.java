package cn.etfquantlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardRules;
import cn.etfquantlab.forward.ForwardService;
import cn.etfquantlab.market.MarketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ForwardMarketRecoveryTest {
    private final LocalDate day = LocalDate.of(2026, 10, 26);
    private final Store store =
            new Store(mock(JdbcTemplate.class), new ObjectMapper().findAndRegisterModules());
    private final MarketService market = mock(MarketService.class);
    private final ForwardService forward =
            new ForwardService(store, market, null, null, null, null, null, null, true);
    private final Map<String, List<JsonNode>> saved = new HashMap<>();

    /** 主源本日已完整时不增加备用请求，保留三个真实标的身份。 */
    @Test
    void completePrimaryDoesNotFetchBackup() {
        prepareReads();
        when(market.sync(any()))
                .thenAnswer(
                        call -> {
                            Map<String, Object> request = call.getArgument(0);
                            assertThat(request.get("provider")).isEqualTo("SINA");
                            save(request.get("symbol").toString());
                            return store.json.createObjectNode();
                        });
        assertThat(forward.prepareMarket(day, false)).containsEntry("count", 3);
        assertThat(requests()).hasSize(3);
    }

    /** 部分缺日仅补对应ETF的当天RAW行情，不向备用源请求旧日期。 */
    @Test
    void missingSymbolFetchesOnlyExactDayFromBackup() {
        prepareReads();
        when(market.sync(any()))
                .thenAnswer(
                        call -> {
                            Map<String, Object> request = call.getArgument(0);
                            String symbol = request.get("symbol").toString();
                            if (!"159915".equals(symbol)
                                    || "TENCENT".equals(request.get("provider"))) {
                                save(symbol);
                            }
                            return store.json.createObjectNode();
                        });
        assertThat(forward.prepareMarket(day, false)).containsEntry("missing_symbols", List.of());
        assertThat(requests().stream().filter(r -> "TENCENT".equals(r.get("provider"))).toList())
                .containsExactly(
                        Map.of(
                                "symbol",
                                "159915",
                                "start",
                                "2026-10-26",
                                "end",
                                "2026-10-26",
                                "provider",
                                "TENCENT",
                                "price_basis",
                                "RAW"));
    }

    /** 主源超时也可由真实备用源恢复，外部异常中的敏感原文不能进入结果。 */
    @Test
    void primaryFailureUsesBackupWithoutExposingExceptionText() {
        prepareReads();
        when(market.sync(any()))
                .thenAnswer(
                        call -> {
                            Map<String, Object> request = call.getArgument(0);
                            if ("SINA".equals(request.get("provider"))) {
                                throw new IllegalArgumentException("SECRET_FIXTURE_MUST_NOT_LEAK");
                            }
                            save(request.get("symbol").toString());
                            return store.json.createObjectNode();
                        });
        Map<String, Object> result = forward.prepareMarket(day, false);
        assertThat(result).containsEntry("count", 3);
        assertThat(store.encode(result)).doesNotContain("SECRET_FIXTURE");
        assertThat(requests()).hasSize(6);
    }

    /** 两源均未取得合格日线时，保持阻断并给出确切日期与缺失标的。 */
    @Test
    void backupSuccessResponseWithoutValidBarStillBlocks() {
        prepareReads();
        save("510300");
        when(market.sync(any())).thenReturn(store.json.createObjectNode().put("count", 0));
        assertThatThrownBy(() -> forward.prepareMarket(day, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-10-26")
                .hasMessageContaining("510500、159915")
                .hasMessageNotContaining("510300")
                .hasMessageContaining("已停止结算与预测");
    }

    /** 两源异常不能伪造行情或放宽完整性判断。 */
    @Test
    void bothSourcesFailWithoutCreatingSyntheticBars() {
        prepareReads();
        when(market.sync(any())).thenThrow(new IllegalStateException("NETWORK_FIXTURE"));
        assertThatThrownBy(() -> forward.prepareMarket(day, false))
                .hasMessageContaining("510300、510500、159915");
        assertThat(saved).isEmpty();
    }

    /** 已有通过正式读取校验的本日数据时，主源临时失败不覆盖它或无谓访问备用源。 */
    @Test
    void verifiedExistingDaySurvivesPrimaryFailure() {
        prepareReads();
        ForwardRules.SYMBOLS.forEach(this::save);
        when(market.sync(any())).thenThrow(new IllegalStateException("NETWORK_FIXTURE"));
        assertThat(forward.prepareMarket(day, false)).containsEntry("count", 3);
        assertThat(requests()).hasSize(3);
    }

    /** 初始化沿用原有只读行情行为，不因引入补偿而触发外部请求。 */
    @Test
    void initializationDoesNotFetchEitherSource() {
        prepareReads();
        ForwardRules.SYMBOLS.forEach(this::save);
        assertThat(forward.prepareMarket(day, true)).containsEntry("count", 3);
        verify(market, never()).sync(any());
    }

    /** 模拟正式按日期和真实性筛选后的持久数据，成功HTTP报告本身不能代表已入库。 */
    private void prepareReads() {
        for (String symbol : ForwardRules.SYMBOLS) {
            when(market.bars(symbol, day, day, "REAL", "RAW"))
                    .thenAnswer(call -> saved.getOrDefault(symbol, List.of()));
        }
    }

    /** 测试夹具只存在内存，不生成市场数据或访问外部源。 */
    private void save(String symbol) {
        saved.put(symbol, List.of(store.json.createObjectNode().put("symbol", symbol)));
    }

    /** 核对实际发出的请求范围，防止备用源重新抓取或修订完整历史。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> requests() {
        ArgumentCaptor<Map<String, Object>> capture = ArgumentCaptor.forClass(Map.class);
        verify(market, org.mockito.Mockito.atLeastOnce()).sync(capture.capture());
        return capture.getAllValues();
    }
}
