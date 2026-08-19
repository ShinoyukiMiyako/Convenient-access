package com.shinoyuki.accesshub.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.config.AccessHubConfig;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API路由器（简化版）
 * 处理白名单管理和用户注册相关的API路由
 */
public class ApiRouter extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ApiRouter.class);
    
    private final WhitelistApiController whitelistController;
    private final UserApiController userController;
    private final PlayerDataHandler playerDataController;
    private final ServerInfoHandler serverInfoHandler;
    private final ItemIconHandler itemIconHandler;
    private final NetworkInfoHandler networkInfoHandler;
    private final ChatBridgeHandler chatBridgeHandler;
    private final OperationLogApiController operationLogController;
    private final BindingApiController bindingController;
    private final PackPublicApiHandler packPublicApiHandler;
    private final PackAdminApiController packAdminController;
    private final PackUploadController packUploadController;
    private AdminAuthController adminAuthController;
    private final AccessHubConfig configManager;

    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController,
                     PlayerDataHandler playerDataController, ServerInfoHandler serverInfoHandler,
                     ItemIconHandler itemIconHandler, NetworkInfoHandler networkInfoHandler,
                     ChatBridgeHandler chatBridgeHandler,
                     OperationLogApiController operationLogController,
                     BindingApiController bindingController,
                     AdminAuthController adminAuthController, AccessHubConfig configManager) {
        this(whitelistController, userController, playerDataController, serverInfoHandler,
                itemIconHandler, networkInfoHandler, chatBridgeHandler, operationLogController,
                bindingController, null, null, null, adminAuthController, configManager);
    }

    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController,
                     PlayerDataHandler playerDataController, ServerInfoHandler serverInfoHandler,
                     ItemIconHandler itemIconHandler, NetworkInfoHandler networkInfoHandler,
                     ChatBridgeHandler chatBridgeHandler,
                     OperationLogApiController operationLogController,
                     BindingApiController bindingController,
                     PackPublicApiHandler packPublicApiHandler,
                     AdminAuthController adminAuthController, AccessHubConfig configManager) {
        this(whitelistController, userController, playerDataController, serverInfoHandler,
                itemIconHandler, networkInfoHandler, chatBridgeHandler, operationLogController,
                bindingController, packPublicApiHandler, null, null, adminAuthController, configManager);
    }

    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController,
                     PlayerDataHandler playerDataController, ServerInfoHandler serverInfoHandler,
                     ItemIconHandler itemIconHandler, NetworkInfoHandler networkInfoHandler,
                     ChatBridgeHandler chatBridgeHandler,
                     OperationLogApiController operationLogController,
                     BindingApiController bindingController,
                     PackPublicApiHandler packPublicApiHandler,
                     PackAdminApiController packAdminController,
                     AdminAuthController adminAuthController, AccessHubConfig configManager) {
        this(whitelistController, userController, playerDataController, serverInfoHandler,
                itemIconHandler, networkInfoHandler, chatBridgeHandler, operationLogController,
                bindingController, packPublicApiHandler, packAdminController, null,
                adminAuthController, configManager);
    }

    public ApiRouter(WhitelistApiController whitelistController, UserApiController userController,
                     PlayerDataHandler playerDataController, ServerInfoHandler serverInfoHandler,
                     ItemIconHandler itemIconHandler, NetworkInfoHandler networkInfoHandler,
                     ChatBridgeHandler chatBridgeHandler,
                     OperationLogApiController operationLogController,
                     BindingApiController bindingController,
                     PackPublicApiHandler packPublicApiHandler,
                     PackAdminApiController packAdminController,
                     PackUploadController packUploadController,
                     AdminAuthController adminAuthController, AccessHubConfig configManager) {
        this.whitelistController = whitelistController;
        this.userController = userController;
        this.playerDataController = playerDataController;
        this.serverInfoHandler = serverInfoHandler;
        this.itemIconHandler = itemIconHandler;
        this.networkInfoHandler = networkInfoHandler;
        this.chatBridgeHandler = chatBridgeHandler;
        this.operationLogController = operationLogController;
        this.bindingController = bindingController;
        this.packPublicApiHandler = packPublicApiHandler;
        this.packAdminController = packAdminController;
        this.packUploadController = packUploadController;
        this.adminAuthController = adminAuthController;
        this.configManager = configManager;
    }
    
    /**
     * 设置管理员认证控制器（用于延迟初始化）
     */
    public void setAdminAuthController(AdminAuthController adminAuthController) {
        this.adminAuthController = adminAuthController;
    }
    
    /**
     * 验证API请求的认证
     */
    private boolean isAuthenticated(HttpServletRequest request, String path) {
        // 如果认证被禁用，直接通过
        if (!configManager.isAuthEnabled()) {
            return true;
        }
        
        // 公开的端点不需要认证
        if (isPublicEndpoint(request, path)) {
            return true;
        }
        
        // 检查JWT Token (用于管理员已登录的请求)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwtToken = authHeader.substring(7);
            // 验证JWT token
            if (adminAuthController != null) {
                try {
                    com.shinoyuki.accesshub.auth.AdminUser user = 
                        adminAuthController.getAdminAuthService().validateToken(jwtToken);
                    if (user != null) {
                        // JWT token 有效
                        request.setAttribute("currentUser", user);
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("JWT token validation failed: {}", e.getMessage());
                }
            }
        }
        
        // 检查API Token (用于非管理员的API访问，constant-time 比较防 timing attack)
        // Pack management changes the client release state and must never inherit
        // the backend/Bot API-key privilege used by other private endpoints.
        if (isPackAdministrationPath(path)) {
            return false;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null) {
            String validToken = configManager.getApiToken();
            if (validToken != null && !validToken.isEmpty()
                    && java.security.MessageDigest.isEqual(
                            validToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPackAdministrationPath(String path) {
        return path != null && path.startsWith("/api/v1/pack/");
    }

    /**
     * 判断是否为公开端点（不需要认证）
     */
    private boolean isPublicEndpoint(HttpServletRequest request, String path) {
        if (path == null) {
            return false;
        }
        if (path.equals("/api/v1/admin/login") ||
               path.equals("/api/v1/admin/register") ||
               // 物品图标必须公开: <img> 标签无法携带 Authorization/X-API-Key 头
               path.equals("/api/v1/item-icon") ||
               // 线路状态供玩家自查页面匿名访问; 该端点只输出各线路人数与连接地址,
               // 不含玩家名单与客户端 IP, 公开无隐私风险
               path.equals("/api/v1/net/nodes")) {
            return true;
        }

        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return path.equals("/api/v1/pack/latest") || extractManifestVersion(path) != null;
    }

    private static String extractManifestVersion(String path) {
        String prefix = "/api/v1/pack/manifest/";
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String version = path.substring(prefix.length());
        return PackPublicService.isValidVersion(version) ? version : null;
    }

    private static String extractPathSegment(String path, String prefix, String suffix) {
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        int end = path.length() - suffix.length();
        if (end <= prefix.length()) {
            return null;
        }
        String segment = path.substring(prefix.length(), end);
        return segment.indexOf('/') < 0 ? segment : null;
    }

    /**
     * 发送认证失败响应
     */
    private void sendAuthFailedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"Unauthorized: Invalid API key or token\"}");
        response.getWriter().flush();
    }
    
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("PUT request to: {}", path);

        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }

        try {
            String versionId = extractPathSegment(path, "/api/v1/pack/versions/", "");
            String entryId = extractPathSegment(path, "/api/v1/pack/entries/", "");
            if (versionId != null) {
                if (packAdminController != null) {
                    packAdminController.handleUpdateVersion(versionId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (entryId != null) {
                if (packAdminController != null) {
                    packAdminController.handleUpdateEntry(entryId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            // 启用/禁用某条白名单: PUT /api/v1/whitelist/by-name/{name}/status
            else if (path != null && path.startsWith("/api/v1/whitelist/by-name/") && path.endsWith("/status")) {
                String name = path.substring("/api/v1/whitelist/by-name/".length(), path.length() - "/status".length());
                whitelistController.handleSetActive(request, response, name);
            } else {
                send404Response(response, "Endpoint not found");
            }
        } catch (Exception e) {
            logger.error("Error handling PUT request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("GET request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
            String versionEntriesId = extractPathSegment(path, "/api/v1/pack/versions/", "/entries");
            String versionDiffId = extractPathSegment(path, "/api/v1/pack/versions/", "/diff");
            if (path == null) {
                send404Response(response, "API endpoint not found");
            }
            // 当前发布版本指针与不可变历史清单均在玩家登录前公开读取
            else if (path.equals("/api/v1/pack/latest")) {
                if (packPublicApiHandler != null) {
                    packPublicApiHandler.handleLatest(request, response);
                } else {
                    send503Response(response, "Pack manifest service not available");
                }
            }
            else if (extractManifestVersion(path) != null) {
                if (packPublicApiHandler != null) {
                    packPublicApiHandler.handleManifest(request, response, extractManifestVersion(path));
                } else {
                    send503Response(response, "Pack manifest service not available");
                }
            }
            else if (path.equals("/api/v1/pack/versions")) {
                if (packAdminController != null) {
                    packAdminController.handleListVersions(request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (versionEntriesId != null) {
                if (packAdminController != null) {
                    packAdminController.handleListEntries(versionEntriesId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (versionDiffId != null) {
                if (packAdminController != null) {
                    packAdminController.handleDiff(versionDiffId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            // 操作日志相关路由
            else if (path.startsWith("/api/v1/logs/operations")) {
                if (path.equals("/api/v1/logs/operations")) {
                    operationLogController.handleGetOperationLogs(request, response);
                } else if (path.equals("/api/v1/logs/operations/stats")) {
                    operationLogController.handleGetOperationStats(request, response);
                } else {
                    send404Response(response, "Endpoint not found");
                }
            }
            // 白名单相关路由
            else if (path.startsWith("/api/v1/whitelist")) {
                if (path.equals("/api/v1/whitelist")) {
                    whitelistController.handleGetWhitelist(request, response);
                } else if (path.equals("/api/v1/whitelist/stats")) {
                    whitelistController.handleGetStats(request, response);
                } else if (path.equals("/api/v1/whitelist/sync/status")) {
                    whitelistController.handleGetSyncStatus(request, response);
                } else {
                    send404Response(response, "Endpoint not found");
                }
            }
            // 玩家数据查询路由（使用查询参数）
            else if (path.equals("/api/v1/player")) {
                if (playerDataController != null) {
                    playerDataController.handleGetPlayerData(request, response);
                } else {
                    send503Response(response, "Player data handler not available");
                }
            }
            // 服务器性能监测路由 (Spark + JVM)
            else if (path.equals("/api/v1/server/performance")) {
                if (serverInfoHandler != null) {
                    serverInfoHandler.handleGetPerformance(request, response);
                } else {
                    send503Response(response, "Server info handler not available");
                }
            }
            // 在线玩家列表路由
            else if (path.equals("/api/v1/server/players")) {
                if (serverInfoHandler != null) {
                    serverInfoHandler.handleGetPlayers(request, response);
                } else {
                    send503Response(response, "Server info handler not available");
                }
            }
            // 线路状态路由 (公开, 各 frp/直连线路的实时人数)
            else if (path.equals("/api/v1/net/nodes")) {
                if (networkInfoHandler != null) {
                    networkInfoHandler.handleGetNodes(request, response);
                } else {
                    send503Response(response, "Network info handler not available");
                }
            }
            // 物品图标路由 (公开, 从 mod jar 抽贴图 PNG)
            else if (path.equals("/api/v1/item-icon")) {
                if (itemIconHandler != null) {
                    itemIconHandler.handle(request, response);
                } else {
                    send503Response(response, "Item icon handler not available");
                }
            }
            // 管理员信息查询路由
            else if (path.equals("/api/v1/admin/me")) {
                if (adminAuthController != null) {
                    adminAuthController.handleGetCurrentUser(request, response);
                } else {
                    send503Response(response, "Admin authentication service not available");
                }
            }
            // 个人识别码状态 (仅掩码前后缀与已绑 QQ, 明文只在签发时返回一次)
            else if (path.equals("/api/v1/admin/personal-code")) {
                bindingController.handleGetPersonalCode(request, response);
            }
            // QQ 绑定查询, Bot 在执行每条运维命令前用它确认发令人身份
            else if (path.equals("/api/v1/bot/binding")) {
                bindingController.handleBotGetBinding(request, response);
            }
            else {
                send404Response(response, "API endpoint not found");
            }
        } catch (Exception e) {
            logger.error("Error handling GET request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("POST request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
            String versionEntriesId = extractPathSegment(path, "/api/v1/pack/versions/", "/entries");
            String versionUploadId = extractPathSegment(path, "/api/v1/pack/versions/", "/upload");
            String versionPublishId = extractPathSegment(path, "/api/v1/pack/versions/", "/publish");
            String versionRollbackId = extractPathSegment(path, "/api/v1/pack/versions/", "/rollback");
            if (path == null) {
                send404Response(response, "API endpoint not found");
            }
            else if (path.equals("/api/v1/pack/versions")) {
                if (packAdminController != null) {
                    packAdminController.handleCreateVersion(request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (versionEntriesId != null) {
                if (packAdminController != null) {
                    packAdminController.handleAddEntry(versionEntriesId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (versionUploadId != null) {
                if (packUploadController != null) {
                    try {
                        packUploadController.handleUploadAsync(
                                PackAdminApiController.parsePositiveId(versionUploadId), request, response);
                    } catch (IllegalArgumentException exception) {
                        ApiSupport.sendJson(response, HttpServletResponse.SC_BAD_REQUEST,
                                ApiResponse.badRequest(exception.getMessage()));
                    }
                } else {
                    send503Response(response, "Pack upload service not available");
                }
            }
            else if (versionPublishId != null) {
                if (packAdminController != null) {
                    packAdminController.handlePublish(versionPublishId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            else if (versionRollbackId != null) {
                if (packAdminController != null) {
                    packAdminController.handleRollback(versionRollbackId, request, response);
                } else {
                    send503Response(response, "Pack administration service not available");
                }
            }
            // 白名单相关路由
             else if (path.startsWith("/api/v1/whitelist")) {
                 if (path.equals("/api/v1/whitelist")) {
                     whitelistController.handleAddPlayer(request, response);
                 } else if (path.equals("/api/v1/whitelist/batch")) {
                     whitelistController.handleBatchOperation(request, response);
                 } else if (path.equals("/api/v1/whitelist/regcode")) {
                     // 仅发码, 不加白; 非公开 -> 落在此鉴权分支内, 需 X-API-Key / 管理员 JWT
                     whitelistController.handleIssueRegistrationCode(request, response);
                 } else if (path.equals("/api/v1/whitelist/sync")) {
                     whitelistController.handleTriggerSync(request, response);
                 }
                 // 重置玩家密码与免密状态: POST /api/v1/whitelist/by-name/{name}/reset-auth
                 // 走 POST 而非 DELETE, 因 DELETE 分支按前缀截玩家名, 会把子路径一起吞掉
                 else if (path.startsWith("/api/v1/whitelist/by-name/") && path.endsWith("/reset-auth")) {
                     String name = path.substring("/api/v1/whitelist/by-name/".length(),
                             path.length() - "/reset-auth".length());
                     whitelistController.handleResetPlayerAuth(request, response, name);
                 } else {
                     send404Response(response, "Endpoint not found");
                 }
             }
             // 管理员认证路由
             else if (path.equals("/api/v1/admin/login")) {
                 if (adminAuthController != null) {
                     adminAuthController.handleLogin(request, response);
                 } else {
                     send503Response(response, "Admin authentication service not available");
                 }
             }
             else if (path.equals("/api/v1/admin/register")) {
                 if (adminAuthController != null) {
                     adminAuthController.handleRegister(request, response);
                 } else {
                     send503Response(response, "Admin authentication service not available");
                 }
             }
             // 令牌生成路由（简化版，需要管理员密码验证）
             else if (path.equals("/api/v1/admin/generate-token")) {
                 userController.handleGenerateToken(request, response);
             }
             // 个人识别码签发/重置, 明文仅此一次返回
             else if (path.equals("/api/v1/admin/personal-code")) {
                 bindingController.handleIssuePersonalCode(request, response);
             }
             // QQ Bot 凭识别码认领 QQ 号
             else if (path.equals("/api/v1/bot/bind")) {
                 bindingController.handleBotBind(request, response);
             }
             // 外部渠道 (QQ #say) 向游戏内公屏发言
             else if (path.equals("/api/v1/server/broadcast")) {
                 if (chatBridgeHandler != null) {
                     chatBridgeHandler.handleBroadcast(request, response);
                 } else {
                     send503Response(response, "Chat bridge handler not available");
                 }
             }
            else {
                send404Response(response, "API endpoint not found");
            }
        } catch (Exception e) {
            logger.error("Error handling POST request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        
        logger.debug("DELETE request to: {}", path);
        
        // 检查认证
        if (!isAuthenticated(request, path)) {
            sendAuthFailedResponse(response);
            return;
        }
        
        try {
             String entryId = extractPathSegment(path, "/api/v1/pack/entries/", "");
             if (path == null) {
                 send404Response(response, "API endpoint not found");
             }
             // 解除 QQ 绑定 (?qq=)
             else if (entryId != null) {
                 if (packAdminController != null) {
                     packAdminController.handleDeleteEntry(entryId, request, response);
                 } else {
                     send503Response(response, "Pack administration service not available");
                 }
             }
             else if (path.equals("/api/v1/bot/binding")) {
                 bindingController.handleBotUnbind(request, response);
             }
             // 白名单删除路由
             else if (path.startsWith("/api/v1/whitelist/by-name/")) {
                 // 通过名称删除
                 String name = path.substring("/api/v1/whitelist/by-name/".length());
                 whitelistController.handleRemovePlayerByName(request, response, name);
             } else if (path.startsWith("/api/v1/whitelist/")) {
                 // 通过 UUID 删除
                 String uuid = path.substring("/api/v1/whitelist/".length());
                 whitelistController.handleRemovePlayer(request, response, uuid);
             } else {
                 send404Response(response, "Endpoint not found");
             }
        } catch (Exception e) {
            logger.error("Error handling DELETE request to {}", path, e);
            send500Response(response, "Internal server error");
        }
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        // 设置CORS头
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setStatus(HttpServletResponse.SC_OK);
    }
    
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        String method = request.getMethod();
        
        switch (method.toUpperCase()) {
            case "GET":
                doGet(request, response);
                break;
            case "POST":
                doPost(request, response);
                break;
            case "PUT":
                doPut(request, response);
                break;
            case "DELETE":
                doDelete(request, response);
                break;
            case "OPTIONS":
                doOptions(request, response);
                break;
            default:
                send405Response(response, "Method not supported: " + method);
        }
    }
    
    private void send405Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 405, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send404Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 404, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send500Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 500, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
    
    private void send503Response(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String jsonResponse = String.format(
            "{\"success\": false, \"error\": {\"code\": 503, \"message\": \"%s\"}, \"timestamp\": %d}",
            message, System.currentTimeMillis()
        );
        
        response.getWriter().write(jsonResponse);
    }
}
