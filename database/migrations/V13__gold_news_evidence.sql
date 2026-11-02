-- 黄金解释复用既有费用账本，但输入、结果和业务键与ETF完全隔离。
CREATE TABLE gold_analysis (
    id uuid PRIMARY KEY,
    business_key text NOT NULL,
    trace_id text NOT NULL,
    input_cutoff timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL,
    quote jsonb NOT NULL,
    input_snapshot jsonb NOT NULL,
    result jsonb,
    error_code text
);
CREATE INDEX gold_analysis_lookup ON gold_analysis(business_key, created_at);
CREATE INDEX gold_analysis_trace ON gold_analysis(trace_id);
