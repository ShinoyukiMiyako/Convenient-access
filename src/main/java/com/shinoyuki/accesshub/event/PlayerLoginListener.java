package com.shinoyuki.accesshub.event;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.net.NodeSession;
import com.shinoyuki.accesshub.net.NodeSessionRegistry;
import com.shinoyuki.accesshub.pack.PackVersionGate;
import com.shinoyuki.accesshub.pack.net.PackVersionChannel;
import com.shinoyuki.accesshub.whitelist.AccessDecision;
import com.shinoyuki.accesshub.whitelist.WhitelistEntry;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 玩家登录监听器。白名单沿用双层拦截，整合包版本门控在 LOGIN 协商阶段执行:
 *
 * 1. {@link PlayerNegotiationEvent}(LOGIN 协商阶段): 在玩家进入世界前于"正在登录"界面拒绝。白名单查询
 *    超时/异常放行交 PLAY 兜底；版本门控开启后则对缺失版本、存储异常与不匹配全部拒绝，并保留拒绝结论
 *    供 PLAY 再兜底。login 阶段断连在重度整合包(含 packetfixer/xlpackets 包管线 mixin)下能否被客户端
 *    渲染出文案并不可靠，因此不能作为唯一防线。
 * 2. {@link PlayerEvent.PlayerLoggedInEvent}(PLAY 阶段, 核心事件必然触发, 唯一可靠防线): 玩家若走到 PLAY，
 *    白名单重新查询；版本门控复用协商期拒绝结论，协商事件未触发时则直接补查。拒绝统一用原版
 *    {@code player.connection.disconnect} 延迟数秒踢出，文案稳定可见 (见 {@link #REJECT_DELAY_SECONDS})。
 *
 * 不会双踢: 协商成功关闭连接 -> PlayerLoggedInEvent 永不触发 (Forge 生命周期保证); 协商未关闭 -> 只有 PLAY 生效。
 * 断连写法的踩坑史与正确姿势见 disconnectDuringLogin 注释 / [[forge-login-disconnect-gotcha]]。
 */
public final class PlayerLoginListener {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLoginListener.class);

    /** 协商阶段白名单查询超时. 超时/异常一律不在协商阶段动作, 交 PLAY 阶段兜底 (含异步线程池排队 + DB 查询)。 */
    private static final int NEGOTIATION_CHECK_TIMEOUT_SECONDS = 10;
    private static final int MAX_DISPLAYED_PACK_VERSION_LENGTH = 128;

    /**
     * 被拒玩家延迟踢出秒数。在 join tick 立即踢, 客户端尚在进服序列中途 (接收区块/各 mod 同步配置),
     * 收到断开包只显示通用"连接中断"而非踢出文案 (实测: 服务端已正确下发文案但客户端不渲染)。延迟数秒待
     * 客户端完全进入 PLAY 再踢, 等价正常 /kick, 文案稳定可见。重度整合包加载慢, 取较宽裕的冗余值。
     */
    private static final long REJECT_DELAY_SECONDS = 3;

    private final AccessHubConfig config;
    private final WhitelistManager whitelistManager;
    private final DatabaseManager databaseManager;
    private final NodeSessionRegistry sessionRegistry;
    private final PackVersionGate packVersionGate;
    private final Map<Connection, Component> pendingPackRejections = new ConcurrentHashMap<>();

    public PlayerLoginListener(AccessHubConfig config,
                               WhitelistManager whitelistManager,
                               DatabaseManager databaseManager,
                               NodeSessionRegistry sessionRegistry,
                               PackVersionGate packVersionGate) {
        this.config = config;
        this.whitelistManager = whitelistManager;
        this.databaseManager = databaseManager;
        this.sessionRegistry = sessionRegistry;
        this.packVersionGate = packVersionGate;
    }

    /**
     * 协商阶段 (LOGIN) 提前拦截。在 enqueueWork 的异步线程依次做版本门控与白名单查询；版本门控
     * fail-closed，白名单仍保持查询异常时交 PLAY 阶段兜底的既有语义。
     */
    @SubscribeEvent
    public void onPlayerNegotiation(PlayerNegotiationEvent event) {
        boolean whitelistEnabled = config.isWhitelistEnabled();
        boolean versionGateEnabled = config.isPackVersionGateEnabled();
        if (!whitelistEnabled && !versionGateEnabled) {
            return;
        }
        GameProfile profile = event.getProfile();
        // 协商阶段(离线模式)UUID 常未解析(id=null), 但玩家名已有; 白名单按名查即可 (checkAccess 支持 name-only)。
        // 版本门控不依赖身份，因此 profile 缺失时仍必须执行，不能让异常握手绕过门控。
        String playerName = profile != null ? profile.getName() : null;
        String playerUuid = profile != null && profile.getId() != null ? profile.getId().toString() : null;
        String ipAddress = formatRemoteAddress(event.getConnection().getRemoteAddress());

        CompletableFuture<Void> check;
        if (versionGateEnabled) {
            check = PackVersionChannel.awaitClientPackVersion(
                            event.getConnection(), config::isPackVersionGateEnabled)
                    .handleAsync((version, error) -> {
                        if (!event.getConnection().isConnected()) {
                            return null;
                        }
                        if (error != null) {
                            boolean continueLogin = rejectPackVersionInfrastructureFailure(
                                    event, playerName, ipAddress, null, error);
                            if (continueLogin && whitelistEnabled && playerName != null) {
                                performNegotiationCheck(
                                        event, playerName, playerUuid, ipAddress);
                            }
                        } else {
                            performNegotiationChecks(event, whitelistEnabled, true,
                                    playerName, playerUuid, ipAddress, version.orElse(null));
                        }
                        return null;
                    });
        } else {
            check = CompletableFuture.runAsync(() -> performNegotiationChecks(
                    event, whitelistEnabled, false, playerName, playerUuid, ipAddress, null));
        }
        event.enqueueWork(check);
    }

    private void performNegotiationChecks(PlayerNegotiationEvent event,
                                          boolean whitelistEnabled,
                                          boolean versionGateEnabled,
                                          String playerName,
                                          String playerUuid,
                                          String ipAddress,
                                          String clientVersion) {
        if (!event.getConnection().isConnected()) {
            return;
        }
        if (versionGateEnabled && !performPackVersionCheck(
                event, playerName, ipAddress, clientVersion)) {
            return;
        }
        if (whitelistEnabled && playerName != null) {
            performNegotiationCheck(event, playerName, playerUuid, ipAddress);
        }
    }

    /**
     * 在 LOGIN 协商 future 内等待 Forge 填好客户端通道表，然后执行数据库权威版本比较。
     * 门控开启时对缺失版本与基础设施异常均拒绝；需要紧急放行时只需关闭配置开关并 reload。
     */
    private boolean performPackVersionCheck(PlayerNegotiationEvent event,
                                            String playerName,
                                            String ipAddress,
                                            String clientVersion) {
        try {
            if (!config.isPackVersionGateEnabled()) {
                return true;
            }
            if (packVersionGate == null) {
                throw new IllegalStateException("Pack version gate is enabled but was not initialized");
            }

            PackVersionGate.Decision decision = packVersionGate.evaluate(clientVersion);
            if (decision.allowed()) {
                return true;
            }
            if (!config.isPackVersionGateEnabled()) {
                return true;
            }

            Component reason = Component.literal(formatPackVersionRejectMessage(
                    decision.clientVersion(), decision.requiredVersion()));
            rememberPackRejection(event.getConnection(), reason);
            disconnectDuringLogin(event.getConnection(), reason);
            logger.warn("整合包版本门控拒绝: 玩家={} IP={} 状态={} 客户端版本={} 要求版本={}",
                    playerName, ipAddress, decision.status(),
                    displayClientVersion(decision.clientVersion()),
                    displayRequiredVersion(decision.requiredVersion()));
            return false;
        } catch (Exception e) {
            return rejectPackVersionInfrastructureFailure(event, playerName, ipAddress, clientVersion, e);
        }
    }

    private boolean rejectPackVersionInfrastructureFailure(PlayerNegotiationEvent event,
                                                           String playerName,
                                                           String ipAddress,
                                                           String clientVersion,
                                                           Throwable error) {
        if (!event.getConnection().isConnected()) {
            return false;
        }
        if (!config.isPackVersionGateEnabled()) {
            return true;
        }
        logger.error("整合包版本门控检查失败，按启用状态拒绝: 玩家={} IP={} 客户端版本={}",
                playerName, ipAddress, displayClientVersion(clientVersion), error);
        if (!config.isPackVersionGateEnabled()) {
            return true;
        }
        Component reason = Component.literal("§c整合包版本验证失败，请稍后重试或联系管理员");
        rememberPackRejection(event.getConnection(), reason);
        disconnectDuringLogin(event.getConnection(), reason);
        return false;
    }

    /** LOGIN 断连在部分包管线下可能失效，连接关闭前保留拒绝结论供 PlayerLoggedInEvent 兜底。 */
    private void rememberPackRejection(Connection connection, Component reason) {
        pendingPackRejections.put(connection, reason);
        connection.channel().closeFuture().addListener(
                future -> pendingPackRejections.remove(connection, reason));
    }

    Component takePendingPackRejection(Connection connection) {
        return pendingPackRejections.remove(connection);
    }

    /** 协商阶段查询并(仅在确定拒绝时)断连。本方法跑在 ForkJoinPool 异步线程 (非 netty 事件循环), 这点对 disconnectDuringLogin 的安全性至关重要。 */
    private void performNegotiationCheck(PlayerNegotiationEvent event,
                                         String playerName, String playerUuid, String ipAddress) {
        logger.info("[协商诊断] performNegotiationCheck 开始: {} ({})", playerName, playerUuid);
        try {
            AccessDecision decision = whitelistManager.checkAccess(playerName, playerUuid)
                    .get(NEGOTIATION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.info("[协商诊断] 决策={}: {} ({})", decision, playerName, playerUuid);
            if (decision == AccessDecision.ALLOWED) {
                return; // 放行: 玩家继续走到 PLAY, 由 PlayerLoggedInEvent 做加入后处理
            }
            String message = decision == AccessDecision.DISABLED
                    ? formatDisabledMessage(playerName)
                    : formatKickMessage(playerName);
            disconnectDuringLogin(event.getConnection(), Component.literal(message));
            logger.warn("协商阶段拒绝 ({}): {} ({}) IP: {}",
                    decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单",
                    playerName, playerUuid, ipAddress);
            logUnauthorizedAccess(playerName, playerUuid, ipAddress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[Whitelist] 协商阶段查询被中断, 交 PLAY 兜底: {} ({})", playerName, playerUuid);
        } catch (Exception e) {
            // 查询超时/异常: 不在协商阶段动作 (避免误踢), 放行交 PLAY 阶段处理 (含严格模式)。
            logger.warn("[Whitelist] 协商阶段查询未决, 交 PLAY 兜底: {} ({}) - {}",
                    playerName, playerUuid, e.getClass().getSimpleName());
        }
    }

    /**
     * login (协商) 阶段带文案断连。严格镜像原版 ServerLoginPacketListenerImpl#disconnect: 同线程顺序
     * send(ClientboundLoginDisconnectPacket) 再 connection.disconnect。
     *
     * 线程安全的唯一正确写法 (已对照 1.20.1 官方映射源码核实, 见 [[forge-login-disconnect-gotcha]]):
     * 本方法必须在【非 netty 事件循环线程】(performNegotiationCheck 跑在 ForkJoinPool) 上同步顺序调用。
     *  - Connection.send 用 writeAndFlush 且 off-loop 时把写任务排进事件循环; 紧随的 disconnect 之 close 任务
     *    排在其后, 事件循环 FIFO 保证"先刷断开包, 后关通道"。awaitUninterruptibly 阻塞的只是本异步线程(无害)。
     *  - 绝不可用 PacketSendListener.thenRun(...) 包 disconnect (c6f260d), 也绝不可用 channel.eventLoop().execute(disconnect):
     *    二者都把 disconnect 搬到事件循环线程, 而 disconnect 内部 channel.close().awaitUninterruptibly() 在事件循环
     *    线程上等待自身 close future -> netty BlockingOperationException -> 通道异常关闭 -> 客户端 "连接重置"。
     *
     * 注意: 即便写法正确, 在含 packetfixer/xlpackets 的整合包里客户端能否渲染该早期断开包仍不确定; 失败时连接会
     * 走到 PLAY 阶段由 PlayerLoggedInEvent 延迟踢兜底 (文案可见)。故本拦截是 best-effort, 非唯一防线。
     */
    private void disconnectDuringLogin(Connection connection, Component reason) {
        try {
            connection.send(new ClientboundLoginDisconnectPacket(reason));
            connection.disconnect(reason);
        } catch (Exception e) {
            logger.error("协商阶段带文案断连失败, 交 PLAY 阶段兜底", e);
        }
    }

    /**
     * 玩家进入游戏世界后的处理, 对应 v1 WhitelistListener.onPlayerJoin。
     *
     * 三件事 (沿用 v1 行为):
     *  1. UUID 补全: 按名字加入(UUID留空)的白名单条目, 玩家首次登录时补上真实 UUID
     *  2. 欢迎消息: 向玩家发送可配置的欢迎语
     *  3. 加入通知: 向在线 OP 广播白名单玩家加入
     *
     * 注: WhitelistManager 的查询在异步线程池执行, 但向玩家/OP 发包必须回到服务器主线程,
     * 故消息发送统一经 server.execute() 调度。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String name = player.getGameProfile().getName();
        UUID uuidObj = player.getUUID();
        String uuid = uuidObj.toString();
        String ip = formatRemoteAddress(player.connection.connection.getRemoteAddress());
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        Connection connection = player.connection.connection;
        Component packRejection = takePendingPackRejection(connection);
        if (packRejection != null && config.isPackVersionGateEnabled()) {
            rejectAfterJoin(server, uuidObj, connection, name, ip, packRejection,
                    "整合包版本门控", false, true);
            return;
        }
        if (config.isPackVersionGateEnabled()) {
            CompletableFuture.runAsync(
                    () -> performPackVersionPlayFallback(player, server, uuidObj, name, uuid, ip, connection));
            return;
        }
        continueLoginAfterPackGate(player, server, uuidObj, name, uuid, ip);
    }

    /** 协商事件未触发或已放行时，PLAY 阶段重新读取当前 published，保证门控没有旁路。 */
    void performPackVersionPlayFallback(ServerPlayer player,
                                        MinecraftServer server,
                                        UUID uuidObj,
                                        String name,
                                        String uuid,
                                        String ip,
                                        Connection connection) {
        String clientVersion = null;
        try {
            clientVersion = PackVersionChannel.clientPackVersion(connection).orElse(null);
            PackVersionGate.Decision decision = packVersionGate.evaluate(clientVersion);
            if (decision.allowed()) {
                continueLoginAfterPackGate(player, server, uuidObj, name, uuid, ip);
                return;
            }
            Component reason = Component.literal(formatPackVersionRejectMessage(
                    decision.clientVersion(), decision.requiredVersion()));
            rejectAfterJoin(server, uuidObj, connection, name, ip, reason,
                    "整合包版本门控", false, true);
            logger.warn("PLAY 兜底拒绝整合包版本: 玩家={} IP={} 状态={} 客户端版本={} 要求版本={}",
                    name, ip, decision.status(),
                    displayClientVersion(decision.clientVersion()),
                    displayRequiredVersion(decision.requiredVersion()));
        } catch (Exception e) {
            if (!config.isPackVersionGateEnabled()) {
                continueLoginAfterPackGate(player, server, uuidObj, name, uuid, ip);
                return;
            }
            logger.error("PLAY 兜底整合包版本检查失败，按启用状态拒绝: 玩家={} IP={} 客户端版本={}",
                    name, ip, displayClientVersion(clientVersion), e);
            rejectAfterJoin(server, uuidObj, connection, name, ip,
                    Component.literal("§c整合包版本验证失败，请稍后重试或联系管理员"),
                    "整合包版本验证异常", false, true);
        }
    }

    private void continueLoginAfterPackGate(ServerPlayer player,
                                            MinecraftServer server,
                                            UUID uuidObj,
                                            String name,
                                            String uuid,
                                            String ip) {
        if (!config.isWhitelistEnabled()) {
            return;
        }

        // 白名单唯一可靠拦截点 (PlayerLoggedInEvent 为核心事件, 任何环境必然触发)。
        // 放行: 主线程处理加入; 拒绝: 延迟踢出 (rejectAfterJoin), 等客户端完全进服后再踢, 文案方能显示。
        whitelistManager.checkAccess(name, uuid).thenAccept(decision -> {
            if (decision == AccessDecision.ALLOWED) {
                server.execute(() -> processWhitelistedJoin(player, name, uuid));
                return;
            }
            String message = decision == AccessDecision.DISABLED
                    ? formatDisabledMessage(name)
                    : formatKickMessage(name);
            String reasonTag = decision == AccessDecision.DISABLED ? "白名单被禁用" : "未在白名单";
            rejectAfterJoin(server, uuidObj, player.connection.connection, name, ip,
                    Component.literal(message), reasonTag, true, false);
        }).exceptionally(t -> {
            // 查询异常: 严格模式踢人, 宽松模式放行
            if (config.isWhitelistStrictMode()) {
                rejectAfterJoin(server, uuidObj, player.connection.connection, name, ip,
                        Component.literal("§c白名单验证失败, 请稍后重试"),
                        "查询异常", true, false);
                logger.warn("[Whitelist] 严格模式踢出 (查询异常): {} - {}", name, t.getMessage());
            } else {
                logger.warn("[Whitelist] 宽松模式放行 (查询异常): {} - {}", name, t.getMessage());
            }
            return null;
        });
    }

    /**
     * 延迟 {@link #REJECT_DELAY_SECONDS} 秒后踢出被拒玩家。延迟原因见该常量注释:
     * join tick 立即踢会让客户端只显示"连接中断"而非文案。延迟后等价一次正常 /kick。
     * 踢出前按 UUID 重新取在线玩家 (期间可能已自行离开/换对象), 并校验 server 仍在运行。
     */
    void rejectAfterJoin(MinecraftServer server,
                         UUID uuid,
                         Connection expectedConnection,
                         String name,
                         String ip,
                         Component message,
                         String reasonTag,
                         boolean auditUnauthorizedAccess,
                         boolean requiresPackVersionGate) {
        CompletableFuture.delayedExecutor(REJECT_DELAY_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!server.isRunning()) {
                return;
            }
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                if (online == null || !shouldExecuteDelayedRejection(
                        expectedConnection,
                        online.connection.connection,
                        requiresPackVersionGate,
                        config::isPackVersionGateEnabled)) {
                    return; // 期间已离开或同 UUID 已建立新会话，不能误踢重连玩家
                }
                online.connection.disconnect(message);
                logger.warn("拒绝玩家进入 ({}): {} ({}) IP: {}", reasonTag, name, uuid, ip);
                if (auditUnauthorizedAccess) {
                    logUnauthorizedAccess(name, uuid.toString(), ip);
                }
            });
        });
    }

    static boolean shouldExecuteDelayedRejection(Connection expectedConnection,
                                                 Connection onlineConnection,
                                                 boolean requiresPackVersionGate,
                                                 BooleanSupplier packVersionGateEnabled) {
        return onlineConnection == expectedConnection
                && (!requiresPackVersionGate || packVersionGateEnabled.getAsBoolean());
    }

    /**
     * 白名单内玩家的加入后处理: UUID 补全 + 欢迎 + 通知 (对应 v1 onPlayerJoin)。
     * 调用前已确认在白名单中 (checkAccess 返回 ALLOWED)。
     */
    private void processWhitelistedJoin(ServerPlayer player, String name, String uuid) {
        whitelistManager.getPlayerByUuid(uuid).thenCompose(byUuid -> {
            if (byUuid.isPresent()) {
                handleJoin(player, byUuid.get());
                return CompletableFuture.completedFuture(null);
            }
            // UUID 未命中, 按名字找 (UUID 待补充的条目), 补全 UUID
            return whitelistManager.getPlayerByName(name).thenAccept(byName -> {
                if (byName.isEmpty()) {
                    return;
                }
                WhitelistEntry entry = byName.get();
                if (entry.getUuid() == null || entry.getUuid().trim().isEmpty()) {
                    whitelistManager.updatePlayerUuid(name, uuid).thenAccept(ok -> {
                        if (ok) {
                            logger.info("已为玩家 {} 补充 UUID: {}", name, uuid);
                            handleJoin(player, entry);
                        } else {
                            logger.warn("为玩家 {} 补充 UUID 失败", name);
                        }
                    });
                } else {
                    logger.warn("同名玩家 UUID 不匹配: {} (库内: {}, 当前: {})",
                            name, entry.getUuid(), uuid);
                    handleJoin(player, entry);
                }
            });
        }).exceptionally(t -> {
            logger.error("处理玩家 {} 加入事件失败", name, t);
            return null;
        });
    }

    /** 加入后通知 + 欢迎消息, 统一回主线程执行发包。 */
    private void handleJoin(ServerPlayer player, WhitelistEntry entry) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (config.isJoinNotificationEnabled()) {
                notifyOnlineOps(server, player, entry);
            }
            if (config.isWelcomeMessageEnabled()) {
                sendWelcome(player);
            }
        });
    }

    /** 向在线 OP (权限等级>=2) 广播白名单玩家加入。Forge 无 Bukkit 权限节点, 用 OP 等级替代 v1 的权限节点过滤。 */
    private void notifyOnlineOps(MinecraftServer server, ServerPlayer joined, WhitelistEntry entry) {
        String text = "§a§l[白名单] §e" + joined.getGameProfile().getName()
                + " §7已加入服务器 §8(添加者: " + entry.getAddedByName() + ")";
        Component message = Component.literal(text);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.hasPermissions(2)) {
                online.sendSystemMessage(message);
            }
        }
    }

    /** 向玩家发送可配置欢迎消息, & 颜色码转 §, {player} 占位符替换。 */
    private void sendWelcome(ServerPlayer player) {
        String text = config.getWelcomeMessage()
                .replace("{player}", player.getGameProfile().getName())
                .replace("&", "§");
        player.sendSystemMessage(Component.literal(text));
    }

    private String formatKickMessage(String playerName) {
        return config.getWhitelistKickMessage()
                .replace("{player}", playerName)
                .replace("{contact}", config.getContactInfo())
                .replace("&", "§");
    }

    private String formatPackVersionRejectMessage(String clientVersion, String requiredVersion) {
        return config.getPackVersionRejectMessage()
                .replace("{current}", displayClientVersion(clientVersion))
                .replace("{required}", displayRequiredVersion(requiredVersion))
                .replace("&", "§");
    }

    private static String displayClientVersion(String version) {
        return displayVersion(version, "未上报");
    }

    private static String displayRequiredVersion(String version) {
        return displayVersion(version, "服务端暂无已发布版本");
    }

    /** 客户端版本可伪造；展示前去控制字符并限长，避免污染服务端日志与断连文案。 */
    private static String displayVersion(String version, String missingText) {
        if (version == null || version.isBlank()) {
            return missingText;
        }
        int length = Math.min(version.length(), MAX_DISPLAYED_PACK_VERSION_LENGTH);
        StringBuilder safe = new StringBuilder(length + 3);
        for (int i = 0; i < length; i++) {
            char ch = version.charAt(i);
            safe.append(Character.isISOControl(ch) ? '?' : ch);
        }
        if (version.length() > MAX_DISPLAYED_PACK_VERSION_LENGTH) {
            safe.append("...");
        }
        return safe.toString();
    }

    /** 在白名单但被管理员手动禁用时的提示文案。 */
    private String formatDisabledMessage(String playerName) {
        return config.getWhitelistDisabledMessage()
                .replace("{player}", playerName)
                .replace("{contact}", config.getContactInfo())
                .replace("&", "§");
    }

    /**
     * 解析玩家的真实来源 IP, 供审计日志使用。
     *
     * 玩家经 frp 中转进来时, Minecraft 看到的源地址是转发器的回环地址, 直接记录等于把整张
     * 审计表写成 127.0.0.1。真实地址由转发器在 PROXY protocol 头里取到并登记在会话表中,
     * 这里按来源端口取回。
     *
     * 只在来源是回环地址时查表: 经转发器的连接必然来自回环, 加这道判断可避免外部直连玩家的
     * 临时端口恰好撞上某个会话端口时张冠李戴。查不到就退回 TCP 层地址 (内网直连的正常情况)。
     */
    private String formatRemoteAddress(SocketAddress addr) {
        if (!(addr instanceof InetSocketAddress inet)) {
            return addr != null ? addr.toString() : "unknown";
        }
        InetAddress address = inet.getAddress();
        if (sessionRegistry != null && address != null && address.isLoopbackAddress()) {
            NodeSession session = sessionRegistry.findByUpstreamPort(inet.getPort());
            if (session != null && session.clientIp() != null) {
                return session.clientIp();
            }
        }
        return address != null ? address.getHostAddress() : inet.getHostString();
    }

    /**
     * 写入 operation_log 表的 UNAUTHORIZED_ACCESS 审计条目.
     * 直接走 databaseManager 而非 OperationLogDao, 因为 DAO 暂无对应方法, 沿用 v1 实现.
     */
    private void logUnauthorizedAccess(String playerName, String playerUuid, String ipAddress) {
        databaseManager.executeAsync(connection -> {
            String sql = """
                    INSERT INTO operation_log
                    (operation_type, target_uuid, target_name, operator_ip, operator_agent,
                     request_data, response_status, execution_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, "UNAUTHORIZED_ACCESS");
                ps.setString(2, playerUuid);
                ps.setString(3, playerName);
                ps.setString(4, ipAddress);
                ps.setString(5, "Minecraft Client");
                ps.setString(6, String.format(
                        "{\"reason\":\"not_in_whitelist\",\"player\":\"%s\",\"uuid\":\"%s\"}",
                        playerName, playerUuid));
                ps.setInt(7, 403);
                ps.setLong(8, 0);
                return ps.executeUpdate();
            }
        }).exceptionally(throwable -> {
            logger.warn("写入未授权访问审计日志失败: {} ({})", playerName, playerUuid, throwable);
            return 0;
        });
    }
}
