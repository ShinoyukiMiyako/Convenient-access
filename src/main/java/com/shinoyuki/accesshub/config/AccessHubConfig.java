package com.shinoyuki.accesshub.config;

import java.util.List;

import com.shinoyuki.accesshub.net.NodeDefinition;

/**
 * 配置访问接口。
 *
 * v2 起业务代码统一通过此接口读写配置，与具体的存储后端解耦：
 *  - v1 (Bukkit) 由 com.xaoxiao.convenientaccess.config 下的实现类承担，留在该包供历史构建参考
 *  - v3 (Forge 重写阶段) 将提供基于 Night Config 的 AccessHubConfigImpl
 *
 * 抽象目标: 让 v2 可复用层在 Bukkit 实现就绪前即可编译通过。
 */
public interface AccessHubConfig {

    void reload();

    // HTTP 服务
    boolean isHttpEnabled();
    int getHttpPort();
    String getHttpHost();
    int getMaxThreads();
    int getTimeout();

    // API 基础
    String getApiVersion();
    boolean isAuthEnabled();
    String getApiToken();
    void setApiToken(String token);
    String getTokenPrefix();

    // JWT 签名
    String getJwtSecret();
    void setJwtSecret(String secret);

    // 管理员密码
    String getAdminPassword();
    void setAdminPassword(String password);

    // 登录失败限制
    boolean isLoginAttemptLimitEnabled();
    int getLoginMaxAttempts();
    int getLoginLockDurationMinutes();

    // CORS
    boolean isCorsEnabled();
    List<String> getAllowedOrigins();

    // 白名单
    boolean isWhitelistEnabled();
    boolean isWhitelistStrictMode();
    String getWhitelistKickMessage();
    String getWhitelistDisabledMessage();
    String getContactInfo();
    int getTokenExpiryHours();
    boolean isAutoCleanupTokens();
    boolean isJoinNotificationEnabled();
    boolean isWelcomeMessageEnabled();
    String getWelcomeMessage();

    // 玩家离线认证 (游戏内强制登录, 与上方管理员 HTTP API 登录限流相互独立)
    boolean isPlayerAuthEnabled();
    int getPlayerAuthTimeoutSeconds();
    int getPlayerAuthMaxAttempts();   // 同一会话登录密码连续错误上限, 达到即踢下线 (重连重置, 不持久锁号)
    int getPlayerAuthLockMinutes();   // 已弃用: 改为会话内踢出后此项不再生效, 保留仅向后兼容旧配置
    int getPlayerAuthMinPasswordLength();
    boolean isPlayerAuthRejectWeakPassword();
    int getPlayerAuthCodeExpiryMinutes();

    // 免密登录二期 (DeviceAuth)
    boolean isDeviceAuthEnabled();
    int getDeviceAuthChallengeTimeoutSeconds();
    String getServerInstanceId();   // 签名域分隔用, 首启自动生成并持久化

    // 整合包版本门控
    boolean isPackVersionGateEnabled();
    String getPackVersionRejectMessage();

    // 整合包自研文件 OSS 上传
    boolean isPackOssEnabled();
    String getPackOssEndpoint();
    String getPackOssBucket();
    String getPackOssAccessKeyId();
    String getPackOssAccessKeySecret();
    String getPackOssPublicBaseUrl();

    // 数据库自动备份
    boolean isBackupEnabled();
    String getBackupSchedule();      // "天:小时:分钟", 如 "0:2:0" = 每天 02:00
    int getBackupRetentionDays();
    boolean isBackupCompress();

    // 多线路接入 (frp 中转 + 家宽直连)
    boolean isNetworkRelayEnabled();
    String getRelayBindHost();
    int getMinecraftPort();          // 转发目标, 即 server.properties 里的 server-port
    List<NodeDefinition> getNodes();

    // 线路延迟探针 (WebSocket 回显)
    boolean isProbeEnabled();
    String getProbeBindHost();
    int getProbePort();

    // Tab 列表 (服务端权威渲染, 客户端无需装 mod)
    boolean isTabListEnabled();
    int getTabListLatencyGreen();
    int getTabListLatencyYellow();
    int getTabListBroadcastIntervalTicks();

    // 主动延迟探针 (Ping/Pong 高频测量, 取代原版 keep-alive 的 15 秒采样 + 重度平滑)
    boolean isLatencyProbeEnabled();
    int getLatencyProbeIntervalTicks();
    int getLatencyProbeJitterTicks();
    int getLatencyWindowSamples();
    int getLatencyPublishIntervalTicks();
    int getLatencyPublishHysteresisMs();

    // 日志
    boolean isLogRequests();
    boolean isDebug();
}
