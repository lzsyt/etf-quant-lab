package cn.etfquantlab.forecast;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.etfquantlab.api.Store;
import cn.etfquantlab.forward.ForwardModels;
import cn.etfquantlab.fund.FundResearchService;
import cn.etfquantlab.gold.GoldService;
import cn.etfquantlab.market.MarketService;
import cn.etfquantlab.prediction.PredictionService;
import cn.etfquantlab.strategy.ResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 目标协议决定准备能力，防止统一界面误送旧ETF训练或扩大到其他资产。 */
class ForecastPreparationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Store store = new Store(null, json);
    private final FundResearchService funds = mock(FundResearchService.class);
    private final GoldService gold = mock(GoldService.class);
    private final MarketService market = mock(MarketService.class);
    private final PredictionService dates = mock(PredictionService.class);
    private final ForwardModels forward = mock(ForwardModels.class);
    private final ResearchService legacy = mock(ResearchService.class);
    private final CloseModels close = mock(CloseModels.class);
    private final ForecastRunService runs =
            new ForecastRunService(store, null, null, null, funds, gold, market, false);
    private final ForecastPreparation preparation =
            new ForecastPreparation(
                    runs, store, funds, gold, market, dates, forward, legacy, close);

    /** 前瞻H1只进入当前ETF前瞻模型训练，不能转入旧收盘模型或整个模拟流程。 */
    @Test
    void forwardTrainingIsScopedToSelectedAssetAndCorrectLabelProtocol() {
        LocalDate day = LocalDate.of(2026, 10, 28);
        when(dates.latestExpected(any())).thenReturn(day);
        when(forward.ensure("510500", day)).thenReturn(List.of());
        preparation.execute(
                json.valueToTree(
                        Map.of(
                                "asset",
                                "510500",
                                "target",
                                "ETF_FORWARD_OPEN",
                                "horizon",
                                1,
                                "action",
                                "TRAIN",
                                "mode",
                                "REAL")));
        verify(forward).ensure("510500", day);
        verifyNoInteractions(legacy, funds, gold, market);
    }

    /** 基金准备依资产执行原生20/60训练，不偷换成ETF期限。 */
    @Test
    void fundTrainingKeepsFundIdentity() {
        when(funds.train("007169")).thenReturn(json.createObjectNode());
        preparation.execute(
                json.valueToTree(
                        Map.of(
                                "asset",
                                "007169",
                                "target",
                                "FUND_NAV_CHANGE",
                                "horizon",
                                60,
                                "action",
                                "TRAIN",
                                "mode",
                                "REAL")));
        verify(funds).train("007169");
        verifyNoInteractions(legacy, forward, gold, market);
    }

    /** 新中期目标只训练该ETF所选期限，不误用旧分类API。 */
    @Test
    void closeTrainingUsesNewContractAndSelectedHorizon() {
        LocalDate day = LocalDate.of(2026, 10, 30);
        when(dates.latestExpected(any())).thenReturn(day);
        when(close.ensure("159915", 60, day)).thenReturn(List.of());
        preparation.execute(
                json.valueToTree(
                        Map.of(
                                "asset",
                                "159915",
                                "target",
                                "ETF_CLOSE_RETURN",
                                "horizon",
                                60,
                                "action",
                                "TRAIN",
                                "mode",
                                "REAL")));
        verify(close).ensure("159915", 60, day);
        verifyNoInteractions(legacy, forward, funds, gold, market);
    }

    /** 基金短期准备仍复用本基金净值训练，不创建另一套采集与调度。 */
    @Test
    void shortFundTrainingUsesFundPipeline() {
        when(funds.train("007169")).thenReturn(json.createObjectNode());
        preparation.execute(
                json.valueToTree(
                        Map.of(
                                "asset",
                                "007169",
                                "target",
                                "FUND_NAV_UP",
                                "horizon",
                                5,
                                "action",
                                "TRAIN",
                                "mode",
                                "REAL")));
        verify(funds).train("007169");
        verifyNoInteractions(legacy, forward, close, gold, market);
    }

    /** 全量、演示和跨资产目标在排队前即拒绝，避免默认扩大写入范围。 */
    @Test
    void rejectsImplicitBatchDemoAndCrossDomainTargets() {
        for (var request :
                List.of(
                        Map.of("action", "TRAIN"),
                        Map.of(
                                "asset",
                                "510300",
                                "target",
                                "ETF_LEGACY_CLOSE",
                                "horizon",
                                1,
                                "action",
                                "TRAIN",
                                "mode",
                                "DEMO"),
                        Map.of(
                                "asset",
                                "460300",
                                "target",
                                "ETF_FORWARD_OPEN",
                                "horizon",
                                1,
                                "action",
                                "TRAIN",
                                "mode",
                                "REAL"))) {
            assertThatThrownBy(() -> preparation.validate(json.valueToTree(request)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(funds, gold, market, dates, forward, legacy);
    }
}
