-- 新实验与旧预测/模拟账户物理分表；迁移不修改任何旧数据。
CREATE TABLE forward_experiment (
    id text PRIMARY KEY,
    strategy_version text NOT NULL,
    protocol_version text NOT NULL,
    label_version text NOT NULL,
    nature text NOT NULL,
    evaluation_type text NOT NULL,
    start_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    configuration jsonb NOT NULL
);
CREATE TABLE forward_account (
    id text PRIMARY KEY,
    experiment_id text NOT NULL REFERENCES forward_experiment(id),
    strategy text NOT NULL,
    initial_capital numeric(20,2) NOT NULL,
    state jsonb NOT NULL,
    last_trade_date date
);
CREATE TABLE forward_model (
    id text PRIMARY KEY,
    symbol text NOT NULL,
    algorithm text NOT NULL,
    available_at timestamptz NOT NULL,
    metadata jsonb NOT NULL
);
CREATE INDEX forward_model_lookup ON forward_model(symbol,algorithm,available_at);
CREATE TABLE forward_analysis (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    input_cutoff timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL,
    trace_id text NOT NULL,
    quote jsonb NOT NULL,
    input_snapshot jsonb NOT NULL,
    result jsonb,
    error_code text
);
CREATE INDEX forward_analysis_key ON forward_analysis(business_key);
CREATE TABLE forward_plan (
    id uuid PRIMARY KEY,
    experiment_id text NOT NULL REFERENCES forward_experiment(id),
    account_id text NOT NULL REFERENCES forward_account(id),
    entry_date date NOT NULL,
    generated_at timestamptz NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX forward_plan_lookup ON forward_plan(account_id,entry_date);
CREATE TABLE forward_decision (
    id uuid PRIMARY KEY,
    experiment_id text NOT NULL REFERENCES forward_experiment(id),
    account_id text NOT NULL REFERENCES forward_account(id),
    plan_id uuid NOT NULL REFERENCES forward_plan(id),
    symbol text NOT NULL,
    signal_date date NOT NULL,
    entry_date date NOT NULL,
    evaluation_date date NOT NULL,
    generated_at timestamptz NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX forward_decision_due ON forward_decision(evaluation_date,account_id);
CREATE TABLE forward_actual (
    decision_id uuid PRIMARY KEY REFERENCES forward_decision(id),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    details jsonb NOT NULL
);
CREATE TABLE forward_settlement (
    id uuid PRIMARY KEY,
    account_id text NOT NULL REFERENCES forward_account(id),
    trade_date date NOT NULL,
    booked_at timestamptz NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX forward_settlement_lookup ON forward_settlement(account_id,trade_date);
CREATE TABLE forward_health (
    stage text PRIMARY KEY,
    status text NOT NULL,
    last_attempt_at timestamptz NOT NULL,
    last_success_at timestamptz,
    reason text,
    trace_id text NOT NULL,
    details jsonb NOT NULL
);
-- 冻结输入、模型、决策、结果和流水均不可覆盖；账户仅是当前状态投影。
CREATE TRIGGER forward_experiment_immutable BEFORE UPDATE OR DELETE ON forward_experiment
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER forward_model_immutable BEFORE UPDATE OR DELETE ON forward_model
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER forward_plan_immutable BEFORE UPDATE OR DELETE ON forward_plan
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER forward_decision_immutable BEFORE UPDATE OR DELETE ON forward_decision
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER forward_actual_immutable BEFORE UPDATE OR DELETE ON forward_actual
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER forward_settlement_immutable BEFORE UPDATE OR DELETE ON forward_settlement
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
