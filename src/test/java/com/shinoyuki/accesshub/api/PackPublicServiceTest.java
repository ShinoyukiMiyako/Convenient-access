package com.shinoyuki.accesshub.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.shinoyuki.accesshub.pack.PackEntry;
import com.shinoyuki.accesshub.pack.PackEntryKind;
import com.shinoyuki.accesshub.pack.PackEntryPolicy;
import com.shinoyuki.accesshub.pack.PackManifestRepository;
import com.shinoyuki.accesshub.pack.PackVersion;
import com.shinoyuki.accesshub.pack.PackVersionStatus;

class PackPublicServiceTest {

    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);

    private final PackManifestRepository repository = Mockito.mock(PackManifestRepository.class);
    private final PackPublicService service = new PackPublicService(repository);

    @Test
    void latestMapsThePublishedPointerExactly() throws Exception {
        PackVersion published = version(7L, "2.0.0", PackVersionStatus.PUBLISHED,
                "新周目：矿洞维度重做", 1_755_432_000L);
        when(repository.findCurrentPublished()).thenReturn(Optional.of(published));

        PackLatestResponse response = service.getLatest().orElseThrow();

        assertEquals("wok", response.packId());
        assertEquals("2.0.0", response.version());
        assertEquals("https://api.mcwok.cn/api/v1/pack/manifest/2.0.0", response.manifestUrl());
        assertEquals("2025-08-17T12:00:00Z", response.releasedAt());
        assertEquals("新周目：矿洞维度重做", response.note());
        assertEquals("0.1.0", response.minLauncherVersion());
    }

    @Test
    void latestReturnsEmptyWhenNothingHasBeenPublished() throws Exception {
        when(repository.findCurrentPublished()).thenReturn(Optional.empty());

        assertFalse(service.getLatest().isPresent());
    }

    @Test
    void manifestMapsOnlyWireFieldsAndSortsByPath() throws Exception {
        PackVersion archived = version(4L, "1.9.0", PackVersionStatus.ARCHIVED, null, 1_750_000_000L);
        when(repository.findReleasedByVersion("1.9.0")).thenReturn(Optional.of(archived));
        when(repository.findEntriesByVersionId(4L)).thenReturn(List.of(
                entry(11L, 4L, "options.txt", PackEntryPolicy.SEEDED, SHA1_B, 2048L,
                        "https://files.example.test/options.txt"),
                entry(10L, 4L, "mods/wok-core.jar", PackEntryPolicy.MANAGED, SHA1_A, 8421376L,
                        "https://files.example.test/wok-core.jar")));

        PackManifestResponse response = service.getManifest("1.9.0").orElseThrow();

        assertEquals(1, response.schema());
        assertEquals("wok", response.packId());
        assertEquals("1.9.0", response.version());
        assertEquals("1.20.1", response.minecraft());
        assertEquals("forge", response.loader().kind());
        assertEquals("47.4.20", response.loader().version());
        assertEquals(List.of("mods/wok-core.jar", "options.txt"),
                response.files().stream().map(PackManifestResponse.FileEntry::path).toList());
        assertEquals("managed", response.files().get(0).policy());
        assertEquals(List.of("https://files.example.test/wok-core.jar"), response.files().get(0).urls());
        assertEquals("seeded", response.files().get(1).policy());
    }

    @Test
    void invalidVersionNeverReachesTheDatabase() throws Exception {
        assertFalse(service.getManifest("../draft").isPresent());
        verify(repository, never()).findReleasedByVersion("../draft");
    }

    @Test
    void unsafePersistedPathCannotCrossThePublicBoundary() throws Exception {
        PackVersion published = version(7L, "2.0.0", PackVersionStatus.PUBLISHED, null, 1_755_432_000L);
        when(repository.findReleasedByVersion("2.0.0")).thenReturn(Optional.of(published));
        when(repository.findEntriesByVersionId(7L)).thenReturn(List.of(
                entry(1L, 7L, "../saves/world.dat", PackEntryPolicy.MANAGED, SHA1_A, 10L,
                        "https://files.example.test/world.dat")));

        assertThrows(IllegalArgumentException.class, () -> service.getManifest("2.0.0"));
    }

    @Test
    void malformedPersistedSha1CannotCrossThePublicBoundary() throws Exception {
        PackVersion published = version(7L, "2.0.0", PackVersionStatus.PUBLISHED, null, 1_755_432_000L);
        when(repository.findReleasedByVersion("2.0.0")).thenReturn(Optional.of(published));
        when(repository.findEntriesByVersionId(7L)).thenReturn(List.of(
                entry(1L, 7L, "mods/wok-core.jar", PackEntryPolicy.MANAGED, "ABC123", 10L,
                        "https://files.example.test/wok-core.jar")));

        assertThrows(IllegalArgumentException.class, () -> service.getManifest("2.0.0"));
    }

    private static PackVersion version(long id, String version, PackVersionStatus status,
                                       String note, long publishedAt) {
        return new PackVersion(id, version, status, "1.20.1", "forge", "47.4.20",
                note, 1_700_000_000L, publishedAt);
    }

    private static PackEntry entry(long id, long versionId, String path, PackEntryPolicy policy,
                                   String sha1, long size, String url) {
        return new PackEntry(id, versionId, path, PackEntryKind.CUSTOM, policy, sha1, size, url,
                null, null, null, null);
    }
}
