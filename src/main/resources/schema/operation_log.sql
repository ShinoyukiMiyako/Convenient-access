-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
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
    
    -- 约束。新增操作类型时必须同步这里, 否则 INSERT 会被 CHECK 拒收, 而 DAO 只把
    -- SQLException 记成日志并返回 false, 该类日志会被静默丢弃 (SET_ACTIVE/GENCODE 曾如此)。
    CONSTRAINT chk_operation_type CHECK (operation_type IN
        ('ADD', 'REMOVE', 'QUERY', 'BATCH_ADD', 'BATCH_REMOVE', 'SYNC',
         'UNAUTHORIZED_ACCESS', 'SET_ACTIVE', 'GENCODE', 'RESET_AUTH'))
);