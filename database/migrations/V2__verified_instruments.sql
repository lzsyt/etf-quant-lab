-- 核对日期 2026-10-24。创业板 ETF 的 20% 限制仅自 2020-08-24 起适用。
INSERT INTO etf_instrument(symbol,name,exchange,tracking_index,lot_size,settlement_days,
    limit_rate,rule_valid_from,source_url,rules_source_url,verified_at) VALUES
('510300','华泰柏瑞沪深300ETF','SSE','沪深300',100,1,0.10,'2012-05-28',
 'https://www.sse.com.cn/disclosure/fund/etflist/detail.shtml?etfClass=03&fundid=510300&type=008',
 'https://etf.sse.com.cn/fund/quertion/','2026-10-24T00:00:00Z'),
('510500','南方中证500ETF','SSE','中证500',100,1,0.10,'2013-03-15',
 'https://www.southernfund.com/new/personal-financing/detail.html?fundCode=510500',
 'https://etf.sse.com.cn/fund/quertion/','2026-10-24T00:00:00Z'),
('159915','易方达创业板ETF','SZSE','创业板指',100,1,0.20,'2020-08-24',
 'https://www.szse.cn/disclosure/notice/general/t20220916_595887.html',
 'https://docs.static.szse.cn/www/aboutus/trends/conference/W020200821767425488573.pdf',
 '2026-10-24T00:00:00Z');

INSERT INTO strategy_config(id,name,category,parameters) VALUES
('ma','双均线 MA20/MA60','RULE','{}'),
('rsi','RSI 超卖反转','RULE','{}'),
('macd','MACD 趋势','RULE','{}'),
('momentum','20日趋势动量','RULE','{}'),
('rotation','ETF相对强弱轮动','RULE','{}'),
('lightgbm','LightGBM','ML','{}'),
('xgboost','XGBoost','ML','{}'),
('llm','大模型事件信号','LLM','{"forward_only":true}'),
('fusion','机器学习与大模型融合','FUSION','{"forward_only":true,"experimental":true,"ml_weight":0.7}'),
('buy_hold','风险约束买入持有','BASELINE','{}'),
('cash','保持现金','BASELINE','{}');
