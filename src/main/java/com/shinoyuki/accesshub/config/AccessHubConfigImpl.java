package com.shinoyuki.accesshub.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.shinoyuki.accesshub.net.NodeDefinition;

/**
 * AccessHubConfig 的 Night Config TOML 实现.
 *
 * 配置文件路径: config/Shinoyuki-Optimize/shinoyuki_accesshub/common.toml
 *
 * 首次启动行为:
 *  - 自动创建配置目录
 *  - 写入默认值与字段注释
 *  - 用 SecureRandom 生成 admin password / API token / JWT secret 三件套
 *  - 在日志中打印 admin password 一次, 之后只能从配置文件读取
 *
 * 线程安全: Night Config 对 CommentedFileConfig 内部加锁, 多线程 get/set 安全.
 * autosave 让每次 set 立即写盘.
 */
public final class AccessHubConfigImpl implements AccessHubConfig {

    private static final Logger logger = LoggerFactory.getLogger(AccessHubConfigImpl.class);

    private static final String CHARSET_ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CommentedFileConfig config;

    public AccessHubConfigImpl(Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());

        boolean firstRun = !Files.exists(configFile);

        this.config = CommentedFileConfig.builder(configFile, TomlFormat.instance())
                .sync()
                .autosave()
                .build();
        this.config.load();

        if (firstRun) {
            populateDefaults();
            logger.info("已生成默认配置: {}", configFile);
        }

