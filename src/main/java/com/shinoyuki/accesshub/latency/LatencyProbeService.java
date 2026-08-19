package com.shinoyuki.accesshub.latency;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shinoyuki.accesshub.config.AccessHubConfig;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 主动延迟探针: 用原版 {@code ClientboundPingPacket} / {@code ServerboundPongPacket} 高频测量链路 RTT,
 * 取代原版 keep-alive 那条又慢又滞后的通道, 并把结果写回 {@code ServerPlayer.latency}。
 *
 * <p>解决的是原版三段串联的滞后 (1.20.1 实测):
 * <ol>
 *   <li>{@code ServerGamePacketListenerImpl} 每 15000ms 才发一个 keep-alive, 即 15 秒一个样本;</li>
 *   <li>{@code latency = (latency * 3 + i) / 4} 是 α=0.25 的 EWMA, 阶跃响应 1-0.75^n,
 *       到 90% 需要 8 个样本 = 120 秒;</li>
 *   <li>{@code PlayerList.tick} 每 600 tick (30 秒) 才广播一次 UPDATE_LATENCY。</li>
 * </ol>
 * 三者叠加, 玩家 tab 上的延迟最坏要一百多秒才反映真值。
 *
 * <p>本探针把采样率提到默认 5Hz, 估计器换成滑动窗口取 min (见 {@link LatencyEstimator}),
 * 广播改为按需增量下发, 于是同样的 90% 准确度在数秒内达成。
 *
 * <p>选 Ping/Pong 而非劫持 keep-alive: 服务端 {@code handlePong} 是空实现, 截取它零副作用;
 * 而 {@code handleKeepAlive} 在 id 不匹配时直接 {@code disconnect(timeout)}, 想复用就必须 cancel
 * 一个带踢人逻辑的原版方法, 与其它 mod / 反作弊的冲突面太大。代价是原版客户端的 {@code handlePing}
 * 带 ensureRunningOnSameThread, 回包要等下一帧, 这份正向噪声由窗口取 min 与探测抖动来摊薄。
 *
 * <p>客户端无需安装任何 mod: 原版客户端收到 ping 一定回 pong。
 */
public final class LatencyProbeService {

    private static final Logger logger = LoggerFactory.getLogger(LatencyProbeService.class);

    /** 原版把 {@code Connection} 自己以这个名字挂在 pipeline 末尾, 探针必须插在它之前才能先于分发打点。 */
    private static final String VANILLA_PACKET_HANDLER = "packet_handler";

    /** 跳变判定的倍数门槛: 新样本高过当前估计 30% 才算候选。 */
    private static final double JUMP_RATIO = 1.3;

    /** 跳变判定的绝对门槛。低延迟下 30% 只有零点几毫秒, 只看倍数会被正常抖动反复触发。 */
    private static final long JUMP_FLOOR_NANOS = 5_000_000L;

    /** 连续多少个高样本才认定真值上移。要求连续, 是为了不让单次 GC 停顿抬高估计。 */
    private static final int JUMP_CONFIRMATIONS = 2;

    /** 登录后的突发探测数, 让首个可信估计在几百毫秒内出来。 */
    private static final int LOGIN_BURST_SAMPLES = 5;

    private final AccessHubConfig config;
    private final MinecraftServer server;
    private final Map<UUID, PlayerLatencyProbe> probes = new ConcurrentHashMap<>();

    private int publishCounter;
    /** pipeline 注入失败只记一次: 该路径每个玩家登录都会走, 逐次 warn 会淹掉真正有用的日志。 */
    private boolean attachFailureLogged;

    public LatencyProbeService(AccessHubConfig config, MinecraftServer server) {
        this.config = config;
        this.server = server;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!config.isLatencyProbeEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        LatencyEstimator estimator = new LatencyEstimator(
                Math.max(1, config.getLatencyWindowSamples()), JUMP_RATIO, JUMP_FLOOR_NANOS, JUMP_CONFIRMATIONS);
        PlayerLatencyProbe probe = new PlayerLatencyProbe(estimator, LOGIN_BURST_SAMPLES);
        probes.put(player.getUUID(), probe);
        attach(player, probe);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 不判 enabled: 运行中被 /accesshub reload 关掉后, 已建立的探针状态照样要回收
        probes.remove(player.getUUID());
        detach(player);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !config.isLatencyProbeEnabled()) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        probePlayers(players);

