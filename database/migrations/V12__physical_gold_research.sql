-- 黄金域独立于ETF行情、模型和账户；新增迁移不改变任何旧记录。
CREATE TABLE gold_source (
    series_id text PRIMARY KEY,
    status text NOT NULL,
    checked_at timestamptz NOT NULL,
    last_success_at timestamptz,
    details jsonb NOT NULL
);
CREATE TABLE gold_observation (
    id uuid PRIMARY KEY,
    series_id text NOT NULL,
    observation_date date NOT NULL,
    value numeric(24,10) NOT NULL,
    unit text NOT NULL,
    source text NOT NULL,
    source_url text NOT NULL,
    available_at timestamptz,
    availability_precision text NOT NULL,
    fetched_at timestamptz NOT NULL,
    revision text NOT NULL,
    quality text NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX gold_observation_lookup ON gold_observation(series_id,observation_date,fetched_at);
CREATE TABLE gold_model (
    model_id text PRIMARY KEY,
    horizon integer NOT NULL CHECK (horizon IN (63,126)),
    registered_at timestamptz NOT NULL DEFAULT now(),
    details jsonb NOT NULL
);
CREATE TABLE gold_prediction (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    horizon integer NOT NULL CHECK (horizon IN (63,126)),
    model_id text NOT NULL REFERENCES gold_model(model_id),
    reference_date date NOT NULL,
    generated_at timestamptz NOT NULL,
    details jsonb NOT NULL
);
CREATE INDEX gold_prediction_lookup ON gold_prediction(horizon,reference_date,generated_at);
CREATE INDEX gold_prediction_key ON gold_prediction(business_key);
CREATE TABLE gold_actual (
    prediction_id uuid PRIMARY KEY REFERENCES gold_prediction(id),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    details jsonb NOT NULL
);
-- 修订数据只能追加，新到期评价另存，任何重试不得覆盖原模型或预测。
CREATE TRIGGER gold_observation_immutable BEFORE UPDATE OR DELETE ON gold_observation
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER gold_model_immutable BEFORE UPDATE OR DELETE ON gold_model
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER gold_prediction_immutable BEFORE UPDATE OR DELETE ON gold_prediction
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
CREATE TRIGGER gold_actual_immutable BEFORE UPDATE OR DELETE ON gold_actual
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
