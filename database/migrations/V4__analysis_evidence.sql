-- 原始资料和模型输出均保留用于审计；预测冻结规则保持不变。
ALTER TABLE llm_analysis ADD COLUMN input_snapshot jsonb NOT NULL DEFAULT '{}';
CREATE INDEX news_time_lookup ON news_item(published_at, fetched_at);
CREATE INDEX prediction_pending_lookup ON prediction(strategy_id, signal_date, horizon);
