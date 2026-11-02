package cn.etfquantlab.prediction;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PredictionController {
    private final Store store;

    /** 页面读取冻结预测和来源证据，无修改预测接口。 */
    public PredictionController(Store store) {
        this.store = store;
    }

    /** 最近预测附带独立对账结果，实时模拟与历史模型测试保持分开。 */
    @GetMapping("/predictions")
    public List<JsonNode> predictions(@RequestParam(defaultValue = "300") int limit) {
        return store.db.query(
                "select jsonb_build_object('prediction',to_jsonb(p),'actual',to_jsonb(a),'execution',"
                        + "jsonb_build_object('linkage_recorded',p.details->>'evidence_version'='v1.1',"
                        + "'signal_triggered',coalesce((p.details->>'target_weight')::numeric,0)>0,"
                        + "'order_count',(select count(*) from paper_order o where "
                        + "o.details->>'prediction_id'=p.id::text),"
                        + "'fill_count',(select count(*) from paper_fill f join paper_order o on "
                        + "o.id=f.order_id where o.details->>'prediction_id'=p.id::text),"
                        + "'realized_pnl',(select sum((f.details->>'realized_pnl')::numeric) from "
                        + "paper_fill f join paper_order o on o.id=f.order_id where "
                        + "o.details->>'prediction_id'=p.id::text),"
                        + "'pnl_semantics','卖出信号对应已平仓收益；买入未平仓及旧记录未关联时为空'))::text"
                        + " from prediction p left join prediction_actual a on a.prediction_id=p.id"
                        + " order by p.created_at desc,p.strategy_id,p.symbol limit ?",
                (rs, row) -> store.decode(rs.getString(1)),
                Math.max(1, Math.min(limit, 1000)));
    }

    /** 原始摘要和允许的来源链接供人工核对，不渲染新闻HTML。 */
    @GetMapping("/news")
    public List<JsonNode> news() {
        return store.db.query(
                "select row_to_json(n)::text from (select * from news_item order by published_at desc limit 100) n",
                (rs, row) -> store.decode(rs.getString(1)));
    }

    /** 分析列表保留失败状态、真实模型版本与调用输出，不混入Mock。 */
    @GetMapping("/analyses")
    public List<JsonNode> analyses() {
        return store.db.query(
                "select row_to_json(a)::text from (select id,input_cutoff,status,nature,"
                        + "prompt_version,model_version,source_ids,result,error_code,created_at"
                        + " from llm_analysis order by created_at desc limit 30) a",
                (rs, row) -> store.decode(rs.getString(1)));
    }
}
