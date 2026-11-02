-- 场外基金独立命名空间，不修改既有ETF、黄金、模拟账户或预测。
create table fund_instrument (
    code text primary key, name text not null, asset_class text not null,
    instrument_type text not null, share_class text not null default 'A',
    currency text not null default 'CNY', metadata jsonb not null default '{}'
);
insert into fund_instrument(code,name,asset_class,instrument_type,metadata) values
('460300','华泰柏瑞沪深300ETF联接A','EQUITY','ETF_FEEDER',
 '{"reference_mappings":[{"code":"510300","relation_type":"RESEARCH_PROXY","status":"TO_VERIFY_TARGET_CONTRACT"}],"source_url":"https://www.bankofchina.com/sdbapp/fundsdocs/202508/t20250818_25457830.html"}'),
('007169','易方达中债1–3年国开行债券指数A','BOND','BOND_INDEX_FUND',
 '{"reference_mappings":[],"source_url":"https://e.efunds.com.cn/cart/subscriptions?fundCode=007169","reference_mapping_status":"NO_VERIFIED_INDEX_CODE"}'),
('008701','华夏黄金ETF联接A','GOLD','ETF_FEEDER',
 '{"reference_mappings":[{"code":"518850","relation_type":"TARGET_ETF","status":"VERIFIED_ANNUAL_REPORT_2023"}],"source_url":"https://www.chinaamc.com.cn/fund/008701/index.shtml"}');

create table fund_nav_observation (
    id uuid primary key, fund_code text not null references fund_instrument(code),
    nav_date date not null, unit_nav numeric(24,10) not null check(unit_nav>0),
    accumulated_nav numeric(24,10), published_at timestamptz,
    first_observed_at timestamptz not null, fetched_at timestamptz not null,
    provider text not null, revision integer not null, revision_hash text not null,
    quality_status text not null, nature text not null check(nature='REAL'),
    details jsonb not null
);
create index fund_nav_lookup on fund_nav_observation(fund_code,nav_date,revision);
create table fund_source_status (
    fund_code text primary key references fund_instrument(code),
    status text not null, last_attempt_at timestamptz not null,
    last_success_at timestamptz, details jsonb not null default '{}'
);
create table fund_model (
    id text primary key, fund_code text not null references fund_instrument(code),
    horizon integer not null check(horizon in (20,60)),
    created_at timestamptz not null default now(), details jsonb not null
);
create index fund_model_lookup on fund_model(fund_code,horizon,created_at);
create table fund_forecast (
    id uuid primary key, fund_code text not null references fund_instrument(code),
    business_key text not null, horizon integer not null check(horizon in (20,60)),
    generated_at timestamptz not null, details jsonb not null
);
create index fund_forecast_lookup on fund_forecast(fund_code,horizon,generated_at);
create index fund_forecast_key on fund_forecast(business_key);
create table fund_forecast_evaluation (
    id uuid primary key, forecast_id uuid not null references fund_forecast(id),
    evaluated_at timestamptz not null default now(), details jsonb not null
);
create index fund_evaluation_lookup on fund_forecast_evaluation(forecast_id,evaluated_at);
create table fund_news_analysis (
    id uuid primary key, fund_code text not null references fund_instrument(code),
    business_key text not null, created_at timestamptz not null default now(),
    input_cutoff_at timestamptz not null, details jsonb not null
);
create index fund_news_lookup on fund_news_analysis(fund_code,created_at);
-- 调用状态与不可变展示快照分离；崩溃后的费用预留不得自动重试收费。
create table fund_news_call (
    id uuid primary key, fund_code text not null, business_key text not null,
    trace_id text not null, status text not null, quote jsonb not null,
    input_snapshot jsonb not null, result jsonb, error_code text,
    created_at timestamptz not null default now()
);
create index fund_news_call_lookup on fund_news_call(fund_code,created_at);
create table fund_comparison (
    id uuid primary key, owner_id text not null, business_key text not null,
    created_at timestamptz not null default now(), request jsonb not null, details jsonb not null
);
create index fund_comparison_owner on fund_comparison(owner_id,created_at);

-- 修订与到期结果追加到独立记录；禁止重训或数据修订覆盖历史冻结事实。
create function fund_immutable() returns trigger language plpgsql as $$
begin raise exception 'Fund research evidence is append-only'; end;
$$;
create trigger fund_nav_immutable before update or delete on fund_nav_observation
for each row execute function fund_immutable();
create trigger fund_model_immutable before update or delete on fund_model
for each row execute function fund_immutable();
create trigger fund_forecast_immutable before update or delete on fund_forecast
for each row execute function fund_immutable();
create trigger fund_evaluation_immutable before update or delete on fund_forecast_evaluation
for each row execute function fund_immutable();
create trigger fund_news_immutable before update or delete on fund_news_analysis
for each row execute function fund_immutable();
create trigger fund_comparison_immutable before update or delete on fund_comparison
for each row execute function fund_immutable();
