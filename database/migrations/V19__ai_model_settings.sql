-- 每个供应商仅保存非敏感选择；应用使用事务锁保证单行，不另建唯一键或索引。
create table ai_model_setting (
    provider varchar(16) not null,
    model varchar(80) not null,
    reasoning_effort varchar(16) not null,
    updated_at timestamptz not null default now()
);
