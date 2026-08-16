package com.shinoyuki.accesshub.pack.net;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.shinoyuki.accesshub.AccessHubMod;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 在 Forge 登录握手的通道版本表中携带 Aurora 已应用的整合包版本。
 *
 * 不能把版本塞进既有 C2SHello：它由 ClientPlayerNetworkEvent.LoggingIn 在 PLAY 阶段发送，晚于
 * PlayerNegotiationEvent，届时玩家已经进入世界。Forge 47.4.20 会在服务端处理 C2SModListReply 时把
 * 客户端通道版本写入 ConnectionData；协商事件挂起的异步任务可在登录继续推进时读取该值。
 *
 * 本通道没有业务消息，双向兼容谓词始终放行，包括 Forge 的 ABSENT 与 ACCEPTVANILLA：协议层只负责
 * 传值，是否拒绝完全由可一键关闭的 PackVersionGate 决定。老客户端不注册本通道，门控开启时按
 * "未上报版本"处理；新客户端连接老服务端时，多出的客户端通道不会被老服务端的已注册通道集合校验，
 * 因而不会触发 Forge 的 mismatched channel list。
 */
public final class PackVersionChannel {

    public static final String CLIENT_PACK_VERSION_PROPERTY = "shinoyuki.accesshub.pack-version";
    public static final ResourceLocation CHANNEL_NAME =
            ResourceLocation.fromNamespaceAndPath(AccessHubMod.MOD_ID, "pack_version");

    private static final long POLL_INTERVAL_MILLIS = 50;
    private static final ConnectionProbe FORGE_CONNECTION_PROBE = new ConnectionProbe() {
        @Override
        public boolean isVanilla(Connection connection) {
            return NetworkHooks.getConnectionType(() -> connection) == ConnectionType.VANILLA;
        }

        @Override
        public Map<ResourceLocation, String> channelVersions(Connection connection) {
            ConnectionData data = NetworkHooks.getConnectionData(connection);
            return data == null ? null : data.getChannels();
        }
    };

    private PackVersionChannel() {}

    /** 在 FMLCommonSetupEvent 调用，确保通道在 Forge 锁定注册表前完成创建。 */
    public static void register() {
        ChannelHolder.initialize();
    }

    /**
     * 等待 Forge 收到客户端 C2SModListReply 并写入 ConnectionData。
     *
     * PlayerNegotiationEvent 先于首个登录 payload 触发，但其 enqueueWork future 不会阻止握手包继续收发。
     * 纯 vanilla 连接没有 ConnectionData，立即返回未上报；modded 连接用延迟任务轮询，不占住工作线程，
     * 也不设置会在低 TPS 或大量 login payload 下误伤合法客户端的墙钟超时。
     */
    public static CompletableFuture<Optional<String>> awaitClientPackVersion(
            Connection connection,
            BooleanSupplier shouldContinueWaiting) {
        return awaitClientPackVersion(connection, shouldContinueWaiting, FORGE_CONNECTION_PROBE);
    }

    static CompletableFuture<Optional<String>> awaitClientPackVersion(
            Connection connection,
            BooleanSupplier shouldContinueWaiting,
            ConnectionProbe connectionProbe) {
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        pollConnectionData(connection, shouldContinueWaiting, connectionProbe, result);
        return result;
    }

    private static void pollConnectionData(Connection connection,
                                           BooleanSupplier shouldContinueWaiting,
                                           ConnectionProbe connectionProbe,
                                           CompletableFuture<Optional<String>> result) {
        if (result.isDone()) {
            return;
        }
        try {
            if (!shouldContinueWaiting.getAsBoolean()) {
                result.complete(Optional.empty());
                return;
            }
            if (connectionProbe.isVanilla(connection)) {
                result.complete(Optional.empty());
                return;
            }
            Map<ResourceLocation, String> channelVersions = connectionProbe.channelVersions(connection);
            if (channelVersions != null) {
                result.complete(clientPackVersion(channelVersions));
                return;
            }
            if (!connection.isConnected()) {
                result.complete(Optional.empty());
                return;
            }
            CompletableFuture.delayedExecutor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(() -> pollConnectionData(
                            connection, shouldContinueWaiting, connectionProbe, result));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
    }

    /** PLAY 阶段兜底读取；此时 Forge 握手已经完成，不再等待。 */
    public static Optional<String> clientPackVersion(Connection connection) {
        Map<ResourceLocation, String> channelVersions =
                FORGE_CONNECTION_PROBE.channelVersions(connection);
        return channelVersions == null ? Optional.empty() : clientPackVersion(channelVersions);
    }

    static Optional<String> clientPackVersion(Map<ResourceLocation, String> channels) {
        return Optional.ofNullable(channels.get(CHANNEL_NAME));
    }

    static String advertisedPackVersion() {
        String version = System.getProperty(CLIENT_PACK_VERSION_PROPERTY);
        return version == null ? "" : version.trim();
    }

    interface ConnectionProbe {
        boolean isVanilla(Connection connection);
        Map<ResourceLocation, String> channelVersions(Connection connection);
    }

    private static final class ChannelHolder {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(CHANNEL_NAME)
                .networkProtocolVersion(PackVersionChannel::advertisedPackVersion)
                .clientAcceptedVersions(remoteVersion -> true)
                .serverAcceptedVersions(remoteVersion -> true)
                .simpleChannel();

        private static void initialize() {}
    }
}
