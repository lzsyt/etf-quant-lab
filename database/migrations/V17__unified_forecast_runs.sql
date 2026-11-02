-- 统一预测运行只追加新领域事实；旧基金、ETF、黄金冻结表保持原样。
create table forecast_run (
    id uuid primary key,
    business_key text not null,
    created_at timestamptz not null default now(),
    cutoff_at timestamptz,
    status text not null,
    request jsonb not null
);
create index forecast_run_key on forecast_run(business_key);
create index forecast_run_created on forecast_run(created_at);
create table forecast_run_item (
    id uuid primary key,
    run_id uuid not null references forecast_run(id),
    asset text not null,
    target text not null,
    horizon integer not null,
    scheme text not null,
    status text not null,
    input_snapshot jsonb not null,
    quote jsonb,
    execution_result jsonb,
    result_id uuid,
    error_code text,
    claim_token uuid,
    updated_at timestamptz not null default now()
);
create index forecast_run_item_run on forecast_run_item(run_id);
create index forecast_run_item_quote on forecast_run_item((quote->>'request_fingerprint'));
create table forecast_result (
    id uuid primary key,
    item_id uuid not null references forecast_run_item(id),
    run_id uuid not null references forecast_run(id),
    asset text not null,
    target text not null,
    horizon integer not null,
    scheme text not null,
    generated_at timestamptz not null,
    details jsonb not null
);
create index forecast_result_lookup on forecast_result(asset,target,horizon,generated_at);
create index forecast_result_item on forecast_result(item_id);
create table forecast_result_actual (
    forecast_id uuid primary key references forecast_result(id),
    evaluated_at timestamptz not null default now(),
    details jsonb not null
);
create trigger forecast_result_immutable before update or delete on forecast_result
for each row execute function reject_prediction_mutation();
create trigger forecast_actual_immutable before update or delete on forecast_result_actual
for each row execute function reject_prediction_mutation();
