-- 仅保存当前管理员主动修改后的BCrypt哈希；首次登录仍兼容受保护的部署配置。
CREATE TABLE admin_credential (
    username VARCHAR(80) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE admin_credential IS '管理员持久化密码，不存明文；修改操作另记审计';
