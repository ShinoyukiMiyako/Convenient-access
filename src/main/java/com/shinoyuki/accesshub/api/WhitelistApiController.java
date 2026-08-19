package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.shinoyuki.accesshub.auth.AdminUser;
import com.shinoyuki.accesshub.auth.PlayerAuthAdminService;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.operation.OperationLogDao;
import com.shinoyuki.accesshub.utils.UuidUtils;
import com.shinoyuki.accesshub.whitelist.BatchOperation;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 白名单API控制器
 * 处理HTTP请求路由、参数验证、响应格式化和错误处理
 */
public class WhitelistApiController {
    private static final Logger logger = LoggerFactory.getLogger(WhitelistApiController.class);
    
    private final WhitelistManager whitelistManager;
    private final OperationLogDao operationLogDao;
    private final PlayerAuthService playerAuthService; // 加白成功后签发绑定注册码; 可为 null (认证未启用)
    private final PlayerAuthAdminService playerAuthAdminService; // 重置认证; 可为 null (mod 启动失败)
    private final AccessHubConfig config;
    private final Gson gson;

    public WhitelistApiController(WhitelistManager whitelistManager, OperationLogDao operationLogDao,
                                  PlayerAuthService playerAuthService,
                                  PlayerAuthAdminService playerAuthAdminService, AccessHubConfig config) {
        this.whitelistManager = whitelistManager;
        this.operationLogDao = operationLogDao;
        this.playerAuthService = playerAuthService;
        this.playerAuthAdminService = playerAuthAdminService;
        this.config = config;
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
     * 处理GET /api/v1/whitelist - 返回白名单数据（支持分页）
     */
    public void handleGetWhitelist(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 解析分页参数
            String pageStr = request.getParameter("page");
            String sizeStr = request.getParameter("size");
            String search = request.getParameter("search");
            String source = request.getParameter("source");
            String addedBy = request.getParameter("added_by");
            String sort = request.getParameter("sort");
            String order = request.getParameter("order");
            String startDate = request.getParameter("start_date");
            String endDate = request.getParameter("end_date");
            
            // 使用前端传入的分页参数，默认值：page=1, size=20
            final int page = (pageStr != null && !pageStr.isEmpty()) ? Integer.parseInt(pageStr) : 1;
            final int size = (sizeStr != null && !sizeStr.isEmpty()) ? Integer.parseInt(sizeStr) : 20;
            
            // 网页管理界面需展示全部条目 (含被禁用), 否则禁用后条目消失、管理员无从重新启用 -> includeInactive=true
            whitelistManager.getWhitelistPaginated(page, size, search, source, addedBy, sort, order, startDate, endDate, true)
                .thenAccept(result -> {
                    // 直接返回所有数据
                    sendJsonResponse(response, 200, ApiResponse.success(result));
                })
                .exceptionally(throwable -> {
                    logger.error("查询白名单失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("查询白名单失败"));
                    return null;
                })
                .join(); // 等待异步操作完成
            
        } catch (Exception e) {
            logger.error("处理白名单查询请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/whitelist - 添加玩家到白名单
     */
    public void handleAddPlayer(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long startTime = System.currentTimeMillis();
        String requestBody = null;
        try {
            // 读取请求体
            requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证 - 只需要name、source，其他为可选
            if (!json.has("name") || !json.has("source")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: name, source"));
                logOperation("ADD", null, null, request, requestBody, 400, System.currentTimeMillis() - startTime);
                return;
            }
            
            String name = json.get("name").getAsString();
            String sourceStr = json.get("source").getAsString();

            // 操作者服务端权威记录: JWT 登录的网页管理员优先 (从 ApiRouter 设的 currentUser 属性取,
            // 客户端无法伪造); 否则按调用方处理。addedByUuid 兼作渠道标记: WEBUI=网页后台, API=程序调用。
            String addedByName;
            String addedByUuid;
            Object currentUser = request.getAttribute("currentUser");
            if (currentUser instanceof AdminUser admin) {
                String displayName = admin.getDisplayName();
                addedByName = (displayName != null && !displayName.isEmpty()) ? displayName : admin.getUsername();
                addedByUuid = "WEBUI";
            } else if (json.has("added_by_name")) {
                addedByName = json.get("added_by_name").getAsString();
                addedByUuid = json.has("added_by_uuid") ? json.get("added_by_uuid").getAsString() : "API";
            } else {
                addedByName = "API";
                addedByUuid = "API";
            }
            
            // 处理时间戳 - 如果前端提供则使用，否则使用当前时间
            LocalDateTime addedAt;
            if (json.has("added_at")) {
                try {
                    String addedAtStr = json.get("added_at").getAsString();
                    addedAt = LocalDateTime.parse(addedAtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("时间格式无效，请使用ISO格式: yyyy-MM-ddTHH:mm:ss"));
                    return;
                }
            } else {
                addedAt = LocalDateTime.now();
            }
            
            // 验证参数格式
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效"));
                return;
            }
            
            WhitelistEntry.Source source;
            try {
                source = WhitelistEntry.Source.fromString(sourceStr);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("来源类型无效"));
                return;
            }
            
            // 新逻辑：只使用玩家名添加到白名单，UUID留空等玩家登录时补充
            // 前置校验玩家名格式。不拦的话 WhitelistManager 会静默返回 false, 与"已存在"
            // 共用同一条 409 分支, 结果是格式错误的请求收到"玩家已在白名单中"这种与实情
            // 无关的提示。校验规则复用 WhitelistManager 的同一份实现, 避免两处规则分叉。
            if (!WhitelistManager.isValidPlayerName(name)) {
                sendJsonResponse(response, 400,
                        ApiResponse.badRequest("玩家名格式无效: 需为 3-16 位字母、数字或下划线"));
                logOperation("ADD", null, name, request, requestBody, 400,
                        System.currentTimeMillis() - startTime);
                return;
            }

            logger.info("添加玩家到白名单（仅用户名）: {}", name);

            // 可选联系 QQ (问卷审核加白时带入): 空串归一为 null
            String rawQq = (json.has("qq") && !json.get("qq").isJsonNull()) ? json.get("qq").getAsString().trim() : "";
            String qq = rawQq.isEmpty() ? null : rawQq;

            CompletableFuture<Boolean> addFuture = whitelistManager.addPlayerByNameOnly(name, addedByName, addedByUuid, source, addedAt, qq);
            
            // 处理添加结果
            final String finalRequestBody = requestBody;
            addFuture.thenAccept(success -> {
                    long executionTime = System.currentTimeMillis() - startTime;
                    if (success) {
                        // 创建同步任务（使用玩家名）
                        
                        JsonObject result = new JsonObject();
                        result.addProperty("name", name);
                        result.addProperty("added", true);
                        result.addProperty("uuid_pending", true); // 表示UUID将在玩家登录时补充
                        result.addProperty("message", "玩家已添加到白名单，UUID将在首次登录时自动补充");

                        // 玩家认证启用时, 随回执签发绑定该用户名的一次性注册码 (前端展示给管理员转交玩家)
                        if (config != null && config.isPlayerAuthEnabled() && playerAuthService != null) {
                            String regCode = playerAuthService.generateRegistrationCode(name);
                            if (regCode != null) {
                                result.addProperty("registration_code", regCode);
                                result.addProperty("code_expires_minutes", config.getPlayerAuthCodeExpiryMinutes());
                            }
                        }

                        sendJsonResponse(response, 201, ApiResponse.success(result, "玩家添加成功"));
                        logOperation("ADD", null, name, request, finalRequestBody, 201, executionTime);
                    } else {
                        sendJsonResponse(response, 409, ApiResponse.error("玩家已在白名单中或添加失败"));
                        logOperation("ADD", null, name, request, finalRequestBody, 409, executionTime);
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("添加玩家失败: {}", name, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("添加玩家失败"));
                    logOperation("ADD", null, name, request, finalRequestBody, 500, System.currentTimeMillis() - startTime);
                    return null;
                })
                .join(); // 等待异步操作完成
            
        } catch (Exception e) {
            logger.error("处理添加玩家请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
            logOperation("ADD", null, null, request, requestBody, 500, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 处理POST /api/v1/whitelist/regcode - 为指定玩家名签发一次性注册码 (仅发码, 不加白)。
     *
     * 与 handleAddPlayer "加白即发码" 解耦: 玩家在问卷站审核通过后早已被加白, 此时再调
     * /v1/whitelist 会因已在白名单撞 409 而拿不到码 (见 handleAddPlayer 409 分支)。故单开此端点
     * 直接调 generateRegistrationCode (内部作废该名旧未用码 + 插入新码), 与白名单状态无关,
     * 供问卷后端在玩家自助领码时以服务端身份调用。非公开端点: 经 ApiRouter 鉴权 (X-API-Key /
     * 管理员 JWT), 不对公网裸奔。
     */
    public void handleIssueRegistrationCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long startTime = System.currentTimeMillis();
        String requestBody = null;
        try {
            requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();

            if (!json.has("name")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: name"));
                logOperation("GENCODE", null, null, request, requestBody, 400, System.currentTimeMillis() - startTime);
                return;
            }

            String name = json.get("name").getAsString();
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效"));
                logOperation("GENCODE", null, name, request, requestBody, 400, System.currentTimeMillis() - startTime);
                return;
            }

            // 认证未启用时无码可发: 明确 409, 杜绝静默返回空码让调用方误判为成功
            if (config == null || !config.isPlayerAuthEnabled() || playerAuthService == null) {
                sendJsonResponse(response, 409, ApiResponse.error("玩家认证未启用, 无法签发注册码"));
                logOperation("GENCODE", null, name, request, requestBody, 409, System.currentTimeMillis() - startTime);
                return;
            }

            String regCode = playerAuthService.generateRegistrationCode(name);
            if (regCode == null) {
                // generateRegistrationCode 内部 DB 异常已 fail-closed 返回 null
                sendJsonResponse(response, 500, ApiResponse.error("生成注册码失败"));
                logOperation("GENCODE", null, name, request, requestBody, 500, System.currentTimeMillis() - startTime);
                return;
            }

            JsonObject result = new JsonObject();
            result.addProperty("name", name);
            result.addProperty("registration_code", regCode);
            result.addProperty("code_expires_minutes", config.getPlayerAuthCodeExpiryMinutes());
            sendJsonResponse(response, 200, ApiResponse.success(result, "注册码已生成"));
            // 明文码绝不入日志: logOperation 仅写请求体 ({name}), 响应里的码不落库
            logOperation("GENCODE", null, name, request, requestBody, 200, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            logger.error("处理签发注册码请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
            logOperation("GENCODE", null, null, request, requestBody, 500, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 处理DELETE /api/v1/whitelist/{uuid} - 从白名单移除玩家
     */
    public void handleRemovePlayer(HttpServletRequest request, HttpServletResponse response, String uuid) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            // 验证UUID格式
            if (!isValidUuid(uuid)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("UUID格式无效"));
                logOperation("REMOVE", uuid, null, request, null, 400, System.currentTimeMillis() - startTime);
                return;
            }
            
            // 先获取玩家信息
            whitelistManager.getPlayerByUuid(uuid)
                .thenCompose(playerOpt -> {
                    if (playerOpt.isEmpty()) {
                        sendJsonResponse(response, 404, ApiResponse.notFound("玩家不存在"));
                        logOperation("REMOVE", uuid, null, request, null, 404, System.currentTimeMillis() - startTime);
                        return CompletableFuture.completedFuture(false);
                    }
                    
                    WhitelistEntry player = playerOpt.get();
                    return whitelistManager.removePlayer(uuid)
                        .thenApply(success -> {
                            long executionTime = System.currentTimeMillis() - startTime;
                            if (success) {
                                // 创建同步任务
                                
                                JsonObject result = new JsonObject();
                                result.addProperty("uuid", uuid);
                                result.addProperty("name", player.getName());
                                result.addProperty("removed", true);
                                
                                sendJsonResponse(response, 200, ApiResponse.success(result, "玩家移除成功"));
                                logOperation("REMOVE", uuid, player.getName(), request, null, 200, executionTime);
                                return true;
                            } else {
                                sendJsonResponse(response, 400, ApiResponse.badRequest("移除玩家失败"));
                                logOperation("REMOVE", uuid, player.getName(), request, null, 400, executionTime);
                                return false;
                            }
                        });
                })
                .exceptionally(throwable -> {
                    logger.error("移除玩家失败: {}", uuid, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("移除玩家失败"));
                    logOperation("REMOVE", uuid, null, request, null, 500, System.currentTimeMillis() - startTime);
                    return false;
                })
                .join(); // 等待异步操作完成
            
        } catch (Exception e) {
            logger.error("处理移除玩家请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理DELETE /api/v1/whitelist/by-name/{name} - 通过玩家名称从白名单移除玩家
     * 用于 UUID 为 null 的情况
     */
    public void handleRemovePlayerByName(HttpServletRequest request, HttpServletResponse response, String playerName) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            // 解码URL编码的名称
            final String name = java.net.URLDecoder.decode(playerName, "UTF-8");
            
            // 记录日志以便调试
            logger.info("删除玩家请求 - 原始名称: {}, 解码后: {}", playerName, name);
            
            // 验证玩家名称
            if (!isValidPlayerName(name)) {
                logger.warn("玩家名称格式无效: {} (长度: {})", name, name.length());
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效: " + name));
                logOperation("REMOVE", null, name, request, null, 400, System.currentTimeMillis() - startTime);
                return;
            }
            
            // 先通过名称获取玩家信息
            whitelistManager.getPlayerByName(name)
                .thenCompose(playerOpt -> {
                    if (playerOpt.isEmpty()) {
                        sendJsonResponse(response, 404, ApiResponse.notFound("玩家不存在"));
                        logOperation("REMOVE", null, name, request, null, 404, System.currentTimeMillis() - startTime);
                        return CompletableFuture.completedFuture(false);
                    }
                    
                    WhitelistEntry player = playerOpt.get();
                    final String playerUuid = player.getUuid();
                    
                    // 如果有 UUID,使用 UUID 删除;否则通过 name 删除
                    CompletableFuture<Boolean> removeFuture;
                    if (playerUuid != null && !playerUuid.isEmpty() && !playerUuid.equals("null")) {
                        removeFuture = whitelistManager.removePlayer(playerUuid);
                    } else {
                        // UUID 为 null 或 "null" 字符串,使用名称删除
                        removeFuture = whitelistManager.removePlayerByName(name);
                    }
                    
                    return removeFuture.thenApply(success -> {
                            long executionTime = System.currentTimeMillis() - startTime;
                            if (success) {
                                // 创建同步任务 - 从JSON文件删除
                                if (playerUuid != null && !playerUuid.isEmpty()) {
                                }
                                // UUID 为 null 时也已经删除了,无需额外同步
                                
                                JsonObject result = new JsonObject();
                                result.addProperty("name", name);
                                result.addProperty("uuid", playerUuid);
                                result.addProperty("removed", true);
                                
                                sendJsonResponse(response, 200, ApiResponse.success(result, "玩家移除成功"));
                                logOperation("REMOVE", playerUuid, name, request, null, 200, executionTime);
                                return true;
                            } else {
                                sendJsonResponse(response, 400, ApiResponse.badRequest("移除玩家失败"));
                                logOperation("REMOVE", playerUuid, name, request, null, 400, executionTime);
                                return false;
                            }
                        });
                })
                .exceptionally(throwable -> {
                    logger.error("移除玩家失败: {}", name, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("移除玩家失败"));
                    logOperation("REMOVE", null, name, request, null, 500, System.currentTimeMillis() - startTime);
                    return false;
                })
                .join(); // 等待异步操作完成
            
        } catch (Exception e) {
            logger.error("处理移除玩家请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
            logOperation("REMOVE", null, null, request, null, 500, System.currentTimeMillis() - startTime);
        }
    }
    
    /**
     * 处理PUT /api/v1/whitelist/by-name/{name}/status - 启用/禁用某条白名单。
     * 请求体: {"is_active": true|false}。禁用后该玩家进服将被拒, 并展示"管理员已关闭访问权限"专属提示。
     * 按名定位 (与删除端点一致), 因 UUID 待补充的条目 uuid 为空。
     */
    public void handleSetActive(HttpServletRequest request, HttpServletResponse response, String playerName) throws IOException {
        long startTime = System.currentTimeMillis();
        String requestBody = null;
        try {
            final String name = java.net.URLDecoder.decode(playerName, "UTF-8");
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效: " + name));
                logOperation("SET_ACTIVE", null, name, request, null, 400, System.currentTimeMillis() - startTime);
                return;
            }

            requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            if (!json.has("is_active") || json.get("is_active").isJsonNull()) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: is_active"));
                logOperation("SET_ACTIVE", null, name, request, requestBody, 400, System.currentTimeMillis() - startTime);
                return;
            }
            final boolean active = json.get("is_active").getAsBoolean();
            final String finalRequestBody = requestBody;

            whitelistManager.setActiveByName(name, active)
                .thenAccept(success -> {
                    long executionTime = System.currentTimeMillis() - startTime;
                    if (Boolean.TRUE.equals(success)) {
                        JsonObject result = new JsonObject();
                        result.addProperty("name", name);
                        result.addProperty("is_active", active);
                        sendJsonResponse(response, 200, ApiResponse.success(result, active ? "已启用" : "已禁用"));
                        logOperation("SET_ACTIVE", null, name, request, finalRequestBody, 200, executionTime);
                    } else {
                        sendJsonResponse(response, 404, ApiResponse.notFound("玩家不存在"));
                        logOperation("SET_ACTIVE", null, name, request, finalRequestBody, 404, executionTime);
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("设置白名单启用状态失败: {}", name, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("设置启用状态失败"));
                    logOperation("SET_ACTIVE", null, name, request, finalRequestBody, 500, System.currentTimeMillis() - startTime);
                    return null;
                })
                .join();

        } catch (Exception e) {
            logger.error("处理设置启用状态请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
            logOperation("SET_ACTIVE", null, null, request, requestBody, 500, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 处理 POST /api/v1/whitelist/by-name/{name}/reset-auth - 重置该玩家的密码与免密状态。
     *
     * 清掉密码记录并吊销设备绑定; 在线的同名玩家原地降级为未认证。玩家此后须重新 /register,
     * 并重新 /enroll 才能恢复免密。
     *
     * 用 POST 而非 DELETE: DELETE 分支按 startsWith("/by-name/") 截取玩家名, 会把 "{name}/reset-auth"
     * 整段当成玩家名, 落到删除白名单的处理器上。
     */
    public void handleResetPlayerAuth(HttpServletRequest request, HttpServletResponse response,
                                      String playerName) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            final String name = java.net.URLDecoder.decode(playerName, "UTF-8");
            if (!isValidPlayerName(name)) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名称格式无效: " + name));
                logOperation("RESET_AUTH", null, name, request, null, 400, System.currentTimeMillis() - startTime);
                return;
            }
            if (playerAuthAdminService == null) {
                sendJsonResponse(response, 503, ApiResponse.error("玩家认证系统未就绪"));
                logOperation("RESET_AUTH", null, name, request, null, 503, System.currentTimeMillis() - startTime);
                return;
            }

            playerAuthAdminService.reset(name)
                .thenAccept(outcome -> {
                    long executionTime = System.currentTimeMillis() - startTime;
                    if (!outcome.isAnythingCleared()) {
                        sendJsonResponse(response, 404, ApiResponse.notFound("该玩家没有认证记录"));
                        logOperation("RESET_AUTH", null, name, request, null, 404, executionTime);
                        return;
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("name", name);
                    result.addProperty("password_cleared", outcome.isPasswordCleared());
                    result.addProperty("device_revoked", outcome.isDeviceRevoked());
                    sendJsonResponse(response, 200, ApiResponse.success(result, "已重置该玩家的密码与免密状态"));
                    logOperation("RESET_AUTH", null, name, request, null, 200, executionTime);
                })
                .exceptionally(throwable -> {
                    logger.error("重置玩家认证失败: {}", name, throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("重置玩家认证失败"));
                    logOperation("RESET_AUTH", null, name, request, null, 500, System.currentTimeMillis() - startTime);
                    return null;
                })
                .join();

        } catch (Exception e) {
            logger.error("处理重置玩家认证请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
            logOperation("RESET_AUTH", null, null, request, null, 500, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 处理GET /api/v1/whitelist/stats - 获取白名单统计信息
     */
    public void handleGetStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            whitelistManager.getStats()
                .thenAccept(stats -> {
                    sendJsonResponse(response, 200, ApiResponse.success(stats));
                })
                .exceptionally(throwable -> {
                    logger.error("获取白名单统计失败", throwable);
                    sendJsonResponse(response, 500, ApiResponse.error("获取统计信息失败"));
                    return null;
                })
                .join(); // 等待异步操作完成
            
        } catch (Exception e) {
            logger.error("处理统计信息请求失败", e);
            sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
        }
    }
    
    /**
     * 处理POST /api/v1/whitelist/sync - 手动触发同步
     * 注意: JSON同步功能已移除,此接口保留仅用于向后兼容
     */
    public void handleTriggerSync(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("message", "JSON同步功能已移除,系统现在使用纯数据库模式");
        result.addProperty("mode", "database-only");
        sendJsonResponse(response, 200, ApiResponse.success(result));
    }
    
    /**
     * 处理GET /api/v1/whitelist/sync/status - 获取同步状态
     * 注意: JSON同步功能已移除,此接口保留仅用于向后兼容
     */
    public void handleGetSyncStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject status = new JsonObject();
        status.addProperty("mode", "database-only");
        status.addProperty("json_sync", "disabled");
        status.addProperty("message", "系统运行在纯数据库模式");
        sendJsonResponse(response, 200, ApiResponse.success(status));
    }
    
    /**
     * 发送JSON响应
     */
    private void sendJsonResponse(HttpServletResponse response, int statusCode, ApiResponse<?> apiResponse) {
        try {
            response.setStatus(statusCode);
            // 与 HTTP 状态码对齐: ApiResponse 的工厂方法只能给出 200/500 的默认值, 真实状态码
            // (201/207/409/429 等) 到这一层才知道。不同步的话客户端读 body.code 会被误导。
            if (apiResponse != null) {
                apiResponse.setCode(statusCode);
            }
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(gson.toJson(apiResponse));
            response.getWriter().flush();
        } catch (IOException e) {
            logger.error("发送JSON响应失败", e);
        }
    }
    
    /**
     * 读取请求体
     */
    private String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = request.getReader().readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
    
    /**
     * 获取整数参数
     */
    private int getIntParameter(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * 验证玩家名称格式
     * 注意: 为了兼容性,这里只检查基本的长度和非空
     * Minecraft 官方只允许 a-zA-Z0-9_ 但某些服务器可能有特殊配置
     */
    private boolean isValidPlayerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        // 基本长度检查 (1-64字符,放宽限制以兼容特殊情况)
        int length = name.length();
        return length >= 1 && length <= 64;
    }
    
    /**
     * 处理POST /api/v1/whitelist/batch - 批量操作
     */
    public void handleBatchOperation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 读取请求体
            String requestBody = readRequestBody(request);
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 参数验证 (source 仅 add 操作必需, 故不在此强制)
            if (!json.has("operation") || !json.has("players")) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: operation, players"));
                return;
            }

            String operation = json.get("operation").getAsString();
            JsonArray playersArray = json.getAsJsonArray("players");

            if (playersArray.size() == 0) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("玩家列表不能为空"));
                return;
            }

            if (playersArray.size() > 100) {
                sendJsonResponse(response, 400, ApiResponse.badRequest("批量操作最多支持100个玩家"));
                return;
            }

            // 批量启用/禁用: 按玩家名定位, 无需 source/addedBy/时间戳, 提前分流
            if ("enable".equalsIgnoreCase(operation) || "disable".equalsIgnoreCase(operation)) {
                handleBatchSetActive(response, playersArray, "enable".equalsIgnoreCase(operation));
                return;
            }

            // 获取操作者信息
            String addedByName = json.has("added_by_name") ? json.get("added_by_name").getAsString() : "API";
            String addedByUuid = json.has("added_by_uuid") ? json.get("added_by_uuid").getAsString() : "00000000-0000-0000-0000-000000000000";

            if ("add".equalsIgnoreCase(operation)) {
                // source 与时间戳仅 add 操作需要
                if (!json.has("source")) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("缺少必需参数: source"));
                    return;
                }
                WhitelistEntry.Source source;
                try {
                    source = WhitelistEntry.Source.fromString(json.get("source").getAsString());
                } catch (IllegalArgumentException e) {
                    sendJsonResponse(response, 400, ApiResponse.badRequest("无效的来源类型: " + json.get("source").getAsString()));
                    return;
                }
                LocalDateTime addedAt = json.has("added_at") ?
                    LocalDateTime.parse(json.get("added_at").getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME) :
                    LocalDateTime.now();

                // 批量添加
                BatchOperation batchOperation = new BatchOperation(
                    BatchOperation.OperationType.ADD, addedByName, addedByUuid
                );
                
                // 解析玩家列表
                for (int i = 0; i < playersArray.size(); i++) {
                    JsonObject playerObj = playersArray.get(i).getAsJsonObject();
                    
                    if (!playerObj.has("name")) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("玩家信息缺少name字段"));
                        return;
                    }
                    
                    String name = playerObj.get("name").getAsString();
                    String providedUuid = playerObj.has("uuid") ? playerObj.get("uuid").getAsString() : null;
                    
                    if (!isValidPlayerName(name)) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("无效的玩家名: " + name));
                        return;
                    }
                    
                    // 生成或验证UUID
                    String uuid;
                    try {
                        uuid = UuidUtils.getOrGenerateUuid(name, providedUuid);
                    } catch (IllegalArgumentException e) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("玩家名不能为空: " + name));
                        return;
                    }
                    
                    batchOperation.addEntry(name, uuid, source, addedAt);
                }
                
                // 执行批量添加
                whitelistManager.executeBatchOperation(batchOperation)
                    .thenAccept(result -> {
                        // 创建同步任务
                        if (result.getSuccessCount() > 0) {
                        }
                        
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("operation", "add");
                        responseData.addProperty("total_requested", result.getTotalRequested());
                        responseData.addProperty("success_count", result.getSuccessCount());
                        responseData.addProperty("failure_count", result.getFailureCount());
                        responseData.addProperty("success_rate", result.getSuccessRate());

                        // 批量加白不逐个签发注册码 (单条加白与游戏内 /accesshub auth gencode 才发);
                        // 明示提示调用方, 杜绝静默缺口。补发: 游戏内 /accesshub auth gencode 批量为未注册白名单玩家发码。
                        if (config != null && config.isPlayerAuthEnabled() && result.getSuccessCount() > 0) {
                            responseData.addProperty("registration_codes_pending", true);
                            responseData.addProperty("registration_codes_hint",
                                    "批量加白未逐个签发注册码, 请在游戏内执行 /accesshub auth gencode 为未注册白名单玩家批量补发");
                        }

                        if (!result.getErrors().isEmpty()) {
                            responseData.add("errors", gson.toJsonTree(result.getErrors()));
                        }
                        
                        if (result.isCompleteSuccess()) {
                            sendJsonResponse(response, 200, ApiResponse.success(responseData, "批量添加完成"));
                        } else if (result.isCompleteFailure()) {
                            sendJsonResponse(response, 400, ApiResponse.badRequest("批量添加全部失败"));
                        } else {
                            sendJsonResponse(response, 207, ApiResponse.success(responseData, "批量添加部分成功"));
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("批量添加失败", throwable);
                        sendJsonResponse(response, 500, ApiResponse.error("批量添加失败"));
                        return null;
                    })
                    .join(); // 等待异步操作完成
                    
            } else if ("remove".equalsIgnoreCase(operation)) {
                // 批量删除
                List<String> uuids = new ArrayList<>();
                
                // 解析UUID列表
                for (int i = 0; i < playersArray.size(); i++) {
                    JsonObject playerObj = playersArray.get(i).getAsJsonObject();
                    
                    if (!playerObj.has("uuid")) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("删除操作需要uuid字段"));
                        return;
                    }
                    
                    String uuid = playerObj.get("uuid").getAsString();
                    if (!isValidUuid(uuid)) {
                        sendJsonResponse(response, 400, ApiResponse.badRequest("无效的UUID: " + uuid));
                        return;
                    }
                    
                    uuids.add(uuid);
                }
                
