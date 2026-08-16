package com.shinoyuki.accesshub.pack.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;

class PackVersionChannelTest {

    @BeforeEach
    void clearPropertyBeforeTest() {
        System.clearProperty(PackVersionChannel.CLIENT_PACK_VERSION_PROPERTY);
    }

    @AfterEach
    void clearPropertyAfterTest() {
        System.clearProperty(PackVersionChannel.CLIENT_PACK_VERSION_PROPERTY);
    }

    @Test
    void advertisesTrimmedVersionInjectedByLauncher() {
        System.setProperty(PackVersionChannel.CLIENT_PACK_VERSION_PROPERTY, " 2.0.0 ");

        assertEquals("2.0.0", PackVersionChannel.advertisedPackVersion());
    }

    @Test
    void missingLauncherPropertyIsAdvertisedAsEmptyVersion() {
        assertEquals("", PackVersionChannel.advertisedPackVersion());
    }

    @Test
    void readsOnlyTheDedicatedPackVersionChannel() {
        Map<ResourceLocation, String> channels = Map.of(
                PackVersionChannel.CHANNEL_NAME, "2.0.0",
                ResourceLocation.fromNamespaceAndPath("example", "other"), "9.9.9");

        assertEquals("2.0.0", PackVersionChannel.clientPackVersion(channels).orElseThrow());
        assertEquals("", PackVersionChannel.clientPackVersion(
                Map.of(PackVersionChannel.CHANNEL_NAME, "")).orElseThrow());
        assertTrue(PackVersionChannel.clientPackVersion(Map.of()).isEmpty());
    }

    @Test
    void vanillaConnectionImmediatelyReportsMissingVersion() throws Exception {
        Connection connection = mock(Connection.class);
        PackVersionChannel.ConnectionProbe probe = mock(PackVersionChannel.ConnectionProbe.class);
        when(probe.isVanilla(connection)).thenReturn(true);

        Optional<String> version = PackVersionChannel
                .awaitClientPackVersion(connection, () -> true, probe)
                .get(500, TimeUnit.MILLISECONDS);

        assertTrue(version.isEmpty());
        verify(probe, never()).channelVersions(connection);
        verify(connection, never()).isConnected();
    }

    @Test
    void moddedConnectionWaitsUntilForgePublishesChannelData() throws Exception {
        Connection connection = mock(Connection.class);
        PackVersionChannel.ConnectionProbe probe = mock(PackVersionChannel.ConnectionProbe.class);
        when(connection.isConnected()).thenReturn(true);
        when(probe.isVanilla(connection)).thenReturn(false);
        when(probe.channelVersions(connection)).thenReturn(
                null, Map.of(PackVersionChannel.CHANNEL_NAME, "2.0.0"));

        Optional<String> version = PackVersionChannel
                .awaitClientPackVersion(connection, () -> true, probe)
                .get(1, TimeUnit.SECONDS);

        assertEquals("2.0.0", version.orElseThrow());
        verify(probe, atLeast(2)).channelVersions(connection);
    }

    @Test
    void disconnectedModdedConnectionStopsWaiting() throws Exception {
        Connection connection = mock(Connection.class);
        PackVersionChannel.ConnectionProbe probe = mock(PackVersionChannel.ConnectionProbe.class);
        when(connection.isConnected()).thenReturn(false);
        when(probe.isVanilla(connection)).thenReturn(false);
        when(probe.channelVersions(connection)).thenReturn(null);

        Optional<String> version = PackVersionChannel
                .awaitClientPackVersion(connection, () -> true, probe)
                .get(500, TimeUnit.MILLISECONDS);

        assertTrue(version.isEmpty());
        verify(connection).isConnected();
    }

    @Test
    void disablingGateReleasesModdedConnectionWaitingForChannelData() throws Exception {
        Connection connection = mock(Connection.class);
        PackVersionChannel.ConnectionProbe probe = mock(PackVersionChannel.ConnectionProbe.class);
        AtomicBoolean enabled = new AtomicBoolean(true);
        when(connection.isConnected()).thenReturn(true);
        when(probe.isVanilla(connection)).thenReturn(false);
        when(probe.channelVersions(connection)).thenReturn(null);

        CompletableFuture<Optional<String>> pending =
                PackVersionChannel.awaitClientPackVersion(connection, enabled::get, probe);
        assertFalse(pending.isDone());

        enabled.set(false);

        assertTrue(pending.get(1, TimeUnit.SECONDS).isEmpty());
    }
}
