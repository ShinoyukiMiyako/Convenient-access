package com.shinoyuki.accesshub;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

import com.mojang.logging.LogUtils;
import com.shinoyuki.accesshub.api.AdminAuthController;
import com.shinoyuki.accesshub.api.ApiRouter;
import com.shinoyuki.accesshub.api.BindingApiController;
import com.shinoyuki.accesshub.api.ChatBridgeHandler;
import com.shinoyuki.accesshub.api.ChatBridgeHandlerImpl;
import com.shinoyuki.accesshub.api.ItemIconHandler;
import com.shinoyuki.accesshub.api.OperationLogApiController;
import com.shinoyuki.accesshub.api.PackAdminApiController;
import com.shinoyuki.accesshub.api.PackPublicApiHandler;
import com.shinoyuki.accesshub.api.PackPublicApiHandlerImpl;
import com.shinoyuki.accesshub.api.PackPublicService;
import com.shinoyuki.accesshub.api.PackUploadController;
import com.shinoyuki.accesshub.api.PlayerDataHandler;
import com.shinoyuki.accesshub.api.PlayerDataHandlerImpl;
import com.shinoyuki.accesshub.api.ServerInfoHandler;
import com.shinoyuki.accesshub.api.ServerInfoHandlerImpl;
import com.shinoyuki.accesshub.api.UserApiController;
import com.shinoyuki.accesshub.integration.SparkIntegration;
import com.shinoyuki.accesshub.api.WhitelistApiController;
import com.shinoyuki.accesshub.auth.AdminAuthService;
import com.shinoyuki.accesshub.auth.LoginAttemptService;
import com.shinoyuki.accesshub.auth.PlayerAuthDao;
import com.shinoyuki.accesshub.auth.PlayerAuthService;
import com.shinoyuki.accesshub.auth.PersonalCodeManager;
import com.shinoyuki.accesshub.auth.PlayerRegistrationCodeDao;
import com.shinoyuki.accesshub.auth.QqBindingDao;
import com.shinoyuki.accesshub.auth.RegistrationTokenManager;
import com.shinoyuki.accesshub.backup.BackupManager;
import com.shinoyuki.accesshub.command.AccessHubCommand;
import com.shinoyuki.accesshub.command.AuthCommand;
import com.shinoyuki.accesshub.command.EnrollCommand;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.config.AccessHubConfigImpl;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.deviceauth.DeviceAuthServer;
import com.shinoyuki.accesshub.deviceauth.DeviceKeyDao;
import com.shinoyuki.accesshub.deviceauth.net.AuthChannel;
import com.shinoyuki.accesshub.event.PlayerAuthListener;
import com.shinoyuki.accesshub.event.PlayerLoginListener;
import com.shinoyuki.accesshub.api.NetworkInfoHandler;
import com.shinoyuki.accesshub.api.NetworkInfoHandlerImpl;
import com.shinoyuki.accesshub.event.NodeSessionListener;
import com.shinoyuki.accesshub.http.HttpServer;
import com.shinoyuki.accesshub.latency.LatencyProbeService;
import com.shinoyuki.accesshub.net.NodeDefinition;
import com.shinoyuki.accesshub.net.NodeRelayServer;
import com.shinoyuki.accesshub.net.NodeSessionRegistry;
import com.shinoyuki.accesshub.net.ProbeServer;
import com.shinoyuki.accesshub.operation.OperationLogDao;
import com.shinoyuki.accesshub.pack.PackAdminService;
import com.shinoyuki.accesshub.pack.PackDao;
import com.shinoyuki.accesshub.pack.PackVersionGate;
import com.shinoyuki.accesshub.tablist.TabListService;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(AccessHubMod.MOD_ID)
public final class AccessHubMod {
    public static final String MOD_ID = "shinoyuki_accesshub";

    /** Shinoyuki 生态约定: 所有 mod 共享 config/Shinoyuki-Optimize/&lt;mod_id&gt;/ 状态目录, 配置 + 数据库集中存放便于运维统一备份. */
    private static final String SHINOYUKI_DIR = "Shinoyuki-Optimize";

    private static final Logger LOGGER = LogUtils.getLogger();

