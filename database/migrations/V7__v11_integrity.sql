-- V1.1仅增加证据字段；旧冻结预测不回填、不覆盖，缺失字段保持NULL。
ALTER TABLE prediction ADD COLUMN target_date date;
ALTER TABLE prediction ADD COLUMN market_cutoff timestamptz;
ALTER TABLE prediction ADD COLUMN news_cutoff timestamptz;
ALTER TABLE prediction ADD COLUMN training_data_cutoff date;
ALTER TABLE prediction ADD COLUMN model_parameter_version text;
ALTER TABLE prediction ADD COLUMN raw_score numeric(24,12);
ALTER TABLE prediction ADD COLUMN score_semantics text;

ALTER TABLE etf_instrument ADD COLUMN etf_type text NOT NULL DEFAULT 'UNVERIFIED';
UPDATE etf_instrument SET etf_type='STOCK_ETF'
WHERE symbol IN ('510300','510500','159915');

-- 官方公告校正关闭日期时标记，而不是删除曾经使用过的日历记录。
ALTER TABLE trading_calendar ADD COLUMN is_open boolean NOT NULL DEFAULT true;

-- 最近结算解释是当前状态投影；历史解释保留在account_snapshot.details。
ALTER TABLE paper_account ADD COLUMN last_settlement jsonb NOT NULL DEFAULT '{}';

-- 长训练由独立心跳线程续租，领取令牌阻止过期worker覆盖新任务状态。
ALTER TABLE task_execution ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE task_execution ADD COLUMN lease_token uuid;
