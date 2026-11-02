package cn.etfquantlab.market;

import cn.etfquantlab.api.QuantClient;
import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketService market;
    private final Store store;
    private final QuantClient quant;

    /** 行情管理只面向已认证的管理员。 */
    public MarketController(MarketService market, Store store, QuantClient quant) {
        this.market = market;
        this.store = store;
        this.quant = quant;
    }

    /** 返回经过核对的品种和交易规则。 */
    @GetMapping("/instruments")
    public List<Map<String, Object>> instruments() {
        return store.db.queryForList("select * from etf_instrument order by symbol");
    }

    /** 按日期、数据性质和价格口径查询历史行情。 */
    @GetMapping("/bars")
    public List<JsonNode> bars(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "2021-01-01") LocalDate start,
            @RequestParam(defaultValue = "2099-01-01") LocalDate end,
            @RequestParam(defaultValue = "REAL") String nature,
            @RequestParam(defaultValue = "RAW") String basis) {
        return market.bars(symbol, start, end, nature, basis);
    }

    /** 抓取任务保留状态和来源报告，允许用户显式切换数据提供商。 */
    @PostMapping("/sync")
    public JsonNode sync(@RequestBody Map<String, Object> request) {
        return market.sync(request);
    }

    /** 展示最近同步成功或失败的真实记录。 */
    @GetMapping("/quality")
    public List<JsonNode> quality() {
        return store.db.query(
                "select row_to_json(s)::text from (select * from market_sync"
                        + " order by started_at desc limit 100) s",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 当前真实行情、元数据、缺失交易日与跨源差异的只读质量报告。 */
    @GetMapping("/quality-report")
    public Map<String, Object> qualityReport() {
        return market.qualityReport();
    }

    /** 管理端主动刷新可信交易日历。 */
    @PostMapping("/calendar/sync")
    public JsonNode calendar() {
        return market.syncCalendar();
    }

    /** 技术指标与页面使用完全相同的行情口径。 */
    @GetMapping("/indicators")
    public JsonNode indicators(
            @RequestParam String symbol, @RequestParam(defaultValue = "REAL") String nature) {
        return quant.post(
                "/market/indicators",
                Map.of(
                        "bars",
                        market.bars(
                                symbol, LocalDate.of(2021, 1, 1), LocalDate.now(), nature, "RAW")));
    }
}