    private AccessHubConfig config;
    private DatabaseManager databaseManager;
    private WhitelistManager whitelistManager;
    private AdminAuthService adminAuthService;
    private PlayerAuthService playerAuthService;
    private DeviceAuthServer deviceAuthServer;
    private HttpServer httpServer;
    private PackUploadController packUploadController;
    private BackupManager backupManager;
    private SparkIntegration sparkIntegration;
    private NodeSessionRegistry nodeSessionRegistry;
    private NodeRelayServer nodeRelayServer;
    private ProbeServer probeServer;

    /** 本次运行是否真的启用了服务端业务 (仅专用服务器). 内置服务器下恒为 false, 关服钩子据此完全静默。 */
    private boolean serverSideActive;

    public AccessHubMod() {
        MinecraftForge.EVENT_BUS.register(this);
        // MOD bus: 注册免密自定义网络通道 (须在 FMLCommonSetupEvent, 早于 ServerStarting)
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        LOGGER.info("AccessHub v0.2.0 loading on Forge 1.20.1");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        // 通道注册写全局状态, 包进 enqueueWork 保证串行化。
        // 双端都要注册: 客户端 jar 也得有这条通道才能连专用服务器免密, 故这里不能按 dist 分支跳过。
        // 用 debug 级: 单人存档下本 mod 不该在控制台留下运行痕迹, 需要排查时看 logs/debug.log。
        event.enqueueWork(AuthChannel::register);
        LOGGER.debug("DeviceAuth 网络通道已注册");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        net.minecraft.server.MinecraftServer server = event.getServer();
        // 专用服务器运维工具: 白名单 / HTTP API / 离线认证 全是面向 dedicated server 的服务端功能。
        // 内置服务器 (单人存档 / 客户端 "对局域网开放") 同样触发 ServerStartingEvent, 若不区分会把房主当作
        // 未在白名单的访问者进服即踢 (whitelist.enabled 默认开), 或在 auth.enabled 默认开下把房主失明/缓慢/钉死
        // 在出生点却无注册码可用 -> 单人进不去。故内置服务器整体跳过服务端初始化, 使装了本 mod 的客户端单人存档
        // 完全透明可玩 (客户端侧只保留 DeviceAuth 免密通道, 仅在连入专用服务器时才生效)。
        if (!server.isDedicatedServer()) {
            // debug 级: 单人存档下本 mod 对玩家应当完全无感, 控制台不留自己的运行日志
            LOGGER.debug("AccessHub 运行于内置服务器 (单人/局域网), 跳过服务端初始化: 白名单/HTTP/认证均不启用");
            return;
        }
        // 先置位再 initialize: 初始化中途失败也要让关服钩子回收已经建好的那部分资源
        serverSideActive = true;
        try {
            // 传入 MinecraftServer 供 PlayerDataHandler 在主线程采集玩家数据
            initialize(server);
            LOGGER.info("AccessHub 服务端启动完成");
        } catch (Exception e) {
            // 不向 Forge 抛出: mod 启动失败应该只让本 mod 业务不可用, 而不是把整个服务器拖死
            LOGGER.error("AccessHub 启动失败, 白名单/HTTP API 功能将不可用", e);
        }
    }

