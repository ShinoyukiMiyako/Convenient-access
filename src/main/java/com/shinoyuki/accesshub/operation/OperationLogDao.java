package com.shinoyuki.accesshub.operation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.database.DatabaseManager;

/**
 * 操作日志数据访问对象
 */
public class OperationLogDao {
    private static final Logger logger = LoggerFactory.getLogger(OperationLogDao.class);
    private final DatabaseManager dbManager;
    
    public OperationLogDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }
    
    /**
     * 记录操作日志
     * @param operationType 操作类型。必须在 operation_log 的 chk_operation_type 白名单内,
     *                      否则 INSERT 被拒、本方法返回 false (调用方务必检查返回值)。
     *                      代码实际写入的有 ADD / REMOVE / SET_ACTIVE / GENCODE / RESET_AUTH / UNAUTHORIZED_ACCESS
     * @param targetUuid 目标玩家UUID
     * @param targetName 目标玩家名称
     * @param operatorIp 操作者IP
     * @param operatorAgent 用户代理
     * @param requestData 请求数据(JSON格式)
     * @param responseStatus 响应状态码
     * @param executionTime 执行时间(ms)
     * @return 是否记录成功
     */
    public boolean logOperation(String operationType, String targetUuid, String targetName,
                               String operatorIp, String operatorAgent, String requestData,
                               int responseStatus, long executionTime) {
        String sql = """
            INSERT INTO operation_log 
            (operation_type, target_uuid, target_name, operator_ip, operator_agent, 
             request_data, response_status, execution_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, operationType);
            pstmt.setString(2, targetUuid);
            pstmt.setString(3, targetName);
            pstmt.setString(4, operatorIp);
            pstmt.setString(5, operatorAgent);
            pstmt.setString(6, requestData);
            pstmt.setInt(7, responseStatus);
            pstmt.setLong(8, executionTime);
            
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            logger.error("记录操作日志失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 查询操作日志
     * @param operationType 操作类型(可选)
     * @param targetUuid 目标UUID(可选)
     * @param targetName 目标名称(可选)
     * @param operatorIp 操作者IP(可选)
     * @param startTime 开始时间(可选)
     * @param endTime 结束时间(可选)
     * @param limit 查询数量限制
     * @param offset 偏移量
     * @return 操作日志列表
     */
    public List<OperationLog> queryLogs(String operationType, String targetUuid, String targetName, String operatorIp,
                                       LocalDateTime startTime, LocalDateTime endTime,
                                       int limit, int offset) {
        List<OperationLog> logs = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM operation_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        
        if (operationType != null && !operationType.isEmpty()) {
            sql.append(" AND operation_type = ?");
            params.add(operationType);
        }
        
        if (targetUuid != null && !targetUuid.isEmpty()) {
            sql.append(" AND target_uuid = ?");
            params.add(targetUuid);
        }
        
        if (targetName != null && !targetName.isEmpty()) {
            sql.append(" AND target_name = ?");
            params.add(targetName);
        }
        
        if (operatorIp != null && !operatorIp.isEmpty()) {
            sql.append(" AND operator_ip = ?");
            params.add(operatorIp);
        }
        
        if (startTime != null) {
            sql.append(" AND created_at >= ?");
            params.add(java.sql.Timestamp.valueOf(startTime));
        }
        
        if (endTime != null) {
            sql.append(" AND created_at <= ?");
            params.add(java.sql.Timestamp.valueOf(endTime));
        }
        
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    logs.add(mapResultSetToLog(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询操作日志失败: {}", e.getMessage(), e);
        }
        
        return logs;
    }
    
    /**
     * 统计操作日志数量
     * @param operationType 操作类型(可选)
     * @param targetUuid 目标UUID(可选)
     * @param targetName 目标名称(可选)
     * @param operatorIp 操作者IP(可选)
     * @param startTime 开始时间(可选)
     * @param endTime 结束时间(可选)
     * @return 日志总数
     */
    public long countLogs(String operationType, String targetUuid, String targetName, String operatorIp,
                         LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM operation_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        
        if (operationType != null && !operationType.isEmpty()) {
            sql.append(" AND operation_type = ?");
            params.add(operationType);
        }
        
        if (targetUuid != null && !targetUuid.isEmpty()) {
            sql.append(" AND target_uuid = ?");
            params.add(targetUuid);
        }
        
        if (targetName != null && !targetName.isEmpty()) {
            sql.append(" AND target_name = ?");
            params.add(targetName);
        }
        
        if (operatorIp != null && !operatorIp.isEmpty()) {
            sql.append(" AND operator_ip = ?");
            params.add(operatorIp);
        }
        
        if (startTime != null) {
            sql.append(" AND created_at >= ?");
            params.add(java.sql.Timestamp.valueOf(startTime));
        }
        
        if (endTime != null) {
            sql.append(" AND created_at <= ?");
            params.add(java.sql.Timestamp.valueOf(endTime));
        }
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            logger.error("统计操作日志失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 按操作类型聚合统计。
     *
     * 返回库中实际出现过的类型, 不预设清单: 调用方若硬编码一份类型数组, 会随着新增操作类型
     * 而失配 —— 此前统计固定列举 ADD/REMOVE/BATCH_ADD/BATCH_REMOVE/UPDATE, 而实际写入的是
     * ADD/REMOVE/SET_ACTIVE/GENCODE, 导致三项恒为 0、另两项完全不出现在分项里。
     *
     * @param startTime 开始时间(可选)
     * @param endTime 结束时间(可选)
     * @return 操作类型 -> 条数, 按条数降序; 查询失败返回空 Map
     */
    public Map<String, Long> countByOperationType(LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder sql = new StringBuilder(
                "SELECT operation_type, COUNT(*) AS cnt FROM operation_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (startTime != null) {
            sql.append(" AND created_at >= ?");
            params.add(java.sql.Timestamp.valueOf(startTime));
        }

        if (endTime != null) {
            sql.append(" AND created_at <= ?");
            params.add(java.sql.Timestamp.valueOf(endTime));
        }

        sql.append(" GROUP BY operation_type ORDER BY cnt DESC");

        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String type = rs.getString("operation_type");
                if (type != null && !type.isEmpty()) {
                    counts.put(type, rs.getLong("cnt"));
                }
            }
        } catch (SQLException e) {
            logger.error("按类型统计操作日志失败: {}", e.getMessage(), e);
        }
        return counts;
    }

    /**
     * 清理旧的操作日志
     * @param daysToKeep 保留天数
     * @return 删除的记录数
     */
    public int cleanOldLogs(int daysToKeep) {
        String sql = "DELETE FROM operation_log WHERE created_at < datetime('now', '-' || ? || ' days')";
        
        try (Connection conn = dbManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, daysToKeep);
            int affected = pstmt.executeUpdate();
            
            if (affected > 0) {
                logger.info("清理了 {} 条旧操作日志(保留{}天)", affected, daysToKeep);
            }
            
            return affected;
        } catch (SQLException e) {
            logger.error("清理旧操作日志失败: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 将 ResultSet 映射为 OperationLog 对象
     */
    private OperationLog mapResultSetToLog(ResultSet rs) throws SQLException {
        OperationLog log = new OperationLog();
        log.setId(rs.getLong("id"));
        log.setOperationType(rs.getString("operation_type"));
        log.setTargetUuid(rs.getString("target_uuid"));
        log.setTargetName(rs.getString("target_name"));
        log.setOperatorIp(rs.getString("operator_ip"));
        log.setOperatorAgent(rs.getString("operator_agent"));
        log.setRequestData(rs.getString("request_data"));
        log.setResponseStatus(rs.getInt("response_status"));
        log.setExecutionTime(rs.getLong("execution_time"));
        log.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return log;
    }
}
