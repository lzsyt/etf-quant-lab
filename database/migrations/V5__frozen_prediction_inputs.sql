-- 每日行情输入只冻结一份，避免每策略复制大量日线；预测详情引用该ID。
CREATE TABLE prediction_input (
    id uuid PRIMARY KEY,
    input_cutoff timestamptz NOT NULL,
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER prediction_input_immutable BEFORE UPDATE OR DELETE ON prediction_input
    FOR EACH ROW EXECUTE FUNCTION reject_prediction_mutation();
