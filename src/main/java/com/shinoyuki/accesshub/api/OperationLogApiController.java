package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.shinoyuki.accesshub.operation.OperationLog;
import com.shinoyuki.accesshub.operation.OperationLogDao;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 操作日志API控制器
 * 提供操作日志查询接口
 */
public class OperationLogApiController {
    private static final Logger logger = LoggerFactory.getLogger(OperationLogApiController.class);
    
    private final OperationLogDao operationLogDao;
    private final Gson gson;
    
    public OperationLogApiController(OperationLogDao operationLogDao) {
        this.operationLogDao = operationLogDao;
        // 配置Gson以正确处理LocalDateTime
        this.gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
                private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
                
                @Override
                public void write(JsonWriter out, LocalDateTime value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                    } else {
                        out.value(value.format(formatter));
                    }
                }
                
                @Override
                public LocalDateTime read(JsonReader in) throws IOException {
                    if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                        in.nextNull();
                        return null;
                    }
                    return LocalDateTime.parse(in.nextString(), formatter);
                }
            })
            .setPrettyPrinting()
            .create();
    }
    
    /**
     * 处理GET /api/v1/logs/operations - 查询操作日志
     * 
     * 查询参数:
     * - type: 操作类型(实际写入的有 ADD/REMOVE/SET_ACTIVE/GENCODE/RESET_AUTH)
     * - target_uuid: 目标玩家UUID
     * - target_name: 目标玩家名称
     * - operator_ip: 操作者IP
     * - start_time: 开始时间(ISO-8601格式)
     * - end_time: 结束时间(ISO-8601格式)
     * - limit: 返回记录数(默认100,最大1000)
     * - offset: 偏移量(默认0)
     */
    public void handleGetOperationLogs(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 获取查询参数
            String operationType = request.getParameter("type");
            String targetUuid = request.getParameter("target_uuid");
            String targetName = request.getParameter("target_name");
            String operatorIp = request.getParameter("operator_ip");
            String startTimeStr = request.getParameter("start_time");
            String endTimeStr = request.getParameter("end_time");
            
            // 解析时间参数
            LocalDateTime startTime = null;
            LocalDateTime endTime = null;
            
            if (startTimeStr != null && !startTimeStr.isEmpty()) {
                try {
                    startTime = LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("start_time格式无效,请使用ISO-8601格式: yyyy-MM-ddTHH:mm:ss"));
                    return;
                }
            }
            
            if (endTimeStr != null && !endTimeStr.isEmpty()) {
                try {
                    endTime = LocalDateTime.parse(endTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("end_time格式无效,请使用ISO-8601格式: yyyy-MM-ddTHH:mm:ss"));
                    return;
                }
            }
            
            // 解析分页参数
            int limit = getIntParameter(request, "limit", 100);
            int offset = getIntParameter(request, "offset", 0);
            
            // 限制最大返回数量
            if (limit > 1000) {
                limit = 1000;
            }
            if (limit < 1) {
                limit = 100;
            }
            if (offset < 0) {
                offset = 0;
            }
            
            // 查询日志
            List<OperationLog> logs = operationLogDao.queryLogs(
                operationType, 
                targetUuid, 
                targetName,
                operatorIp, 
                startTime, 
                endTime, 
                limit, 
                offset
            );
            
            // 查询总数
            long totalCount = operationLogDao.countLogs(
                operationType, 
                targetUuid,
                targetName,
                operatorIp, 
                startTime, 
                endTime
            );
            
            // 构建响应
            JsonArray logsArray = new JsonArray();
            for (OperationLog log : logs) {
                JsonObject logObj = new JsonObject();
                logObj.addProperty("id", log.getId());
                logObj.addProperty("operation_type", log.getOperationType());
                logObj.addProperty("target_uuid", log.getTargetUuid());
                logObj.addProperty("target_name", log.getTargetName());
                logObj.addProperty("operator_ip", log.getOperatorIp());
                logObj.addProperty("operator_agent", log.getOperatorAgent());
                logObj.addProperty("request_data", log.getRequestData());
                logObj.addProperty("response_status", log.getResponseStatus());
                logObj.addProperty("execution_time", log.getExecutionTime());
                logObj.addProperty("created_at", log.getCreatedAt() != null ? 
                    log.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
                logsArray.add(logObj);
            }
            
            JsonObject result = new JsonObject();
            result.add("logs", logsArray);
            result.addProperty("total", totalCount);
            result.addProperty("limit", limit);
            result.addProperty("offset", offset);
            result.addProperty("has_more", offset + logs.size() < totalCount);
            
            sendJsonResponse(response, 200, ApiResponse.success(result, "查询成功"));
            
        } catch (Exception e) {
            logger.error("查询操作日志失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("查询操作日志失败"));
        }
    }
    
    /**
     * 处理GET /api/v1/logs/operations/stats - 获取操作日志统计
     */
    public void handleGetOperationStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 获取时间范围
            String startTimeStr = request.getParameter("start_time");
            String endTimeStr = request.getParameter("end_time");
            
            LocalDateTime startTime = null;
            LocalDateTime endTime = null;
            
            if (startTimeStr != null && !startTimeStr.isEmpty()) {
                try {
                    startTime = LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("start_time格式无效"));
                    return;
                }
            }
            
            if (endTimeStr != null && !endTimeStr.isEmpty()) {
                try {
                    endTime = LocalDateTime.parse(endTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("end_time格式无效"));
                    return;
                }
            }
            
            // 按类型聚合。不再硬编码类型清单 —— 原先固定列举 ADD/REMOVE/BATCH_ADD/BATCH_REMOVE/
            // UPDATE, 而实际写入的只有 ADD/REMOVE/SET_ACTIVE/GENCODE, 结果是三项恒为 0, 另两项
            // 只进 total 不进分项。改为从库里聚合, 新增操作类型会自动出现在统计中。
            JsonObject stats = new JsonObject();
            Map<String, Long> byType = operationLogDao.countByOperationType(startTime, endTime);

            long totalCount = 0;
            for (Map.Entry<String, Long> entry : byType.entrySet()) {
                stats.addProperty(entry.getKey().toLowerCase(), entry.getValue());
                totalCount += entry.getValue();
            }

            stats.addProperty("total", totalCount);
            
            sendJsonResponse(response, 200, ApiResponse.success(stats, "统计成功"));
            
        } catch (Exception e) {
            logger.error("获取操作日志统计失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("获取统计信息失败"));
        }
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int statusCode, ApiResponse<?> apiResponse) {
        try {
            response.setStatus(statusCode);
            // 与 HTTP 状态码对齐, 理由同 WhitelistApiController.sendJsonResponse。
            if (apiResponse != null) {
                apiResponse.setCode(statusCode);
            }
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(gson.toJson(apiResponse));
        } catch (IOException e) {
            logger.error("发送响应失败", e);
        }
    }
    
    /**
     * 获取整数参数
     */
    private int getIntParameter(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