                // 执行批量删除
                whitelistManager.batchRemovePlayersByUuid(uuids, addedByName, addedByUuid)
                    .thenAccept(result -> {
                        // 创建同步任务
                        if (result.getSuccessCount() > 0) {
                        }
                        
                        JsonObject responseData = new JsonObject();
                        responseData.addProperty("operation", "remove");
                        responseData.addProperty("total_requested", result.getTotalRequested());
                        responseData.addProperty("success_count", result.getSuccessCount());
                        responseData.addProperty("failure_count", result.getFailureCount());
                        responseData.addProperty("success_rate", result.getSuccessRate());
                        
                        if (!result.getErrors().isEmpty()) {
                            responseData.add("errors", gson.toJsonTree(result.getErrors()));
                        }
                        
                        if (result.isCompleteSuccess()) {
                            sendJsonResponse(response, 200, ApiResponse.success(responseData, "批量删除完成"));
                        } else if (result.isCompleteFailure()) {
                            sendJsonResponse(response, 400, ApiResponse.badRequest("批量删除全部失败"));
                        } else {
                            sendJsonResponse(response, 207, ApiResponse.success(responseData, "批量删除部分成功"));
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("批量删除失败", throwable);
                        sendJsonResponse(response, 500, ApiResponse.error("批量删除失败"));
                        return null;
                    })
                    .join(); // 等待异步操作完成
                    
            } else {
                sendJsonResponse(response, 400, ApiResponse.badRequest("不支持的操作类型: " + operation));
            }
            
        } catch (Exception e) {
             logger.error("处理批量操作请求失败", e);
             sendJsonResponse(response, 500, ApiResponse.error("服务器内部错误"));
         }
     }
      
      /**
       * 批量启用/禁用 (由 handleBatchOperation 的 enable/disable 分支调用)。玩家按名定位。
       */
      private void handleBatchSetActive(HttpServletResponse response, JsonArray playersArray, boolean active) {
          List<String> names = new ArrayList<>();
          for (int i = 0; i < playersArray.size(); i++) {
              JsonObject playerObj = playersArray.get(i).getAsJsonObject();
              if (!playerObj.has("name")) {
                  sendJsonResponse(response, 400, ApiResponse.badRequest("启用/禁用操作需要name字段"));
                  return;
              }
              String name = playerObj.get("name").getAsString();
              if (!isValidPlayerName(name)) {
                  sendJsonResponse(response, 400, ApiResponse.badRequest("无效的玩家名: " + name));
                  return;
              }
              names.add(name);
          }

          whitelistManager.batchSetActiveByName(names, active)
              .thenAccept(result -> {
                  JsonObject responseData = new JsonObject();
                  responseData.addProperty("operation", active ? "enable" : "disable");
                  responseData.addProperty("total_requested", result.getTotalRequested());
                  responseData.addProperty("success_count", result.getSuccessCount());
                  responseData.addProperty("failure_count", result.getFailureCount());
                  responseData.addProperty("success_rate", result.getSuccessRate());
                  if (!result.getErrors().isEmpty()) {
                      responseData.add("errors", gson.toJsonTree(result.getErrors()));
                  }

                  if (result.isCompleteSuccess()) {
                      sendJsonResponse(response, 200, ApiResponse.success(responseData, active ? "批量启用完成" : "批量禁用完成"));
                  } else if (result.isCompleteFailure()) {
                      sendJsonResponse(response, 400, ApiResponse.badRequest(active ? "批量启用全部失败" : "批量禁用全部失败"));
                  } else {
                      sendJsonResponse(response, 207, ApiResponse.success(responseData, active ? "批量启用部分成功" : "批量禁用部分成功"));
                  }
              })
              .exceptionally(throwable -> {
                  logger.error("批量设置启用状态失败", throwable);
                  sendJsonResponse(response, 500, ApiResponse.error("批量设置启用状态失败"));
                  return null;
              })
              .join();
      }

      /**
       * 验证UUID格式
       */
      private boolean isValidUuid(String uuid) {
          return uuid != null && uuid.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
      }
      
      /**
       * 记录操作日志
       */
      private void logOperation(String operationType, String targetUuid, String targetName, 
                                HttpServletRequest request, String requestData, 
                                int responseStatus, long executionTime) {
          try {
              String operatorIp = getClientIp(request);
              String operatorAgent = request.getHeader("User-Agent");
              
              boolean recorded = operationLogDao.logOperation(
                  operationType,
                  targetUuid,
                  targetName,
                  operatorIp,
                  operatorAgent,
                  requestData,
                  responseStatus,
                  executionTime
              );
              // 写日志失败不该影响业务响应, 但必须留痕。此前这里丢弃返回值, 加上 DAO 把
              // SQLException 吞成 false, 导致 CHECK 约束漏配类型时整类日志被静默丢弃却无人察觉。
              if (!recorded) {
                  logger.warn("操作日志写入失败(已忽略, 不影响业务): type={} target={}",
                          operationType, targetName);
              }
          } catch (Exception e) {
              logger.error("记录操作日志失败", e);
          }
      }
      
      /**
       * 获取客户端真实IP
       */
      private String getClientIp(HttpServletRequest request) {
          String ip = request.getHeader("X-Forwarded-For");
          if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
              ip = request.getHeader("X-Real-IP");
          }
          if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
              ip = request.getRemoteAddr();
          }
          // 如果是多个IP，取第一个
          if (ip != null && ip.contains(",")) {
              ip = ip.split(",")[0].trim();
          }
          return ip;
      }
  }