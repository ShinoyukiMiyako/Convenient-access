package com.shinoyuki.accesshub.tablist;

import java.lang.management.ManagementFactory;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.config.AccessHubConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Tab 列表增强: 服务端每隔若干 tick 下发 header/footer, 并给每个玩家名挂上彩色延迟。
 *
 * 走原版 {@link ClientboundTabListPacket} 与 Forge 的 TabListNameFormat 事件, 客户端不需要装 mod。
 * 延迟直接读 {@code ServerPlayer.latency} —— 该字段在 1.20.1 official mapping 下公开可访问,
 * 与 PlayerDataHandlerImpl / ServerInfoHandlerImpl 取 ping 的口径一致, 无需 mixin。
 */
public final class TabListService {

    private static final Logger logger = LoggerFactory.getLogger(TabListService.class);

    /** 取不到 CPU 负载时的哨兵值, 渲染层据此显示 "--%" 而不是伪造 0。 */
    private static final double CPU_LOAD_UNAVAILABLE = -1.0;

    private static final com.sun.management.OperatingSystemMXBean OS_BEAN = resolveOsBean();

    private final AccessHubConfig config;
    private final MinecraftServer server;
    private final long startNanos;

    private int tickCounter;
    /** 广播连续失败时只记一次日志: 该路径每秒跑两次, 逐次 warn 会淹掉真正有用的关服/报错信息。 */
    private boolean broadcastFailureLogged;

    public TabListService(AccessHubConfig config, MinecraftServer server) {
        this.config = config;
        this.server = server;
        this.startNanos = System.nanoTime();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !config.isTabListEnabled()) {
            return;
        }
        int interval = Math.max(1, config.getTabListBroadcastIntervalTicks());
        if (++tickCounter % interval != 0) {
            return;
        }
        try {
            broadcast();
            broadcastFailureLogged = false;
        } catch (Throwable t) {
            // Tab 只是展示层, 渲染或下发出问题不该把服务器 tick 带崩, 就地降级并保留现场。
            if (!broadcastFailureLogged) {
                broadcastFailureLogged = true;
                logger.warn("Tab 列表广播失败, 后续同类错误不再重复记录", t);
            }
        }
    }

    /**
     * Forge 在需要玩家 Tab 显示名时触发。此处附加彩色延迟后缀。
     */
    @SubscribeEvent
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!config.isTabListEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        event.setDisplayName(TabListRenderer.buildDisplayName(
                player,
                player.latency,
                config.getTabListLatencyGreen(),
                config.getTabListLatencyYellow()));
    }

    private void broadcast() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        // getAverageTickTime 是原版自己维护的平滑均值, 同步且零分配; 与 /accesshub 命令展示同一口径。
        // 不走 SparkIntegration: 那条路异步返回且最细窗口为 10 秒, 不适合 tick 内的实时刷新。
        double mspt = Math.max(0.01, server.getAverageTickTime());
        double tps = Math.min(20.0, 1000.0 / mspt);

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();

        Component header = TabListRenderer.buildHeader((System.nanoTime() - startNanos) / 1_000_000L);
        Component footer = TabListRenderer.buildFooter(tps, mspt, processCpuLoad(), heapUsed, runtime.maxMemory());
        ClientboundTabListPacket packet = new ClientboundTabListPacket(header, footer);

        for (ServerPlayer player : players) {
            player.connection.send(packet);
            // 触发 TabListNameFormat, 名字变了才会广播 UPDATE_DISPLAY_NAME, 因此延迟没变化时不产生额外流量。
            player.refreshTabListName();
        }
    }

    private static double processCpuLoad() {
        if (OS_BEAN == null) {
            return CPU_LOAD_UNAVAILABLE;
        }
        double load = OS_BEAN.getProcessCpuLoad();
        if (Double.isNaN(load) || load < 0.0) {
            return CPU_LOAD_UNAVAILABLE;
        }
        return Math.min(1.0, load);
    }

    /** 非 HotSpot JVM 上 OperatingSystemMXBean 可能不是 com.sun 实现, 此时放弃 CPU 指标而不是崩掉。 */
    private static com.sun.management.OperatingSystemMXBean resolveOsBean() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sunBean ? sunBean : null;
    }
}
