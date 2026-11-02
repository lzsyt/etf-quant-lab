-- 令牌仅保存摘要；明文只在创建时返回一次。
create table agent_access_token (
    id uuid primary key,
    name varchar(100) not null,
    token_hash char(64) not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz
);
create index agent_access_token_hash_idx on agent_access_token(token_hash);
