-- 迁移脚本: 版本 9 到版本 10
-- operation_log 放行 RESET_AUTH (管理侧重置玩家密码与免密状态)。不加进 CHECK 的话该类日志
-- 会被数据库直接拒收, 而 DAO 把 SQLException 记成日志后仅返回 false, 于是整类审计静默丢失
-- (SET_ACTIVE 与 GENCODE 就这么丢过, 见 migrate_6_to_7)。
--
-- SQLite 不支持 ALTER 已有的 CHECK 约束, 只能重建表再迁数据。原有类型全部保留(只放宽、不收紧)。

CREATE TABLE operation_log_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type VARCHAR(20) NOT NULL,          -- 操作类型
    target_uuid VARCHAR(36),                      -- 目标玩家UUID
    target_name VARCHAR(16),                      -- 目标玩家名称
    operator_ip VARCHAR(45),                      -- 操作者IP
    operator_agent TEXT,                          -- 用户代理
    request_data TEXT,                            -- 请求数据
    response_status INTEGER,                      -- 响应状态码
    execution_time INTEGER,                       -- 执行时间(ms)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_operation_type CHECK (operation_type IN
        ('ADD', 'REMOVE', 'QUERY', 'BATCH_ADD', 'BATCH_REMOVE', 'SYNC',
         'UNAUTHORIZED_ACCESS', 'SET_ACTIVE', 'GENCODE', 'RESET_AUTH'))
);

INSERT INTO operation_log_new
    (id, operation_type, target_uuid, target_name, operator_ip, operator_agent,
     request_data, response_status, execution_time, created_at)
SELECT
    id, operation_type, target_uuid, target_name, operator_ip, operator_agent,
    request_data, response_status, execution_time, created_at
FROM operation_log;

DROP TABLE operation_log;

ALTER TABLE operation_log_new RENAME TO operation_log;

-- DROP TABLE 会连带删掉表上的索引, 此处按 schema/indexes.sql 原样重建
CREATE INDEX IF NOT EXISTS idx_operation_log_type ON operation_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_operation_log_target ON operation_log(target_uuid);
CREATE INDEX IF NOT EXISTS idx_operation_log_time ON operation_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_log_ip ON operation_log(operator_ip);