        int publishInterval = Math.max(1, config.getLatencyPublishIntervalTicks());
        if (++publishCounter % publishInterval == 0) {
            publishEstimates(players);
        }
    }

    /** 当前估计, 无样本时为 {@link LatencyEstimator#NO_ESTIMATE}。供命令 / API 读取免走 player.latency 的滞回。 */
    public int estimateMillis(UUID playerId) {
        PlayerLatencyProbe probe = probes.get(playerId);
        return probe == null ? LatencyEstimator.NO_ESTIMATE : probe.publishedMillis();
    }

    private void probePlayers(List<ServerPlayer> players) {
        int interval = Math.max(1, config.getLatencyProbeIntervalTicks());
        int jitter = Math.max(0, config.getLatencyProbeJitterTicks());
        for (ServerPlayer player : players) {
            PlayerLatencyProbe probe = probes.get(player.getUUID());
            if (probe == null || !probe.tickDueForProbe(interval, jitter)) {
                continue;
            }
            int id = probe.allocateProbeId();
            // 时间戳打在写出完成回调里而不是这里: Connection.send 从主线程调用时会先 execute 进 eventLoop,
            // 那段排队若算进 RTT, 服务器越卡延迟显示得越高, 就不再是链路口径了。
            player.connection.send(
                    new ClientboundPingPacket(id),
                    PacketSendListener.thenRun(() -> probe.markSent(id, System.nanoTime())));
        }
    }

    /**
     * 把估计写回 {@code ServerPlayer.latency} 并增量广播。
     *
     * <p>写回而不是另建一套显示通道, 是为了让原版信号格图标、Tab 显示名、HTTP API 三个消费方
     * 共用同一个真值, 不必各自改造。滞回则同时保护两条广播路径: 延迟数字一变,
     * Tab 显示名就要发一次 UPDATE_DISPLAY_NAME 给全服, 是 O(N^2) 流量, 高频探测下必须压住。
     */
    private void publishEstimates(List<ServerPlayer> players) {
        int hysteresis = Math.max(0, config.getLatencyPublishHysteresisMs());
        List<ServerPlayer> changed = new ArrayList<>();
        for (ServerPlayer player : players) {
            PlayerLatencyProbe probe = probes.get(player.getUUID());
            if (probe == null) {
                continue;
            }
            int estimate = probe.publishedMillis();
            if (estimate == LatencyEstimator.NO_ESTIMATE) {
                continue;
            }
            int current = player.latency;
            if (Math.abs(estimate - current) < Math.max(hysteresis, current / 10)) {
                continue;
            }
            player.latency = estimate;
            changed.add(player);
        }
        if (changed.isEmpty()) {
            return;
        }
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), changed));
    }

    private void attach(ServerPlayer player, PlayerLatencyProbe probe) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get(PongInterceptor.HANDLER_NAME) != null) {
            return;
        }
        if (pipeline.get(VANILLA_PACKET_HANDLER) == null) {
            if (!attachFailureLogged) {
                attachFailureLogged = true;
                logger.warn("连接 pipeline 中找不到 {}, 延迟探针无法打点, 延迟将回落到原版 keep-alive 口径",
                        VANILLA_PACKET_HANDLER);
            }
            return;
        }
        pipeline.addBefore(VANILLA_PACKET_HANDLER, PongInterceptor.HANDLER_NAME, new PongInterceptor(probe));
    }

    private void detach(ServerPlayer player) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get(PongInterceptor.HANDLER_NAME) != null) {
            pipeline.remove(PongInterceptor.HANDLER_NAME);
        }
    }
}
