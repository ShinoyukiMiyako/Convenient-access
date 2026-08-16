package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackAdminServiceLifecycleTest {
    private static final long NOW = 1_776_571_200L;
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private InMemoryPackAdminRepository repository;
    private PackAdminService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPackAdminRepository();
        service = new PackAdminService(
                repository, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    @Test
    void createsEmptyDraftAndCopiesAnExistingVersionWithFreshIds() throws Exception {
        PackVersion published = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);
        PackEntry sourceEntry = repository.seedEntry(
                published.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);

        PackVersion empty = service.createDraft(PackDraftRequest.empty(
                "2.0.0", "1.21.1", "neoforge", "21.1.42", "new branch"));
        assertEquals(PackVersionStatus.DRAFT, empty.status());
        assertEquals("1.21.1", empty.minecraft());
        assertEquals("neoforge", empty.loaderKind());
        assertEquals(NOW, empty.createdAt());
        assertEquals(List.of(), service.listEntries(empty.id()));

        PackVersion copied = service.createDraft(PackDraftRequest.copy(published.id(), "1.0.1"));
        assertEquals(PackVersionStatus.DRAFT, copied.status());
        assertEquals(published.minecraft(), copied.minecraft());
        assertEquals(published.loaderVersion(), copied.loaderVersion());
        assertEquals(published.note(), copied.note());

        PackEntry copiedEntry = service.listEntries(copied.id()).get(0);
        assertNotEquals(sourceEntry.id(), copiedEntry.id());
        assertEquals(copied.id(), copiedEntry.versionId());
        assertEquals(sourceEntry.path(), copiedEntry.path());
        assertEquals(sourceEntry.sha1(), copiedEntry.sha1());
    }

    @Test
    void rejectsDuplicateVersionAndAmbiguousCopyInput() throws Exception {
        PackVersion source = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);

        PackAdminException duplicate = assertThrows(PackAdminException.class,
                () -> service.createDraft(PackDraftRequest.empty(
                        "1.0.0", "1.20.1", "forge", "47.4.20", null)));
        assertEquals(PackAdminException.Reason.CONFLICT, duplicate.reason());

        PackDraftRequest ambiguous = new PackDraftRequest(
                "1.0.1", "1.20.1", null, null, null, source.id());
        assertThrows(IllegalArgumentException.class, () -> service.createDraft(ambiguous));
        assertEquals(1, service.listVersions().size());
    }

    @Test
    void releasedEntriesAndMetadataAreImmutable() throws Exception {
        PackVersion published = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);
        PackEntry entry = repository.seedEntry(
                published.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersionUpdateRequest metadata = new PackVersionUpdateRequest(
                "1.0.1", "1.20.1", "forge", "47.4.20", "changed");

        assertImmutable(() -> service.updateDraft(published.id(), metadata));
        assertImmutable(() -> service.addEntry(published.id(), custom("mods/new.jar", SHA_B)));
        assertImmutable(() -> service.updateEntry(entry.id(), custom("mods/base.jar", SHA_B)));
        assertImmutable(() -> service.deleteEntry(entry.id()));

        assertEquals(SHA_A, service.listEntries(published.id()).get(0).sha1());
        assertEquals("1.0.0", service.listVersions().get(0).version());
    }

    @Test
    void publishRequiresDeletionConfirmationAndSwitchesCurrentVersionAtomically() throws Exception {
        PackVersion previous = repository.seedVersion("1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);
        repository.seedEntry(previous.id(), "mods/removed.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersion draft = repository.seedVersion("2.0.0", PackVersionStatus.DRAFT, 300L, null);
        repository.seedEntry(draft.id(), "mods/new.jar", PackEntryPolicy.MANAGED, SHA_B);
        String reviewedRevision = reviewedRevision(draft.id());

        PackAdminException confirmation = assertThrows(
                PackAdminException.class,
                () -> service.publish(draft.id(), false, reviewedRevision));
        assertEquals(PackAdminException.Reason.REMOVAL_CONFIRMATION_REQUIRED, confirmation.reason());
        assertEquals(1, repository.publishCalls);
        assertEquals(PackVersionStatus.PUBLISHED,
                repository.findVersionById(previous.id()).orElseThrow().status());

        PackVersion published = service.publish(draft.id(), true, reviewedRevision);
        assertEquals(PackVersionStatus.PUBLISHED, published.status());
        assertEquals(NOW, published.publishedAt());
        PackVersion archived = repository.findVersionById(previous.id()).orElseThrow();
        assertEquals(PackVersionStatus.ARCHIVED, archived.status());
        assertEquals(200L, archived.publishedAt());
        assertEquals(1L, service.listVersions().stream()
                .filter(version -> version.status() == PackVersionStatus.PUBLISHED)
                .count());
    }

    @Test
    void rollbackOnlyAcceptsArchivedReleaseAndPreservesItsOriginalTimestamp() throws Exception {
        PackVersion archived = repository.seedVersion("1.0.0", PackVersionStatus.ARCHIVED, 100L, 200L);
        repository.seedEntry(archived.id(), "mods/old.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersion current = repository.seedVersion("2.0.0", PackVersionStatus.PUBLISHED, 300L, 400L);
        repository.seedEntry(current.id(), "mods/current.jar", PackEntryPolicy.MANAGED, SHA_B);
        PackVersion draft = repository.seedVersion("3.0.0", PackVersionStatus.DRAFT, 500L, null);

        PackAdminException draftFailure = assertThrows(
                PackAdminException.class,
                () -> service.rollback(draft.id(), true, reviewedRevision(draft.id())));
        assertEquals(PackAdminException.Reason.CONFLICT, draftFailure.reason());

        String reviewedRevision = reviewedRevision(archived.id());
        PackAdminException confirmation = assertThrows(
                PackAdminException.class,
                () -> service.rollback(archived.id(), false, reviewedRevision));
        assertEquals(PackAdminException.Reason.REMOVAL_CONFIRMATION_REQUIRED, confirmation.reason());
        assertEquals(1, repository.rollbackCalls);

        PackVersion restored = service.rollback(archived.id(), true, reviewedRevision);
        assertEquals(PackVersionStatus.PUBLISHED, restored.status());
        assertEquals(200L, restored.publishedAt());
        assertEquals(PackVersionStatus.ARCHIVED,
                repository.findVersionById(current.id()).orElseThrow().status());
        assertEquals(400L, repository.findVersionById(current.id()).orElseThrow().publishedAt());
    }

    @Test
    void publishReturnsItsTransactionalSnapshotWhenImmediatelySuperseded() throws Exception {
        PackVersion first = repository.seedVersion("1.0.0", PackVersionStatus.DRAFT, 100L, null);
        PackVersion competing = repository.seedVersion("2.0.0", PackVersionStatus.DRAFT, 200L, null);
        String reviewedRevision = reviewedRevision(first.id());
        repository.afterNextSuccessfulRelease(() -> publishFromHook(competing.id()));

        PackVersion response = service.publish(first.id(), true, reviewedRevision);

        assertEquals(first.id(), response.id());
        assertEquals(PackVersionStatus.PUBLISHED, response.status());
        assertEquals(NOW, response.publishedAt());
        assertEquals(PackVersionStatus.ARCHIVED,
                repository.findVersionById(first.id()).orElseThrow().status());
        assertEquals(competing.id(), repository.findCurrentPublished().orElseThrow().id());
    }

    @Test
    void rollbackReturnsItsTransactionalSnapshotWhenImmediatelySuperseded() throws Exception {
        PackVersion target = repository.seedVersion(
                "1.0.0", PackVersionStatus.ARCHIVED, 100L, 200L);
        repository.seedVersion("2.0.0", PackVersionStatus.PUBLISHED, 300L, 400L);
        PackVersion competing = repository.seedVersion("3.0.0", PackVersionStatus.DRAFT, 500L, null);
        String reviewedRevision = reviewedRevision(target.id());
        repository.afterNextSuccessfulRelease(() -> publishFromHook(competing.id()));

        PackVersion response = service.rollback(target.id(), true, reviewedRevision);

        assertEquals(target.id(), response.id());
        assertEquals(PackVersionStatus.PUBLISHED, response.status());
        assertEquals(200L, response.publishedAt());
        assertEquals(PackVersionStatus.ARCHIVED,
                repository.findVersionById(target.id()).orElseThrow().status());
        assertEquals(competing.id(), repository.findCurrentPublished().orElseThrow().id());
    }

    @Test
    void staleRevisionRejectsTargetEntryAdditionsAndDeletions() throws Exception {
        PackVersion current = repository.seedVersion(
                "1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);
        repository.seedEntry(current.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersion draft = repository.seedVersion("2.0.0", PackVersionStatus.DRAFT, 300L, null);
        repository.seedEntry(draft.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);

        String beforeAddition = reviewedRevision(draft.id());
        PackEntry added = repository.seedEntry(
                draft.id(), "mods/unreviewed.jar", PackEntryPolicy.MANAGED, SHA_B);
        assertStale(() -> service.publish(draft.id(), true, beforeAddition));

        String beforeDeletion = reviewedRevision(draft.id());
        assertTrue(repository.deleteEntry(added.id()));
        assertStale(() -> service.publish(draft.id(), true, beforeDeletion));
        assertEquals(PackVersionStatus.DRAFT,
                repository.findVersionById(draft.id()).orElseThrow().status());
        assertEquals(PackVersionStatus.PUBLISHED,
                repository.findVersionById(current.id()).orElseThrow().status());
    }

    @Test
    void staleRevisionRejectsAChangedCurrentPublication() throws Exception {
        PackVersion current = repository.seedVersion(
                "1.0.0", PackVersionStatus.PUBLISHED, 100L, 200L);
        repository.seedEntry(current.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersion candidate = repository.seedVersion("2.0.0", PackVersionStatus.DRAFT, 300L, null);
        repository.seedEntry(candidate.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);
        PackVersion competing = repository.seedVersion("3.0.0", PackVersionStatus.DRAFT, 400L, null);
        repository.seedEntry(competing.id(), "mods/base.jar", PackEntryPolicy.MANAGED, SHA_A);

        String candidateRevision = reviewedRevision(candidate.id());
        service.publish(competing.id(), true, reviewedRevision(competing.id()));

        assertStale(() -> service.publish(candidate.id(), true, candidateRevision));
        assertEquals(PackVersionStatus.DRAFT,
                repository.findVersionById(candidate.id()).orElseThrow().status());
        assertEquals(PackVersionStatus.PUBLISHED,
                repository.findVersionById(competing.id()).orElseThrow().status());
    }

    @Test
    void releaseRejectsMalformedExpectedRevisionBeforeRepositoryMutation() {
        PackVersion draft = repository.seedVersion("1.0.0", PackVersionStatus.DRAFT, 100L, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.publish(draft.id(), true, "ABC"));
        assertEquals(0, repository.publishCalls);
    }

    private void assertImmutable(ThrowingOperation operation) {
        PackAdminException exception = assertThrows(PackAdminException.class, operation::run);
        assertEquals(PackAdminException.Reason.IMMUTABLE_VERSION, exception.reason());
    }

    private void assertStale(ThrowingOperation operation) {
        PackAdminException exception = assertThrows(PackAdminException.class, operation::run);
        assertEquals(PackAdminException.Reason.STALE_DIFF, exception.reason());
    }

    private String reviewedRevision(long versionId) throws SQLException {
        return service.diffFromPublished(versionId).revision();
    }

    private void publishFromHook(long versionId) {
        try {
            service.publish(versionId, true, reviewedRevision(versionId));
        } catch (SQLException exception) {
            throw new AssertionError("替代发布不应失败", exception);
        }
    }

    private static PackEntryRequest custom(String path, String sha1) {
        return new PackEntryRequest(
                path, PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, sha1, 100L,
                "https://cdn.example.test/file", null, null, null, null);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
