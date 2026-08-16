package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shinoyuki.accesshub.config.AccessHubConfig;

class PackVersionGateTest {

    private AccessHubConfig config;
    private PackManifestRepository repository;
    private PackVersionGate gate;

    @BeforeEach
    void setUp() {
        config = mock(AccessHubConfig.class);
        repository = mock(PackManifestRepository.class);
        gate = new PackVersionGate(config, repository);
    }

    @Test
    void disabledGateAllowsEveryClientWithoutQueryingDatabase() throws Exception {
        when(config.isPackVersionGateEnabled()).thenReturn(false);

        PackVersionGate.Decision decision = gate.evaluate(null);

        assertTrue(decision.allowed());
        assertEquals(PackVersionGate.Status.DISABLED, decision.status());
        verify(repository, never()).findCurrentPublished();
    }

    @Test
    void matchingPublishedVersionIsAllowed() throws Exception {
        enableWithPublished("2.0.0");

        PackVersionGate.Decision decision = gate.evaluate("2.0.0");

        assertTrue(decision.allowed());
        assertEquals(PackVersionGate.Status.MATCH, decision.status());
        assertEquals("2.0.0", decision.requiredVersion());
    }

    @Test
    void mismatchingVersionIsRejectedWithBothVersions() throws Exception {
        enableWithPublished("2.0.0");

        PackVersionGate.Decision decision = gate.evaluate("1.9.4");

        assertFalse(decision.allowed());
        assertEquals(PackVersionGate.Status.VERSION_MISMATCH, decision.status());
        assertEquals("1.9.4", decision.clientVersion());
        assertEquals("2.0.0", decision.requiredVersion());
    }

    @Test
    void absentAndBlankClientVersionsAreRejected() throws Exception {
        enableWithPublished("2.0.0");

        PackVersionGate.Decision absent = gate.evaluate(null);
        PackVersionGate.Decision blank = gate.evaluate("   ");

        assertFalse(absent.allowed());
        assertFalse(blank.allowed());
        assertEquals(PackVersionGate.Status.CLIENT_VERSION_MISSING, absent.status());
        assertEquals(PackVersionGate.Status.CLIENT_VERSION_MISSING, blank.status());
    }

    @Test
    void enabledGateRejectsWhenNoVersionHasBeenPublished() throws Exception {
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        when(repository.findCurrentPublished()).thenReturn(Optional.empty());

        PackVersionGate.Decision decision = gate.evaluate("2.0.0");

        assertFalse(decision.allowed());
        assertEquals(PackVersionGate.Status.NO_PUBLISHED_VERSION, decision.status());
    }

    @Test
    void databaseFailureIsNotHiddenAsAnAllowedDecision() throws Exception {
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        when(repository.findCurrentPublished()).thenThrow(new SQLException("database unavailable"));

        assertThrows(SQLException.class, () -> gate.evaluate("2.0.0"));
    }

    @Test
    void repositoryReturningDraftViolatesTheReadBoundary() throws Exception {
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        when(repository.findCurrentPublished()).thenReturn(Optional.of(version("2.0.0", PackVersionStatus.DRAFT)));

        assertThrows(IllegalStateException.class, () -> gate.evaluate("2.0.0"));
    }

    @Test
    void randomizedDifferentVersionsNeverPassExactMatchGate() throws Exception {
        enableWithPublished("2.0.0");
        Random random = new Random(0x5041434bL);

        for (int i = 0; i < 200; i++) {
            String clientVersion = "1." + random.nextInt(1000) + "." + random.nextInt(1000);
            PackVersionGate.Decision decision = gate.evaluate(clientVersion);

            assertFalse(decision.allowed(), clientVersion);
            assertEquals(PackVersionGate.Status.VERSION_MISMATCH, decision.status(), clientVersion);
        }
    }

    private void enableWithPublished(String version) throws SQLException {
        when(config.isPackVersionGateEnabled()).thenReturn(true);
        when(repository.findCurrentPublished()).thenReturn(Optional.of(version(version, PackVersionStatus.PUBLISHED)));
    }

    private static PackVersion version(String version, PackVersionStatus status) {
        return new PackVersion(1L, version, status, "1.20.1", "forge", "47.4.20",
                null, 1_700_000_000L, status == PackVersionStatus.PUBLISHED ? 1_700_000_100L : null);
    }
}