    /**
     * 业务初始化, 顺序: 配置 -> 数据库 -> 业务管理器 -> 认证 -> Controllers -> ApiRouter -> HttpServer.
     */
    private void initialize(net.minecraft.server.MinecraftServer server) throws Exception {
        // 1. 状态目录 config/Shinoyuki-Optimize/shinoyuki_accesshub/ (含 common.toml 与 whitelist.db)
        Path baseDir = FMLPaths.CONFIGDIR.get().resolve(SHINOYUKI_DIR).resolve(MOD_ID);
        LOGGER.info("AccessHub 状态目录: {}", baseDir);

        // 2. 配置 (Night Config TOML, 首次启动自动生成默认值与三件套密钥)
        config = new AccessHubConfigImpl(baseDir.resolve("common.toml"));

        // 3. 数据库 (跟配置文件同目录, 简化运维备份: 备份 config/Shinoyuki-Optimize/ 一次性带走所有状态)
        databaseManager = new DatabaseManager(baseDir.toFile());
        if (!databaseManager.initialize().get()) {
            throw new IllegalStateException("数据库初始化失败");
        }

        // 4. 业务管理器
        whitelistManager = new WhitelistManager(databaseManager);
        if (!whitelistManager.initialize().get()) {
            throw new IllegalStateException("白名单管理器初始化失败");
        }
        RegistrationTokenManager tokenManager = new RegistrationTokenManager(databaseManager);
        OperationLogDao operationLogDao = new OperationLogDao(databaseManager);
        // QQ Bot 绑定链路: 管理员在面板签发个人识别码, 私聊发给 Bot 完成 QQ 认领
        PersonalCodeManager personalCodeManager = new PersonalCodeManager(databaseManager);
        QqBindingDao qqBindingDao = new QqBindingDao(databaseManager);
        PackDao packDao = new PackDao(databaseManager);
        PackAdminService packAdminService = new PackAdminService(packDao);
        PackVersionGate packVersionGate = new PackVersionGate(config, packDao);

        // 5. 认证服务
        LoginAttemptService loginAttempt = new LoginAttemptService(
                config.getLoginMaxAttempts(),
                config.getLoginLockDurationMinutes(),
                config.isLoginAttemptLimitEnabled()
        );
        adminAuthService = new AdminAuthService(
                databaseManager,
                tokenManager,
                config.getAdminPassword(),
                config.getJwtSecret(),
                loginAttempt
        );

        // 玩家离线认证 (游戏内强制登录, 与管理员 HTTP 认证相互独立). 数据库已就绪即可构建.
        PlayerAuthDao playerAuthDao = new PlayerAuthDao(databaseManager);
        PlayerRegistrationCodeDao playerRegistrationCodeDao = new PlayerRegistrationCodeDao(databaseManager);
        playerAuthService = new PlayerAuthService(playerAuthDao, playerRegistrationCodeDao, config);

        // 免密验签 (DeviceAuth): 服务端只存公钥. 通道处理器经 AuthChannel.setServer 惰性引用本实例。
        DeviceKeyDao deviceKeyDao = new DeviceKeyDao(databaseManager);
        deviceAuthServer = new DeviceAuthServer(deviceKeyDao, playerAuthService, config);
        AuthChannel.setServer(deviceAuthServer);

        // 6. API Controllers (加白时由 playerAuthService 签发绑定注册码并回传)
        WhitelistApiController whitelistController = new WhitelistApiController(
                whitelistManager, operationLogDao, playerAuthService, config);
        UserApiController userController = new UserApiController(tokenManager, whitelistManager);
        OperationLogApiController operationLogController = new OperationLogApiController(operationLogDao);
        AdminAuthController adminAuthController = new AdminAuthController(adminAuthService);
        PlayerDataHandler playerDataHandler = new PlayerDataHandlerImpl(server);
        // Spark 性能监测 (软依赖, 未装 spark mod 时降级为 JVM 数据) + 在线玩家列表
        sparkIntegration = new SparkIntegration(server);
        ServerInfoHandler serverInfoHandler = new ServerInfoHandlerImpl(server, sparkIntegration);
        // 物品图标抽取 (无状态: 仅依赖 ModList + 资源 IO, 内置 PNG 缓存)
        ItemIconHandler itemIconHandler = new ItemIconHandler();
        // 外部渠道 (QQ #say) 投递到游戏内公屏
        ChatBridgeHandler chatBridgeHandler = new ChatBridgeHandlerImpl(server);
        BindingApiController bindingController = new BindingApiController(personalCodeManager, qqBindingDao);
        PackPublicApiHandler packPublicApiHandler =
                new PackPublicApiHandlerImpl(new PackPublicService(packDao));
        PackAdminApiController packAdminController = new PackAdminApiController(packAdminService);
        Path uploadTempDirectory = baseDir.resolve("upload-tmp");
        packUploadController = new PackUploadController(packAdminService, config, uploadTempDirectory);

        // 线路会话表. 无条件创建: 转发器未启用时它恒为空表, 查询直接返回 null,
        // 依赖方 (登录监听器/统计端点) 无需各自做 null 分支。
        nodeSessionRegistry = new NodeSessionRegistry();
        NetworkInfoHandler networkInfoHandler =
                new NetworkInfoHandlerImpl(server, config, nodeSessionRegistry);

        ApiRouter apiRouter = new ApiRouter(
                whitelistController, userController,
                playerDataHandler, serverInfoHandler,
                itemIconHandler, networkInfoHandler,
                chatBridgeHandler,
                operationLogController, bindingController,
                packPublicApiHandler, packAdminController, packUploadController,
                adminAuthController,
                config
        );

        // 7. HTTP 服务器
        if (config.isHttpEnabled()) {
            httpServer = new HttpServer(config, apiRouter, uploadTempDirectory);
            httpServer.start();
        } else {
            LOGGER.info("HTTP 服务器在配置中已禁用, 跳过启动");
        }

        // 8. 玩家登录监听器 (PreLogin 阶段拦截未在白名单的玩家)
        // 注册到 EVENT_BUS, 实例持有 config / whitelistManager / databaseManager 依赖.
        // 不放进 mod 启动早期是因为它依赖 whitelistManager 已初始化完成 (步骤 4).
        PlayerLoginListener loginListener = new PlayerLoginListener(
                config, whitelistManager, databaseManager, nodeSessionRegistry, packVersionGate);
        MinecraftForge.EVENT_BUS.register(loginListener);
        LOGGER.info("白名单登录监听器已注册到事件总线");

        // 8a. 线路归属认领器. 独立于白名单开关, 故单独注册.
        MinecraftForge.EVENT_BUS.register(new NodeSessionListener(nodeSessionRegistry));

        // 8b. 玩家离线认证拦截器 (未认证全限制 + 冻结 + 超时踢出).
        // 注册到 EVENT_BUS 即生效; 内部各 @SubscribeEvent 均先判 auth.enabled 再处理, 禁用时零开销放行.
        PlayerAuthListener authListener = new PlayerAuthListener(config, playerAuthService, deviceAuthServer);
        MinecraftForge.EVENT_BUS.register(authListener);
        LOGGER.info("玩家离线认证拦截器已注册到事件总线 (auth.enabled={})", config.isPlayerAuthEnabled());

        // 9. 数据库自动备份 (定时备份 whitelist.db, 与数据库同目录)
        backupManager = new BackupManager(baseDir.toFile(), config);
        backupManager.initialize();

        // 10. 多线路接入 (frp 中转 + 家宽直连) 与延迟探针
        startNetworkServices(server);

        // 11. Tab 列表增强. 与上面几个监听器同样常驻注册, 内部先判 tablist.enabled, 关闭时零开销放行,
        // 这样 /accesshub reload 改开关后无需重启即可生效.
        MinecraftForge.EVENT_BUS.register(new TabListService(config, server));
        LOGGER.info("Tab 列表增强已注册到事件总线 (tablist.enabled={})", config.isTabListEnabled());

        // 12. 主动延迟探针. 与上面同样常驻注册, 内部先判 latency.probe-enabled。
        // 探针把估计写回 ServerPlayer.latency, 因此 Tab 显示、原版信号格图标与 HTTP API
        // 三个消费方共用同一个真值, 都不需要为此改造。
        MinecraftForge.EVENT_BUS.register(new LatencyProbeService(config, server));
        LOGGER.info("主动延迟探针已注册到事件总线 (latency.probe-enabled={})", config.isLatencyProbeEnabled());
    }

