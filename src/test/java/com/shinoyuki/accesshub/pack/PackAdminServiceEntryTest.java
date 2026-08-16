package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackAdminServiceEntryTest {
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccc";

    private InMemoryPackAdminRepository repository;
    private PackAdminService service;
    private PackVersion draft;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPackAdminRepository();
        service = new PackAdminService(
                repository, Clock.fixed(Instant.ofEpochSecond(1_776_571_200L), ZoneOffset.UTC));
        draft = repository.seedVersion("2.0.0", PackVersionStatus.DRAFT, 100L, null);
    }

    @Test
    void addsUpdatesAndDeletesValidatedDraftEntries() throws Exception {
        PackEntry custom = service.addEntry(draft.id(), custom("mods/wok.jar", SHA_A));
        assertEquals(PackEntryKind.CUSTOM, custom.kind());
        assertEquals(List.of(custom), service.listEntries(draft.id()));

        PackEntry platform = service.addEntry(draft.id(), platform("mods/sodium.jar", SHA_B));
        assertEquals("modrinth", platform.platform());
        assertEquals("AANobbMI", platform.projectId());

        PackEntry updated = service.updateEntry(custom.id(), new PackEntryRequest(
                "config/wok.toml", PackEntryKind.CUSTOM, PackEntryPolicy.SEEDED, SHA_C, 0L,
                "https://cdn.example.test/config", null, null, null, null));
        assertEquals("config/wok.toml", updated.path());
        assertEquals(PackEntryPolicy.SEEDED, updated.policy());
        assertEquals(0L, updated.size());

        service.deleteEntry(platform.id());
        assertEquals(List.of(updated), service.listEntries(draft.id()));
    }

    @Test
    void platformEntriesFreezeRuntimeCoordinatesButAllowNameAndNoteChanges() throws Exception {
        service.addEntry(draft.id(), platform("mods/sodium.jar", SHA_B));

        PackVersion renamed = service.updateDraft(draft.id(), new PackVersionUpdateRequest(
                "2.0.1", draft.minecraft(), draft.loaderKind(), draft.loaderVersion(), "renamed"));
        assertEquals("2.0.1", renamed.version());
        assertEquals("renamed", renamed.note());

        PackAdminException conflict = assertThrows(PackAdminException.class,
                () -> service.updateDraft(draft.id(), new PackVersionUpdateRequest(
                        "2.0.2", "1.21.1", draft.loaderKind(), draft.loaderVersion(), "changed")));
        assertEquals(PackAdminException.Reason.CONFLICT, conflict.reason());
        PackVersion unchanged = repository.findVersionById(draft.id()).orElseThrow();
        assertEquals("2.0.1", unchanged.version());
        assertEquals(draft.minecraft(), unchanged.minecraft());
        assertEquals("renamed", unchanged.note());
    }

    @Test
    void rejectsDuplicatePathsBeforeWriting() throws Exception {
        service.addEntry(draft.id(), custom("mods/wok.jar", SHA_A));

        for (String duplicate : List.of("mods/wok.jar", "mods/WOK.jar")) {
            PackAdminException exception = assertThrows(PackAdminException.class,
                    () -> service.addEntry(draft.id(), custom(duplicate, SHA_B)));
            assertEquals(PackAdminException.Reason.CONFLICT, exception.reason());
        }
        assertEquals(1, service.listEntries(draft.id()).size());
    }

    @Test
    void rejectsUnsafeOrInternallyInconsistentEntries() {
        PackEntryRequest[] invalid = {
                custom("../outside.jar", SHA_A),
                custom("mods/CON.jar", SHA_A),
                custom("mods/a.jar", "ABCDEF"),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, SHA_A, -1L,
                        "https://cdn.example.test/a", null, null, null, null),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, SHA_A, 1L,
                        "file:///tmp/a.jar", null, null, null, null),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, SHA_A, 1L,
                        "https://cdn.example.test/a", "modrinth", null, null, null),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.PLATFORM, PackEntryPolicy.MANAGED, SHA_A, 1L,
                        "https://cdn.example.test/a", "modrinth", null, "Project", "version"),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.PLATFORM, PackEntryPolicy.MANAGED, SHA_A, 1L,
                        "https://cdn.example.test/a", null, "project", "Project", "version"),
                new PackEntryRequest(
                        "mods/a.jar", null, PackEntryPolicy.MANAGED, SHA_A, 1L,
                        "https://cdn.example.test/a", null, null, null, null),
                new PackEntryRequest(
                        "mods/a.jar", PackEntryKind.CUSTOM, null, SHA_A, 1L,
                        "https://cdn.example.test/a", null, null, null, null)
        };

        for (PackEntryRequest request : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.addEntry(draft.id(), request));
        }
        assertEquals(0, repository.findEntriesByVersionId(draft.id()).size());
    }

    @Test
    void computesStablePathBasedDiffIgnoringDatabaseIdentity() throws Exception {
        PackVersion published = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 10L, 20L);
        PackEntryInput same = input(
                "mods/same.jar", PackEntryPolicy.MANAGED, SHA_A, 10L, "https://cdn.example/same");
        repository.seedEntry(published.id(), same);
        repository.seedEntry(draft.id(), same);
        repository.seedEntry(published.id(), input(
                "mods/removed.jar", PackEntryPolicy.MANAGED, SHA_B, 20L,
                "https://cdn.example/removed"));
        repository.seedEntry(published.id(), input(
                "mods/changed.jar", PackEntryPolicy.MANAGED, SHA_A, 30L,
                "https://cdn.example/old"));
        repository.seedEntry(draft.id(), input(
                "mods/changed.jar", PackEntryPolicy.SEEDED, SHA_C, 31L,
                "https://cdn.example/new"));
        repository.seedEntry(draft.id(), input(
                "mods/added.jar", PackEntryPolicy.OPTIONAL, SHA_B, 40L,
                "https://cdn.example/added"));

        PackVersionDiff diff = service.diffFromPublished(draft.id());

        assertTrue(diff.revision().matches("[0-9a-f]{64}"));
        assertEquals(diff.revision(), service.diffFromPublished(draft.id()).revision());
        assertEquals(List.of("mods/added.jar"), diff.added().stream().map(PackEntry::path).toList());
        assertEquals(List.of("mods/removed.jar"), diff.removed().stream().map(PackEntry::path).toList());
        assertEquals(1, diff.changed().size());
        PackVersionDiff.EntryChange changed = diff.changed().get(0);
        assertEquals("mods/changed.jar", changed.before().path());
        assertEquals("mods/changed.jar", changed.after().path());
        assertEquals(List.of("policy", "sha1", "size", "downloadUrl"), changed.changedFields());
        assertFalse(diff.changed().stream()
                .anyMatch(change -> change.after().path().equals("mods/same.jar")));
    }

    @Test
    void classifiesRandomizedLargeDiffWithoutLosingOrDuplicatingPaths() throws Exception {
        PackVersion published = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 10L, 20L);
        Random random = new Random(0x51ffL);
        List<String> expectedAdded = new ArrayList<>();
        List<String> expectedChanged = new ArrayList<>();
        List<String> expectedRemoved = new ArrayList<>();

        for (int i = 0; i < 120; i++) {
            String path = "mods/generated-" + i + "-"
                    + Long.toUnsignedString(random.nextLong(), 36) + ".jar";
            PackEntryInput original = input(
                    path, PackEntryPolicy.MANAGED, SHA_A, i, "https://cdn.example/original/" + i);
            switch (i % 4) {
                case 0 -> {
                    repository.seedEntry(published.id(), original);
                    repository.seedEntry(draft.id(), original);
                }
                case 1 -> {
                    repository.seedEntry(published.id(), original);
                    repository.seedEntry(draft.id(), input(
                            path, PackEntryPolicy.MANAGED, SHA_B, i,
                            "https://cdn.example/changed/" + i));
                    expectedChanged.add(path);
                }
                case 2 -> {
                    repository.seedEntry(published.id(), original);
                    expectedRemoved.add(path);
                }
                case 3 -> {
                    repository.seedEntry(draft.id(), original);
                    expectedAdded.add(path);
                }
                default -> throw new AssertionError("unreachable");
            }
        }

        PackVersionDiff diff = service.diffFromPublished(draft.id());
        expectedAdded.sort(String::compareTo);
        expectedChanged.sort(String::compareTo);
        expectedRemoved.sort(String::compareTo);

        assertEquals(expectedAdded, diff.added().stream().map(PackEntry::path).toList());
        assertEquals(expectedChanged,
                diff.changed().stream().map(change -> change.after().path()).toList());
        assertEquals(expectedRemoved, diff.removed().stream().map(PackEntry::path).toList());
        assertEquals(120, diff.added().size() + diff.changed().size()
                + diff.removed().size() + 30);
    }

    @Test
    void blocksPublicationWhenPersistedEntryViolatesSafetyBoundary() {
        repository.seedEntry(draft.id(), input(
                "mods/../../escape.jar", PackEntryPolicy.MANAGED, SHA_A, 1L,
                "https://cdn.example/escape"));

        assertThrows(IllegalStateException.class,
                () -> service.publish(draft.id(), true, "a".repeat(64)));
        assertEquals(1, repository.publishCalls);
        assertEquals(PackVersionStatus.DRAFT,
                repository.findVersionById(draft.id()).orElseThrow().status());
    }

    @Test
    void blocksPublicationWhenPersistedPathsCollideOnWindows() {
        repository.seedEntry(draft.id(), input(
                "mods/Wok.jar", PackEntryPolicy.MANAGED, SHA_A, 1L,
                "https://cdn.example/first"));
        repository.seedEntry(draft.id(), input(
                "mods/wok.jar", PackEntryPolicy.MANAGED, SHA_B, 1L,
                "https://cdn.example/second"));

        assertThrows(IllegalStateException.class,
                () -> service.publish(draft.id(), true, "a".repeat(64)));
        assertEquals(1, repository.publishCalls);
    }

    @Test
    void rejectsCaseOnlyRenameAcrossOneVersionSwitch() {
        PackVersion published = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 10L, 20L);
        repository.seedEntry(published.id(), input(
                "mods/Wok.jar", PackEntryPolicy.MANAGED, SHA_A, 1L,
                "https://cdn.example/original"));
        repository.seedEntry(draft.id(), input(
                "mods/wok.jar", PackEntryPolicy.MANAGED, SHA_A, 1L,
                "https://cdn.example/original"));

        PackAdminException exception = assertThrows(
                PackAdminException.class, () -> service.diffFromPublished(draft.id()));
        assertEquals(PackAdminException.Reason.CONFLICT, exception.reason());
        assertEquals(0, repository.publishCalls);
    }

    private static PackEntryRequest custom(String path, String sha1) {
        return new PackEntryRequest(
                path, PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, sha1, 100L,
                "https://cdn.example.test/file", null, null, null, null);
    }

    private static PackEntryRequest platform(String path, String sha1) {
        return new PackEntryRequest(
                path, PackEntryKind.PLATFORM, PackEntryPolicy.MANAGED, sha1, 100L,
                "https://cdn.modrinth.com/data/AANobbMI/version/file.jar",
                "modrinth", "AANobbMI", "Sodium", "version-id");
    }

    private static PackEntryInput input(String path, PackEntryPolicy policy, String sha1,
                                        long size, String url) {
        return new PackEntryInput(
                path, PackEntryKind.CUSTOM, policy, sha1, size, url,
                null, null, null, null);
    }
}
