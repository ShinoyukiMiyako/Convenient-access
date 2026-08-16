package com.shinoyuki.accesshub.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;
import com.shinoyuki.accesshub.config.AccessHubConfig;
import com.shinoyuki.accesshub.database.DatabaseManager;
import com.shinoyuki.accesshub.net.NodeSessionRegistry;
import com.shinoyuki.accesshub.pack.PackVersionGate;
import com.shinoyuki.accesshub.pack.net.PackVersionChannel;
import com.shinoyuki.accesshub.whitelist.WhitelistManager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class PlayerLoginListenerPackVersionTest {

    @Test
    void loginRejectionSurvivesFailedEarlyDisconnectForPlayFallback() throws Exception {
        AccessHubConfig config = mock(AccessHubConfig.class);
        PackVersionGate gate = mock(PackVersionGate.class);
        PlayerLoginListener listener = listener(config, gate);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        ChannelFuture closeFuture = mock(ChannelFuture.class);
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        when(config.isWhitelistEnabled()).thenReturn(false);
        when(config.getPackVersionRejectMessage()).thenReturn("&c{current} -> {required}");
        when(connection.isConnected()).thenReturn(true);
        when(connection.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25565));
        when(connection.channel()).thenReturn(channel);
        when(channel.closeFuture()).thenReturn(closeFuture);
        when(gate.evaluate("1.0.0")).thenReturn(new PackVersionGate.Decision(
                false, PackVersionGate.Status.VERSION_MISMATCH, "1.0.0", "2.0.0"));
        doThrow(new IllegalStateException("simulated send failure"))
                .when(connection).send(any(ClientboundLoginDisconnectPacket.class));
        List<Future<Void>> pendingWork = new ArrayList<>();
        PlayerNegotiationEvent event = new PlayerNegotiationEvent(
                connection, new GameProfile(UUID.randomUUID(), "Alice"), pendingWork);

        try (MockedStatic<PackVersionChannel> versionChannel = mockStatic(PackVersionChannel.class)) {
            versionChannel.when(() -> PackVersionChannel.awaitClientPackVersion(
                            eq(connection), any(BooleanSupplier.class)))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of("1.0.0")));

            listener.onPlayerNegotiation(event);
            assertEquals(1, pendingWork.size());
            pendingWork.get(0).get(1, TimeUnit.SECONDS);
        }

        Component pendingRejection = listener.takePendingPackRejection(connection);
        assertNotNull(pendingRejection);
        assertEquals("§c1.0.0 -> 2.0.0", pendingRejection.getString());
        verify(connection).send(any(ClientboundLoginDisconnectPacket.class));
        verify(connection, never()).disconnect(any(Component.class));
    }

    @Test
    void playFallbackReadsAndEvaluatesVersionOnEveryInvocation() throws Exception {
        AccessHubConfig config = mock(AccessHubConfig.class);
        PackVersionGate gate = mock(PackVersionGate.class);
        PlayerLoginListener listener = listener(config, gate);
        Connection connection = mock(Connection.class);
        UUID uuid = UUID.randomUUID();
        when(config.isWhitelistEnabled()).thenReturn(false);
        when(gate.evaluate("1.0.0")).thenReturn(new PackVersionGate.Decision(
                true, PackVersionGate.Status.MATCH, "1.0.0", "1.0.0"));
        when(gate.evaluate("2.0.0")).thenReturn(new PackVersionGate.Decision(
                true, PackVersionGate.Status.MATCH, "2.0.0", "2.0.0"));

        try (MockedStatic<PackVersionChannel> versionChannel = mockStatic(PackVersionChannel.class)) {
            versionChannel.when(() -> PackVersionChannel.clientPackVersion(connection))
                    .thenReturn(Optional.of("1.0.0"), Optional.of("2.0.0"));

            listener.performPackVersionPlayFallback(
                    null, null, uuid, "Alice", uuid.toString(), "127.0.0.1", connection);
            listener.performPackVersionPlayFallback(
                    null, null, uuid, "Alice", uuid.toString(), "127.0.0.1", connection);

            versionChannel.verify(() -> PackVersionChannel.clientPackVersion(connection),
                    org.mockito.Mockito.times(2));
        }

        verify(gate).evaluate("1.0.0");
        verify(gate).evaluate("2.0.0");
    }

    @Test
    void playFallbackConnectionReadFailureStillSchedulesRejection() {
        AccessHubConfig config = mock(AccessHubConfig.class);
        PackVersionGate gate = mock(PackVersionGate.class);
        PlayerLoginListener listener = spy(listener(config, gate));
        Connection connection = mock(Connection.class);
        UUID uuid = UUID.randomUUID();
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        doNothing().when(listener).rejectAfterJoin(
                any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());

        try (MockedStatic<PackVersionChannel> versionChannel = mockStatic(PackVersionChannel.class)) {
            versionChannel.when(() -> PackVersionChannel.clientPackVersion(connection))
                    .thenThrow(new IllegalStateException("simulated connection-data failure"));

            listener.performPackVersionPlayFallback(
                    null, null, uuid, "Alice", uuid.toString(), "127.0.0.1", connection);
        }

        ArgumentCaptor<Component> rejection = ArgumentCaptor.forClass(Component.class);
        verify(listener).rejectAfterJoin(
                any(), eq(uuid), eq(connection), eq("Alice"), eq("127.0.0.1"),
                rejection.capture(), eq("整合包版本验证异常"), eq(false), eq(true));
        assertEquals("§c整合包版本验证失败，请稍后重试或联系管理员", rejection.getValue().getString());
        verifyNoInteractions(gate);
    }

    @Test
    void closingGateCancelsQueuedPackRejection() {
        Connection connection = mock(Connection.class);

        assertFalse(PlayerLoginListener.shouldExecuteDelayedRejection(
                connection, connection, true, () -> false));
        assertTrue(PlayerLoginListener.shouldExecuteDelayedRejection(
                connection, connection, true, () -> true));
    }

    @Test
    void oldConnectionCannotRejectReplacementSession() {
        Connection oldConnection = mock(Connection.class);
        Connection replacementConnection = mock(Connection.class);
        BooleanSupplier gateEnabled = mock(BooleanSupplier.class);

        assertFalse(PlayerLoginListener.shouldExecuteDelayedRejection(
                oldConnection, replacementConnection, true, gateEnabled));
        verifyNoInteractions(gateEnabled);
    }

    private static PlayerLoginListener listener(AccessHubConfig config, PackVersionGate gate) {
        return new PlayerLoginListener(
                config,
                mock(WhitelistManager.class),
                mock(DatabaseManager.class),
                mock(NodeSessionRegistry.class),
                gate);
    }
}
