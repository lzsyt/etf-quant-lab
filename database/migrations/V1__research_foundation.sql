-- 所有时间戳使用 timestamptz；金额及价格使用 NUMERIC。业务幂等由事务锁实现。
CREATE TABLE etf_instrument (
    symbol varchar(6) PRIMARY KEY,
    name text NOT NULL,
    exchange varchar(8) NOT NULL,
    tracking_index text NOT NULL,
    lot_size integer NOT NULL,
    settlement_days integer NOT NULL,
    limit_rate numeric(10,6) NOT NULL,
    tick_size numeric(10,6) NOT NULL DEFAULT 0.001,
    rule_valid_from date NOT NULL,
    source_url text NOT NULL,
    rules_source_url text NOT NULL,
    verified_at timestamptz NOT NULL,
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE market_bar (
    id uuid PRIMARY KEY,
    symbol varchar(6) NOT NULL REFERENCES etf_instrument(symbol),
    trade_date date NOT NULL,
    open numeric(20,6) NOT NULL,
    high numeric(20,6) NOT NULL,
    low numeric(20,6) NOT NULL,
    close numeric(20,6) NOT NULL,
    volume numeric(24,4) NOT NULL,
    amount numeric(24,4),
    price_basis varchar(16) NOT NULL,
    source text NOT NULL,
    nature varchar(16) NOT NULL,
    fetched_at timestamptz NOT NULL,
    quality_status varchar(24) NOT NULL,
    quality_flags jsonb NOT NULL DEFAULT '[]',
    raw_values jsonb NOT NULL,
    revision integer NOT NULL DEFAULT 1
);
CREATE INDEX market_bar_lookup ON market_bar(symbol, trade_date, price_basis, nature);

CREATE TABLE market_sync (
    id uuid PRIMARY KEY,
    symbol varchar(6) NOT NULL,
    source text NOT NULL,
    status varchar(24) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    report jsonb NOT NULL DEFAULT '{}'
);
CREATE TABLE trading_calendar (
    trade_date date PRIMARY KEY,
    source text NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE corporate_action (
    id uuid PRIMARY KEY,
    symbol varchar(6) NOT NULL REFERENCES etf_instrument(symbol),
    ex_date date NOT NULL,
    pay_date date NOT NULL,
    cash_per_unit numeric(20,6) NOT NULL DEFAULT 0,
    split_factor numeric(20,8) NOT NULL DEFAULT 1,
    reference_price numeric(20,6),
    source_url text NOT NULL,
    verified boolean NOT NULL DEFAULT false
);
CREATE INDEX corporate_action_lookup ON corporate_action(symbol, ex_date);

CREATE TABLE strategy_config (
    id varchar(40) PRIMARY KEY,
    name text NOT NULL,
    category varchar(32) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    parameters jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE risk_config_history (
    id uuid PRIMARY KEY,
    parameters jsonb NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    reason text NOT NULL
);
CREATE TABLE model_version (
    id text PRIMARY KEY,
    symbol varchar(6) NOT NULL,
    algorithm varchar(32) NOT NULL,
    horizon integer NOT NULL,
    feature_version text NOT NULL,
    trained_at timestamptz NOT NULL DEFAULT now(),
    training_range jsonb NOT NULL,
    hyperparameters jsonb NOT NULL,
    metrics jsonb NOT NULL,
    metadata jsonb NOT NULL
);
CREATE INDEX model_version_lookup ON model_version(symbol, trained_at);
CREATE TABLE news_item (
    id text PRIMARY KEY,
    title text NOT NULL,
    summary text NOT NULL,
    source_url text NOT NULL,
    source_name text NOT NULL,
    published_at timestamptz NOT NULL,
    fetched_at timestamptz NOT NULL,
    nature varchar(16) NOT NULL,
    publication_verified boolean NOT NULL,
    content_sha256 text NOT NULL
);
CREATE TABLE llm_analysis (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    input_cutoff timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    nature varchar(16) NOT NULL,
    prompt_version text NOT NULL,
    model_version text NOT NULL,
    source_ids jsonb NOT NULL,
    result jsonb,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX llm_analysis_lookup ON llm_analysis(business_key);
CREATE TABLE api_call_cost (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    status varchar(24) NOT NULL,
    cost_currency varchar(8) NOT NULL DEFAULT 'USD',
    reserved_cost numeric(16,8) NOT NULL,
    actual_cost numeric(16,8),
    input_tokens integer,
    output_tokens integer,
    duration_ms bigint,
    pricing_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX api_cost_day ON api_call_cost(created_at);
CREATE TABLE prediction (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    symbol varchar(6) NOT NULL,
    strategy_id text NOT NULL,
    horizon integer NOT NULL,
    signal_date date NOT NULL,
    input_cutoff timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    model_version text NOT NULL,
    nature varchar(16) NOT NULL,
    probability numeric(12,10),
    direction varchar(16) NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX prediction_lookup ON prediction(business_key);
CREATE INDEX prediction_date ON prediction(signal_date, symbol);
-- 预测与事后结果分表，禁止更新或删除已冻结预测。
CREATE FUNCTION reject_prediction_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Frozen predictions cannot be changed';
END;
$$;
CREATE TRIGGER prediction_immutable BEFORE UPDATE OR DELETE ON prediction
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TABLE prediction_actual (
    prediction_id uuid PRIMARY KEY REFERENCES prediction(id),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    target_date date NOT NULL,
    actual_return numeric(20,10) NOT NULL,
    actual_up boolean NOT NULL,
    correct boolean,
    brier_score numeric(20,10),
    price_basis varchar(16) NOT NULL,
    details jsonb NOT NULL
);
CREATE TABLE backtest_experiment (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    status varchar(24) NOT NULL,
    evaluation_type varchar(32) NOT NULL,
    nature varchar(16) NOT NULL,
    configuration jsonb NOT NULL,
    result jsonb,
    error_code text
);
CREATE TABLE backtest_order (
    id uuid PRIMARY KEY,
    experiment_id uuid NOT NULL REFERENCES backtest_experiment(id),
    business_key text NOT NULL,
    details jsonb NOT NULL
);
CREATE TABLE backtest_fill (
    id uuid PRIMARY KEY,
    experiment_id uuid NOT NULL REFERENCES backtest_experiment(id),
    details jsonb NOT NULL
);
CREATE TABLE paper_account (
    id varchar(40) PRIMARY KEY,
    strategy_id text NOT NULL,
    initial_capital numeric(20,2) NOT NULL,
    cash numeric(20,2) NOT NULL,
    equity numeric(20,2) NOT NULL,
    peak_equity numeric(20,2) NOT NULL,
    locked boolean NOT NULL DEFAULT false,
    last_trade_date date,
    state jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE paper_position (
    id uuid PRIMARY KEY,
    account_id varchar(40) NOT NULL REFERENCES paper_account(id),
    symbol varchar(6) NOT NULL,
    quantity numeric(20,4) NOT NULL,
    cost_basis numeric(20,2) NOT NULL,
    lots jsonb NOT NULL
);
CREATE INDEX paper_position_account ON paper_position(account_id, symbol);
CREATE TABLE paper_order (
    id uuid PRIMARY KEY,
    account_id varchar(40) NOT NULL REFERENCES paper_account(id),
    business_key text NOT NULL,
    trade_date date NOT NULL,
    symbol varchar(6) NOT NULL,
    side varchar(8) NOT NULL,
    status varchar(24) NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX paper_order_lookup ON paper_order(account_id, business_key);
CREATE TABLE paper_fill (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES paper_order(id),
    quantity numeric(20,4) NOT NULL,
    price numeric(20,6) NOT NULL,
    fee numeric(20,2) NOT NULL,
    details jsonb NOT NULL
);
CREATE TABLE account_snapshot (
    id uuid PRIMARY KEY,
    account_id varchar(40) NOT NULL REFERENCES paper_account(id),
    trade_date date NOT NULL,
    equity numeric(20,2) NOT NULL,
    cash numeric(20,2) NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX account_snapshot_lookup ON account_snapshot(account_id, trade_date);
CREATE TABLE risk_event (
    id uuid PRIMARY KEY,
    account_id varchar(40),
    trade_date date NOT NULL,
    rule text NOT NULL,
    details jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE task_execution (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    task_type varchar(40) NOT NULL,
    status varchar(24) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    started_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    request jsonb NOT NULL,
    result jsonb,
    error_code text
);
CREATE INDEX task_execution_lookup ON task_execution(business_key);
CREATE INDEX task_execution_pending ON task_execution(status, updated_at);
CREATE TABLE operation_audit (
    id uuid PRIMARY KEY,
    action text NOT NULL,
    details jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