        ensurePackDefaults();
        ensureTabListDefaults();
        ensureSecrets();
    }

    /**
     * 首次启动写入默认值 + 字段注释.
     */
    private void populateDefaults() {
        config.set("http.enabled", true);
        config.setComment("http.enabled", " 是否启用 HTTP 服务器");
        config.set("http.port", 22222);
        config.setComment("http.port", " 监听端口");
        config.set("http.host", "0.0.0.0");
        config.setComment("http.host", " 监听地址 (0.0.0.0 表示所有接口)");
        config.set("http.max-threads", 10);
        config.setComment("http.max-threads", " Jetty 线程池上限");
        config.set("http.timeout", 30000);
        config.setComment("http.timeout", " 连接闲置超时 (毫秒)");

        config.set("api.version", "v1");
        config.set("api.auth.enabled", true);
        config.setComment("api.auth.enabled", " 是否启用 API 鉴权 (生产环境强烈建议开启)");
        config.set("api.auth.admin-password", "");
        config.setComment("api.auth.admin-password", " 管理员密码 (首次启动自动生成 12 位)");
        config.set("api.auth.api-token", "");
        config.setComment("api.auth.api-token", " API 访问令牌 (首次启动自动生成 sk- 开头的 64 位)");
        config.set("api.auth.token-prefix", "sk-");
        config.set("api.auth.jwt-secret", "");
        config.setComment("api.auth.jwt-secret",
                " JWT 签名密钥 (首次启动生成 base64 256 位强随机). 修改此值会让所有已签发的 token 立即失效");

        config.set("api.auth.login-attempt-limit.enabled", true);
        config.set("api.auth.login-attempt-limit.max-attempts", 5);
        config.set("api.auth.login-attempt-limit.lock-duration-minutes", 15);

        config.set("api.cors.enabled", true);
        config.set("api.cors.allowed-origins", new ArrayList<>(List.of("*")));

        config.set("whitelist.enabled", true);
        config.set("whitelist.strict-mode", true);
        config.setComment("whitelist.strict-mode",
                " 严格模式: 数据库查询失败时拒绝玩家连接. 关闭则放行 (适合调试)");
        config.set("whitelist.kick-message",
                "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
        config.set("whitelist.contact-info", "请联系管理员");
        config.set("whitelist.token-expiry-hours", 24);
        config.set("whitelist.auto-cleanup-tokens", true);
        config.set("whitelist.join-notification.enabled", true);
        config.setComment("whitelist.join-notification.enabled",
                " 白名单玩家加入时向在线 OP (权限等级>=2) 广播通知");
        config.set("whitelist.welcome-message.enabled", true);
        config.set("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");

        config.set("auth.enabled", true);
        config.setComment("auth.enabled",
                " 玩家离线认证总开关: 开启后进服玩家必须 /register 注册并 /login 登录, 未认证前全限制");
        config.set("auth.timeout-seconds", 60);
        config.setComment("auth.timeout-seconds", " 进服后未在此秒数内完成认证则踢出");
        config.set("auth.max-attempts", 5);
        config.setComment("auth.max-attempts",
                " 同一会话登录密码连续错误上限, 达到后踢下线 (重连即重置, 不持久锁号; 按 IP 锁因动态公网作废)");
        config.set("auth.lock-minutes", 10);
        config.setComment("auth.lock-minutes", " 已弃用: 失败改为会话内踢出, 此项不再生效, 仅保留以兼容旧配置");
        config.set("auth.min-password-length", 8);
        config.setComment("auth.min-password-length", " 注册 / 改密的密码最低位数");
        config.set("auth.reject-weak-password", true);
        config.setComment("auth.reject-weak-password",
                " 拒绝弱密码 (纯数字 / 与用户名相同 / 常见弱口令)");
        config.set("auth.code-expiry-minutes", 1440);
        config.setComment("auth.code-expiry-minutes",
                " 加白时生成的注册码有效期 (分钟), 默认 1440=24 小时; 一次性, 仅限绑定的用户名");
        config.set("auth.device-auth.enabled", true);
        config.setComment("auth.device-auth.enabled",
                " 免密登录二期: 装了本 mod 的客户端进服时服务端用设备公钥验签自动解冻; 没装/验签失败静默回退密码登录");
        config.set("auth.device-auth.challenge-timeout-seconds", 15);
        config.setComment("auth.device-auth.challenge-timeout-seconds",
                " 免密挑战宽限秒数: 客户端须在此秒数内完成解密私钥+签名+回包. 整合包进服瞬间主线程卡顿时"
                        + " 5 秒偏紧, 故默认 15. 服务端按本值的三分之二重发, 最多 4 次, 总重试窗口约为本值的 3 倍,"
                        + " 必须小于 auth.timeout-seconds, 否则玩家会在免密还没试完时就被踢");

        config.set("backup.enabled", true);
        config.set("backup.schedule", "0:2:0");
        config.setComment("backup.schedule", " 备份计划 \"天:小时:分钟\", 如 0:2:0=每天02:00, 1:0:0=每隔1天的00:00");
        config.set("backup.retention-days", 7);
        config.set("backup.compress", true);
        config.setComment("backup.compress", " 是否压缩备份为 ZIP");

        // 默认关闭: 本功能会额外占用若干端口并改变玩家进服的网络路径, 必须等 frp 与 DNS 都配好
        // 之后再手动开启。装上新版本就自动接管网络会让原本正常的服务器直接失联。
        config.set("network.enabled", false);
        config.setComment("network.enabled",
                " 多线路接入转发器. 开启后各线路入口端口的流量统一转发到 minecraft-port,\n"
                        + " 并按入口端口统计每条线路的在线人数. 需先配好 frp 与 DNS 再开启");
        config.set("network.bind-host", "0.0.0.0");
        config.setComment("network.bind-host",
                " 线路入口监听地址. 家宽直连线路需要从公网可达故须为 0.0.0.0;\n"
                        + " 注意 frp 线路的入口端口不要在路由器上做端口映射, 只有直连线路的端口才映射");
        config.set("network.minecraft-port", 25565);
        config.setComment("network.minecraft-port",
                " 转发目标端口, 须与 server.properties 的 server-port 一致.\n"
                        + " 该端口不应直接暴露到公网, 否则玩家绕过转发器后线路统计会漏人");

        config.set("network.probe.enabled", false);
        config.setComment("network.probe.enabled",
                " 线路延迟探针 (WebSocket 回显), 供玩家自查页面测量各线路往返延迟与抖动");
        config.set("network.probe.bind-host", "127.0.0.1");
        config.setComment("network.probe.bind-host",
                " 探针监听地址. 保持回环即可: 对外一律经由 nginx/Caddy 终结 TLS 后反代进来,\n"
                        + " 直接暴露探针端口等于把一个无鉴权的回显服务挂在公网上");
        config.set("network.probe.port", 25610);

        // 入口端口顺序刻意与线路优先级一致 (ECS 在前, 轻量居中, 家宽直连殿后),
        // 排障时看一眼端口号就知道玩家走的是哪一档线路。
        config.set("network.nodes", new ArrayList<>(List.of(
                defaultNode("hangzhou1", "杭州一线", 25601,
                        "hangzhou1.mcwok.cn:25565", "wss://hangzhou1.mcwok.cn/probe"),
                defaultNode("shanghai", "上海线", 25602,
                        "shanghai.mcwok.cn:25565", "wss://shanghai.mcwok.cn/probe"),
                defaultNode("shenzhen", "深圳线", 25603,
                        "shenzhen.mcwok.cn:25565", "wss://shenzhen.mcwok.cn/probe"),
                defaultNode("hangzhou2", "杭州二线", 25604,
                        "hangzhou2.mcwok.cn:25565", "wss://hangzhou2.mcwok.cn/probe"),
                defaultNode("wuhan", "武汉线", 25605,
                        "wuhan.mcwok.cn:25565", "wss://wuhan.mcwok.cn/probe"),
                defaultNode("guangzhou", "广州线", 25606,
                        "guangzhou.mcwok.cn:25565", "wss://guangzhou.mcwok.cn/probe"),
                // 家宽线不在 mcwok.cn 下: shinoyuki.cn 在自己账号下才能让证书 DNS-01 自动续期,
                // 探针带 :8443 是因为厦门联通对家宽入站封了 80/443/8080/53
                defaultNode("xiamen", "厦门联通专线", 25607,
                        "home.shinoyuki.cn:25565", "wss://home.shinoyuki.cn:8443/probe")
        )));
        config.setComment("network.nodes",
                " 线路定义. listen-port 是本机入口端口, 须与 frpc 配置里该线路 proxy 的 localPort 对应;\n"
                        + " endpoint 是给玩家填进游戏客户端的地址; probe-url 留空表示这条线不做延迟探测.\n"
                        + " 删掉整个 [[network.nodes]] 块即可下线一条线路, 无需改动其它线路");

        config.set("logging.log-requests", false);
        config.set("logging.debug", false);

        config.save();
    }

    /** 构造一条默认线路。用 in-memory Config 而非字符串拼接, 让 Night Config 写出标准的 TOML 数组表。 */
    private static Config defaultNode(String id, String displayName, int listenPort,
                                      String endpoint, String probeUrl) {
        Config entry = Config.inMemory();
        entry.set("id", id);
        entry.set("display-name", displayName);
        entry.set("listen-port", listenPort);
        entry.set("endpoint", endpoint);
        entry.set("probe-url", probeUrl);
        return entry;
    }

    private void ensurePackDefaults() {
        boolean changed = false;
        changed |= ensureSetting("pack.version-gate.enabled", false,
                " 整合包版本门控总开关，发布异常时可立即关闭放行玩家");
        changed |= ensureSetting("pack.version-gate.reject-message",
                "&c整合包版本不匹配\n&7当前版本: {current}\n&7所需版本: {required}\n&7请使用 Aurora 更新后重试",
                " 版本不匹配时的拒绝文案，可使用 {current} 与 {required} 占位符");
        changed |= ensureSetting("pack.oss.enabled", false,
                " 自研整合包文件上传总开关；填写以下全部配置后再手动开启");
        changed |= ensureSetting("pack.oss.endpoint", "",
                " 阿里云 OSS 地域 Endpoint，例如 https://oss-cn-hangzhou.aliyuncs.com，不含 bucket 与对象路径");
        changed |= ensureSetting("pack.oss.bucket", "", " OSS Bucket 名称");
        changed |= ensureSetting("pack.oss.access-key-id", "",
                " 仅授予目标 bucket 写权限的 RAM AccessKey ID");
        changed |= ensureSetting("pack.oss.access-key-secret", "",
                " RAM AccessKey Secret；保持为空直到手动配置，本程序不会自动生成或输出到日志");
        changed |= ensureSetting("pack.oss.public-base-url", "",
                " 客户端公开下载基地址，可含 CDN 路径前缀，例如 https://cdn.example.com/wok");
        if (changed) {
            config.save();
        }
    }

    private boolean ensureSetting(String path, Object defaultValue, String comment) {
        boolean changed = false;
        if (!config.contains(path)) {
            config.set(path, defaultValue);
            changed = true;
        }
        if (config.getComment(path) == null) {
            config.setComment(path, comment);
            changed = true;
        }
        return changed;
    }

    /**
     * 确保 admin password / api token / jwt secret 三件套都已生成.
     */
    private void ensureTabListDefaults() {
        boolean changed = false;
        changed |= ensureSetting("tablist.enabled", true,
                " Tab 列表增强总开关；关闭后回到原版 Tab, 不再发送 header/footer 与延迟后缀");
        changed |= ensureSetting("tablist.latency-green-threshold", 60,
                " 延迟低于此值显示绿色 (毫秒)");
        changed |= ensureSetting("tablist.latency-yellow-threshold", 120,
                " 延迟低于此值显示黄色, 否则红色 (毫秒)");
        changed |= ensureSetting("tablist.broadcast-interval-ticks", 10,
                " 两次 Tab 刷新之间的服务端 tick 数, 10 = 每秒 2 次；调大可降低广播开销");
        if (changed) {
            config.save();
        }
    }

    private void ensureSecrets() {
        if (getAdminPassword().isEmpty()) {
            String pwd = generateRandomString(12, CHARSET_ALPHANUMERIC);
            setAdminPassword(pwd);
            logger.warn("自动生成管理员密码 (首次启动一次性输出, 请妥善保管): {}", pwd);
        }

        if (getApiToken().isEmpty()) {
            String prefix = config.getOrElse("api.auth.token-prefix", "sk-");
            String token = prefix + generateRandomString(64 - prefix.length(), CHARSET_ALPHANUMERIC);
            setApiToken(token);
            logger.warn("自动生成 API 访问令牌: {}", token);
        }

        if (getJwtSecret().isEmpty()) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            setJwtSecret(Base64.getEncoder().encodeToString(bytes));
            logger.info("自动生成 JWT 签名密钥 (256 bit, 已写入配置文件)");
        }

        if (getServerInstanceId().isEmpty()) {
            // 免密签名域分隔用: 持久化, 避免每次重启使飞行中挑战失效 + 客户端按实例分文件失配
            config.set("auth.device-auth.server-instance-id", generateRandomString(24, CHARSET_ALPHANUMERIC));
            logger.info("自动生成 DeviceAuth 服务器实例标识 (持久化于配置)");
        }
    }

    private static String generateRandomString(int length, String charset) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
        }
        return sb.toString();
    }

    @Override
    public void reload() {
        config.load();
    }

    @Override public boolean isHttpEnabled()   { return config.getOrElse("http.enabled", true); }
    @Override public int     getHttpPort()     { return config.getIntOrElse("http.port", 22222); }
    @Override public String  getHttpHost()     { return config.getOrElse("http.host", "0.0.0.0"); }
    @Override public int     getMaxThreads()   { return config.getIntOrElse("http.max-threads", 10); }
    @Override public int     getTimeout()      { return config.getIntOrElse("http.timeout", 30000); }

    @Override public String  getApiVersion()   { return config.getOrElse("api.version", "v1"); }
    @Override public boolean isAuthEnabled()   { return config.getOrElse("api.auth.enabled", true); }
    @Override public String  getApiToken()     { return config.getOrElse("api.auth.api-token", ""); }
    @Override public void    setApiToken(String token) { config.set("api.auth.api-token", token); }
    @Override public String  getTokenPrefix()  { return config.getOrElse("api.auth.token-prefix", "sk-"); }

    @Override public String  getJwtSecret()    { return config.getOrElse("api.auth.jwt-secret", ""); }
    @Override public void    setJwtSecret(String secret) { config.set("api.auth.jwt-secret", secret); }

    @Override public String  getAdminPassword() { return config.getOrElse("api.auth.admin-password", ""); }
    @Override public void    setAdminPassword(String password) { config.set("api.auth.admin-password", password); }

    @Override public boolean isLoginAttemptLimitEnabled() { return config.getOrElse("api.auth.login-attempt-limit.enabled", true); }
    @Override public int     getLoginMaxAttempts()        { return config.getIntOrElse("api.auth.login-attempt-limit.max-attempts", 5); }
    @Override public int     getLoginLockDurationMinutes(){ return config.getIntOrElse("api.auth.login-attempt-limit.lock-duration-minutes", 15); }

    @Override public boolean isCorsEnabled() { return config.getOrElse("api.cors.enabled", true); }

    @Override
    public List<String> getAllowedOrigins() {
        // Night Config 的 List 内部是 List<Object>, 需要逐元素 toString 化以满足接口契约
        List<Object> raw = config.getOrElse("api.cors.allowed-origins", new ArrayList<>(List.of("*")));
        List<String> result = new ArrayList<>(raw.size());
        for (Object o : raw) {
            result.add(String.valueOf(o));
        }
        return result;
    }

    @Override public boolean isWhitelistEnabled()    { return config.getOrElse("whitelist.enabled", true); }
    @Override public boolean isWhitelistStrictMode() { return config.getOrElse("whitelist.strict-mode", true); }

    @Override
    public String getWhitelistKickMessage() {
        return config.getOrElse("whitelist.kick-message",
                "&c您不在服务器白名单中！\n&7请联系管理员申请加入白名单");
    }

    @Override
    public String getWhitelistDisabledMessage() {
        return config.getOrElse("whitelist.disabled-message",
                "&c您已在白名单中，但管理员手动关闭了您的访问权限\n&7如有疑问请联系管理员");
    }

    @Override public String getContactInfo()           { return config.getOrElse("whitelist.contact-info", "请联系管理员"); }
    @Override public int    getTokenExpiryHours()      { return config.getIntOrElse("whitelist.token-expiry-hours", 24); }
    @Override public boolean isAutoCleanupTokens()     { return config.getOrElse("whitelist.auto-cleanup-tokens", true); }
    @Override public boolean isJoinNotificationEnabled() { return config.getOrElse("whitelist.join-notification.enabled", true); }

    @Override public boolean isWelcomeMessageEnabled() { return config.getOrElse("whitelist.welcome-message.enabled", true); }

    @Override
    public String getWelcomeMessage() {
        return config.getOrElse("whitelist.welcome-message.text", "&a欢迎回到服务器！\n&7玩家: &e{player}");
    }

    @Override public boolean isPlayerAuthEnabled()       { return config.getOrElse("auth.enabled", true); }
    @Override public int     getPlayerAuthTimeoutSeconds(){ return config.getIntOrElse("auth.timeout-seconds", 60); }
    @Override public int     getPlayerAuthMaxAttempts()  { return config.getIntOrElse("auth.max-attempts", 5); }
    @Override public int     getPlayerAuthLockMinutes()  { return config.getIntOrElse("auth.lock-minutes", 10); }
    @Override public int     getPlayerAuthMinPasswordLength()   { return config.getIntOrElse("auth.min-password-length", 8); }
    @Override public boolean isPlayerAuthRejectWeakPassword()   { return config.getOrElse("auth.reject-weak-password", true); }
    @Override public int     getPlayerAuthCodeExpiryMinutes()   { return config.getIntOrElse("auth.code-expiry-minutes", 1440); }
    @Override public boolean isDeviceAuthEnabled()                  { return config.getOrElse("auth.device-auth.enabled", true); }
    @Override public int     getDeviceAuthChallengeTimeoutSeconds(){ return config.getIntOrElse("auth.device-auth.challenge-timeout-seconds", 15); }
    @Override public String  getServerInstanceId()                 { return config.getOrElse("auth.device-auth.server-instance-id", ""); }

    @Override public boolean isPackVersionGateEnabled() { return config.getOrElse("pack.version-gate.enabled", false); }

    @Override
    public String getPackVersionRejectMessage() {
        return config.getOrElse("pack.version-gate.reject-message",
                "&c整合包版本不匹配\n&7当前版本: {current}\n&7所需版本: {required}\n&7请使用 Aurora 更新后重试");
    }

    @Override public boolean isPackOssEnabled()          { return config.getOrElse("pack.oss.enabled", false); }
    @Override public String  getPackOssEndpoint()        { return config.getOrElse("pack.oss.endpoint", ""); }
    @Override public String  getPackOssBucket()          { return config.getOrElse("pack.oss.bucket", ""); }
    @Override public String  getPackOssAccessKeyId()     { return config.getOrElse("pack.oss.access-key-id", ""); }
    @Override public String  getPackOssAccessKeySecret() { return config.getOrElse("pack.oss.access-key-secret", ""); }
    @Override public String  getPackOssPublicBaseUrl()   { return config.getOrElse("pack.oss.public-base-url", ""); }

    @Override public boolean isBackupEnabled()       { return config.getOrElse("backup.enabled", true); }
    @Override public String  getBackupSchedule()     { return config.getOrElse("backup.schedule", "0:2:0"); }
    @Override public int     getBackupRetentionDays(){ return config.getIntOrElse("backup.retention-days", 7); }
    @Override public boolean isBackupCompress()      { return config.getOrElse("backup.compress", true); }

    @Override public boolean isNetworkRelayEnabled() { return config.getOrElse("network.enabled", false); }
    @Override public String  getRelayBindHost()      { return config.getOrElse("network.bind-host", "0.0.0.0"); }
    @Override public int     getMinecraftPort()      { return config.getIntOrElse("network.minecraft-port", 25565); }

    /**
     * 读取线路定义。字段缺失或端口非法时由 {@link NodeDefinition} 的构造校验抛出, 不做静默兜底 —
     * 一条线路配错意味着那条线的玩家全部连不上, 必须在启动日志里立刻暴露, 而不是等玩家来报障。
     */
    @Override
    public List<NodeDefinition> getNodes() {
        List<Config> raw = config.get("network.nodes");
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<NodeDefinition> result = new ArrayList<>(raw.size());
        for (Config entry : raw) {
            result.add(new NodeDefinition(
                    entry.getOrElse("id", ""),
                    entry.getOrElse("display-name", ""),
                    entry.getIntOrElse("listen-port", 0),
                    entry.getOrElse("endpoint", ""),
                    entry.getOrElse("probe-url", "")));
        }
        return result;
    }

    @Override public boolean isProbeEnabled()   { return config.getOrElse("network.probe.enabled", false); }
    @Override public String  getProbeBindHost() { return config.getOrElse("network.probe.bind-host", "127.0.0.1"); }
    @Override public int     getProbePort()     { return config.getIntOrElse("network.probe.port", 25610); }

    @Override public boolean isTabListEnabled()                { return config.getOrElse("tablist.enabled", true); }
    @Override public int     getTabListLatencyGreen()          { return config.getIntOrElse("tablist.latency-green-threshold", 60); }
    @Override public int     getTabListLatencyYellow()         { return config.getIntOrElse("tablist.latency-yellow-threshold", 120); }
    @Override public int     getTabListBroadcastIntervalTicks(){ return config.getIntOrElse("tablist.broadcast-interval-ticks", 10); }

    @Override public boolean isLogRequests() { return config.getOrElse("logging.log-requests", false); }
    @Override public boolean isDebug()       { return config.getOrElse("logging.debug", false); }
}
