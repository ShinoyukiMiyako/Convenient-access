package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PackDiffRevisionTest {
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @Test
    void revisionIsStableAcrossEntryOrderingAndDatabaseEntryIdentity() {
        PackVersion published = version(1, "1.0.0", PackVersionStatus.PUBLISHED, 100, 200L);
        PackVersion target = version(2, "2.0.0", PackVersionStatus.DRAFT, 300, null);
        PackEntry publishedA = entry(10, published.id(), "mods/a.jar", SHA_A);
        PackEntry publishedB = entry(11, published.id(), "mods/b.jar", SHA_B);
        PackEntry targetA = entry(20, target.id(), "mods/a.jar", SHA_A);
        PackEntry targetB = entry(21, target.id(), "mods/b.jar", SHA_B);

        String first = PackReleasePlanner.compare(
                published, target, List.of(publishedB, publishedA), List.of(targetB, targetA)).revision();
        String reorderedAndRecreated = PackReleasePlanner.compare(
                published, target,
                List.of(entry(91, published.id(), "mods/a.jar", SHA_A),
                        entry(90, published.id(), "mods/b.jar", SHA_B)),
                List.of(entry(81, target.id(), "mods/b.jar", SHA_B),
                        entry(80, target.id(), "mods/a.jar", SHA_A))).revision();

        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, reorderedAndRecreated);
    }

    @Test
    void revisionChangesWithPublishedBaselineTargetMetadataOrEntryContent() {
        PackVersion published = version(1, "1.0.0", PackVersionStatus.PUBLISHED, 100, 200L);
        PackVersion target = version(2, "2.0.0", PackVersionStatus.DRAFT, 300, null);
        PackEntry original = entry(20, target.id(), "mods/a.jar", SHA_A);
        String baseline = PackReleasePlanner.compare(
                published, target, List.of(), List.of(original)).revision();

        PackVersion anotherPublished = version(3, "1.1.0", PackVersionStatus.PUBLISHED, 150, 250L);
        PackVersion renamedTarget = new PackVersion(
                target.id(), target.version(), target.status(), target.minecraft(), target.loaderKind(),
                target.loaderVersion(), "different note", target.createdAt(), target.publishedAt());
        PackEntry changed = entry(20, target.id(), "mods/a.jar", SHA_B);

        assertNotEquals(baseline, PackReleasePlanner.compare(
                anotherPublished, target, List.of(), List.of(original)).revision());
        assertNotEquals(baseline, PackReleasePlanner.compare(
                published, renamedTarget, List.of(), List.of(original)).revision());
        assertNotEquals(baseline, PackReleasePlanner.compare(
                published, target, List.of(), List.of(changed)).revision());
    }

    @Test
    void expectedRevisionRequiresExactLowercaseSha256WireForm() {
        String valid = "a".repeat(64);

        assertEquals(valid, PackDiffRevision.requireValidExpected(valid));
        for (String invalid : List.of("", "a".repeat(63), "A".repeat(64), "sha256:" + valid)) {
            assertThrows(IllegalArgumentException.class,
                    () -> PackDiffRevision.requireValidExpected(invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PackDiffRevision.requireValidExpected(null));
    }

    private static PackVersion version(long id, String name, PackVersionStatus status,
                                       long createdAt, Long publishedAt) {
        return new PackVersion(
                id, name, status, "1.20.1", "forge", "47.4.20", "note",
                createdAt, publishedAt);
    }

    private static PackEntry entry(long id, long versionId, String path, String sha1) {
        return new PackEntry(
                id, versionId, path, PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED,
                sha1, 10, "https://cdn.example.test/" + Math.abs(path.hashCode()),
                null, null, null, null);
    }
}