    /**
     * 启动线路转发器与延迟探针。
     *
     * 故障不外抛而是就地降级: 端口被占用一类的问题若让整个 initialize 失败, 白名单会跟着失效,
     * 在 strict-mode 下等于全服玩家都进不来 — 拿服务器可用性去换一个网络增强功能不划算。
     * 失败时记 error, 线路统计不可用, 玩家仍可经原有端口正常进服。
     */
    private void startNetworkServices(net.minecraft.server.MinecraftServer server) {
        if (config.isNetworkRelayEnabled()) {
            try {
                int configuredPort = config.getMinecraftPort();
                if (configuredPort != server.getPort()) {
                    LOGGER.warn("network.minecraft-port={} 与服务器实际监听端口 {} 不一致, 转发将连不上目标, 请核对配置",
                            configuredPort, server.getPort());
                }
                List<NodeDefinition> nodes = config.getNodes();
                if (nodes.isEmpty()) {
                    LOGGER.warn("多线路接入已启用但 network.nodes 为空, 转发器不启动");
                } else {
                    nodeRelayServer = new NodeRelayServer(nodes, config.getRelayBindHost(),
                            new InetSocketAddress("127.0.0.1", configuredPort), nodeSessionRegistry);
                    nodeRelayServer.start();
                    LOGGER.info("多线路接入已启动, 共 {} 条线路", nodes.size());
                }
            } catch (Exception e) {
                LOGGER.error("线路转发器启动失败, 线路统计不可用 (玩家仍可经原有端口进服)", e);
                nodeRelayServer = null;
            }
        } else {
            LOGGER.info("多线路接入在配置中未启用");
        }

        if (!config.isProbeEnabled()) {
            return;
        }
        try {
            probeServer = new ProbeServer(config.getProbeBindHost(), config.getProbePort());
            probeServer.start();
        } catch (Exception e) {
            LOGGER.error("延迟探针启动失败, 自查页面将无法测量线路延迟", e);
            probeServer = null;
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (!serverSideActive) {
            return; // 内置服务器: 什么都没启动过, 退出单人世界时也不该刷关服日志
        }
        serverSideActive = false;
        LOGGER.info("AccessHub 正在关闭...");
        // 每步独立 try/catch + catch Throwable: 关闭钩子绝不能崩掉关服流程, 且任一步失败不影响后续。
        // 尤其 httpServer.stop() 在 Forge SecureJar 下可能抛 NoClassDefFoundError (relocate 的 Jetty 关闭期
        // 类惰性加载失败), 那是 Error 不是 Exception, 旧的 catch(Exception) 抓不住会逃逸 -> 关服崩 + 服务器关不掉。
        // 网络入口优先关闭: 先停止接受新连接, 再回收其余资源
        if (nodeRelayServer != null) {
            try { nodeRelayServer.stop(); } catch (Throwable t) { LOGGER.warn("线路转发器关闭异常", t); }
        }
        if (probeServer != null) {
            try { probeServer.stop(); } catch (Throwable t) { LOGGER.warn("延迟探针关闭异常", t); }
        }
        if (backupManager != null) {
            try { backupManager.shutdown(); } catch (Throwable t) { LOGGER.warn("备份管理器关闭异常", t); }
        }
        if (packUploadController != null) {
            try { packUploadController.close(); } catch (Throwable t) { LOGGER.warn("整合包上传服务关闭异常", t); }
        }
        if (httpServer != null) {
            try { httpServer.stop(); } catch (Throwable t) { LOGGER.warn("HTTP 服务器关闭异常 (不影响关服)", t); }
        }
        if (databaseManager != null) {
            try { databaseManager.shutdown(); } catch (Throwable t) { LOGGER.warn("数据库关闭异常", t); }
        }
        LOGGER.info("AccessHub 已关闭");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // 内置服务器 (单人存档 / 对局域网开放) 整体跳过服务端初始化, 这些命令在那里必然报"未就绪",
        // 只会污染单人世界的命令补全并和别的 mod 抢 /login 这类通名. 与 onServerStarting 的门禁口径一致.
        // 注意不能改判 !=DEDICATED: 数据生成等场景用的是 ALL, 这里只排除 INTEGRATED.
        if (event.getCommandSelection() == Commands.CommandSelection.INTEGRATED) {
            return;
        }
        AccessHubCommand.register(event.getDispatcher(), this);
        // 玩家自助认证命令 (不要求 OP). 依赖在执行期经 getPlayerAuthService 惰性解析,
        // 因 RegisterCommandsEvent 在 initialize 之前的 bootstrap 即触发, 与 AccessHubCommand 同模式.
        AuthCommand.register(event.getDispatcher(), this);
        EnrollCommand.register(event.getDispatcher(), this);
        LOGGER.info("AccessHub 命令已注册: /accesshub (alias: /ca /ahub), /register /login /changepassword /enroll (别名 /reg /l)");
    }

    /**
     * 暴露给命令层使用. mod 启动失败时返回 null, 命令实现负责 null 防御并提示用户.
     */
    public AccessHubConfig getConfig() {
        return config;
    }

    /**
     * 暴露给命令层使用. mod 启动失败或 http 被配置禁用时返回 null.
     */
    public HttpServer getHttpServer() {
        return httpServer;
    }

    /**
     * 暴露给命令层使用. mod 启动失败时返回 null.
     */
    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }

    /**
     * 暴露给命令层使用 (auth 管理子命令). mod 启动失败时返回 null.
     */
    public PlayerAuthService getPlayerAuthService() {
        return playerAuthService;
    }

    /** 暴露给命令层 (/enroll) 与网络通道使用. mod 启动失败时返回 null. */
    public DeviceAuthServer getDeviceAuthServer() {
        return deviceAuthServer;
    }
}
