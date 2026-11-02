-- 场外基金手工记账独立账户；不触及旧模拟账户或真实交易渠道。
create table fund_portfolio (
    id uuid primary key,
    owner_id text not null,
    account_kind text not null check (account_kind in ('ACTUAL_MANUAL','SIMULATED','DEMO')),
    name text not null,
    cash_confirmed boolean not null default false,
    version bigint not null default 0,
    created_at timestamptz not null default now()
);
create index idx_fund_portfolio_owner on fund_portfolio(owner_id,account_kind);
create table fund_ledger_event (
    id uuid primary key,
    account_id uuid not null references fund_portfolio(id),
    transaction_id uuid,
    fund_code text,
    event_type text not null,
    cash_delta numeric(24,2) not null default 0,
    transit_delta numeric(24,2) not null default 0,
    receivable_delta numeric(24,2) not null default 0,
    units_delta numeric(24,8) not null default 0,
    cost_delta numeric(24,2) not null default 0,
    contribution_delta numeric(24,2) not null default 0,
    opening_delta numeric(24,2) not null default 0,
    effective_date date not null,
    details jsonb not null,
    actor text not null,
    created_at timestamptz not null default now()
);
create index idx_fund_ledger_account on fund_ledger_event(account_id,created_at);
create index idx_fund_ledger_transaction on fund_ledger_event(transaction_id);
create table fund_transaction (
    id uuid primary key,
    account_id uuid not null references fund_portfolio(id),
    fund_code text not null,
    transaction_type text not null,
    status text not null,
    amount numeric(24,2) not null,
    units numeric(24,8),
    version bigint not null default 0,
    details jsonb not null,
    created_at timestamptz not null default now()
);
create index idx_fund_transaction_account on fund_transaction(account_id,created_at);
create table fund_transaction_revision (
    id uuid primary key,
    transaction_id uuid not null references fund_transaction(id),
    version bigint not null,
    details jsonb not null,
    actor text not null,
    created_at timestamptz not null default now()
);
create table fund_dca_policy (
    id uuid primary key,
    account_id uuid not null references fund_portfolio(id),
    version bigint not null,
    details jsonb not null,
    created_at timestamptz not null default now()
);
create index idx_fund_policy_account on fund_dca_policy(account_id,version);
create table fund_dca_plan (
    id uuid primary key,
    account_id uuid not null references fund_portfolio(id),
    cycle text not null,
    version bigint not null default 0,
    status text not null,
    details jsonb not null,
    created_at timestamptz not null default now()
);
create index idx_fund_plan_account on fund_dca_plan(account_id,cycle);
create table fund_dca_plan_revision (
    id uuid primary key,
    plan_id uuid not null references fund_dca_plan(id),
    details jsonb not null,
    created_at timestamptz not null default now()
);
create table fund_ledger_request (
    id uuid primary key,
    owner_id text not null,
    account_scope text not null,
    request_key text not null,
    request_body jsonb not null,
    response_body jsonb not null,
    created_at timestamptz not null default now()
);
create index idx_fund_request_lookup on fund_ledger_request(owner_id,account_scope,request_key);
-- 并发使用事务咨询锁+账户行锁+应用幂等键，不增加非必要唯一索引。
create function prevent_fund_ledger_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Fund ledger evidence is immutable; append a reversal or revision';
end;
$$;
create trigger fund_ledger_immutable before update or delete on fund_ledger_event
    for each row execute function prevent_fund_ledger_mutation();
create trigger fund_transaction_revision_immutable before update or delete on fund_transaction_revision
    for each row execute function prevent_fund_ledger_mutation();
create trigger fund_policy_immutable before update or delete on fund_dca_policy
    for each row execute function prevent_fund_ledger_mutation();
create trigger fund_plan_revision_immutable before update or delete on fund_dca_plan_revision
    for each row execute function prevent_fund_ledger_mutation();
create trigger fund_request_immutable before update or delete on fund_ledger_request
    for each row execute function prevent_fund_ledger_mutation();
