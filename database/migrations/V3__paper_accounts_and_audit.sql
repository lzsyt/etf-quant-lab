ALTER TABLE operation_audit ADD COLUMN actor text NOT NULL DEFAULT 'SYSTEM';

INSERT INTO risk_config_history(id,parameters,reason) VALUES
('51030000-0000-4000-8000-000000000001',
 '{"max_single_weight":"0.20","max_total_weight":"0.40","max_order_weight":"0.05",
   "max_daily_buy_weight":"0.10","daily_loss_limit":"0.02","drawdown_lock":"0.10",
   "commission_rate":"0.0003","minimum_commission":"5.00","slippage_bps":"5",
   "spread_bps":"2","max_volume_fraction":"0.01"}', 'V1用户指定的初始风险约束');

INSERT INTO paper_account(id,strategy_id,initial_capital,cash,equity,peak_equity,state)
SELECT id,id,100000,100000,100000,100000,
 '{"cash":"100000.00","initial_capital":"100000.00","equity":"100000.00",
   "peak_equity":"100000.00","locked":false,"lots":{},"receivables":[],
   "last_prices":{},"last_trade_date":null,"cumulative_fees":"0.00","cumulative_dividends":"0.00"}'::jsonb
FROM strategy_config;
