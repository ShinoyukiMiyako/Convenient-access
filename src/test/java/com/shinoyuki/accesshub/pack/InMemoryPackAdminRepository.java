package com.shinoyuki.accesshub.pack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class InMemoryPackAdminRepository implements PackAdminRepository {
    private final Map<Long, PackVersion> versions = new LinkedHashMap<>();
    private final Map<Long, PackEntry> entries = new LinkedHashMap<>();
    private long nextVersionId = 1;
    private long nextEntryId = 1;
    private Runnable afterReleaseHook;
    int publishCalls;
    int rollbackCalls;

    void afterNextSuccessfulRelease(Runnable hook) {
        afterReleaseHook = Objects.requireNonNull(hook, "hook");
    }

    PackVersion seedVersion(String version, PackVersionStatus status, long createdAt, Long publishedAt) {
        long id = nextVersionId++;
        PackVersion seeded = new PackVersion(
                id, version, status, "1.20.1", "forge", "47.4.20", "note-" + version,
                createdAt, publishedAt);
        versions.put(id, seeded);
        return seeded;
    }

    PackEntry seedEntry(long versionId, String path, PackEntryPolicy policy, String sha1) {
        return seedEntry(versionId, path, PackEntryKind.CUSTOM, policy, sha1, null, null, null, null);
    }

    PackEntry seedEntry(long versionId, String path, PackEntryKind kind, PackEntryPolicy policy,
                        String sha1, String platform, String projectId, String projectName,
                        String externalVersionId) {
        return seedEntry(versionId, new PackEntryInput(
                path, kind, policy, sha1, 100L, "https://cdn.example.test/" + nextEntryId,
                platform, projectId, projectName, externalVersionId));
    }

    PackEntry seedEntry(long versionId, PackEntryInput input) {
        long id = nextEntryId++;
        PackEntry seeded = entryOf(id, versionId, input);
        entries.put(id, seeded);
        return seeded;
    }

    @Override
    public Optional<PackVersion> findCurrentPublished() {
        return versions.values().stream()
                .filter(version -> version.status() == PackVersionStatus.PUBLISHED)
                .findFirst();
    }

    @Override
    public Optional<PackVersion> findReleasedByVersion(String version) {
        return versions.values().stream()
                .filter(candidate -> candidate.version().equals(version))
                .filter(candidate -> candidate.status() != PackVersionStatus.DRAFT)
                .findFirst();
    }

    @Override
    public Optional<PackVersion> findVersionById(long id) {
        return Optional.ofNullable(versions.get(id));
    }

    @Override
    public Optional<PackEntry> findEntryById(long id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<PackVersion> findAllVersions() {
        return List.copyOf(versions.values());
    }

    @Override
    public List<PackEntry> findEntriesByVersionId(long versionId) {
        return entries.values().stream()
                .filter(entry -> entry.versionId() == versionId)
                .toList();
    }

    @Override
    public long createDraft(String version, String minecraft, String loaderKind, String loaderVersion,
                            String note, long createdAt) {
        long id = nextVersionId++;
        versions.put(id, new PackVersion(
                id, version, PackVersionStatus.DRAFT, minecraft, loaderKind, loaderVersion,
                note, createdAt, null));
        return id;
    }

    @Override
    public long copyAsDraft(long sourceId, String newVersion, long createdAt) throws SQLException {
        PackVersion source = versions.get(sourceId);
        if (source == null) {
            throw new SQLException("source missing");
        }
        long id = createDraft(newVersion, source.minecraft(), source.loaderKind(), source.loaderVersion(),
                source.note(), createdAt);
        for (PackEntry sourceEntry : new ArrayList<>(entries.values())) {
            if (sourceEntry.versionId() == sourceId) {
                addEntry(id, inputOf(sourceEntry));
            }
        }
        return id;
    }

    @Override
    public boolean updateDraft(long id, String version, String minecraft, String loaderKind,
                               String loaderVersion, String note) {
        PackVersion current = versions.get(id);
        if (current == null || current.status() != PackVersionStatus.DRAFT) {
            return false;
        }
        boolean hasPlatformEntries = entries.values().stream()
                .anyMatch(entry -> entry.versionId() == id && entry.kind() == PackEntryKind.PLATFORM);
        if (hasPlatformEntries && (!Objects.equals(current.minecraft(), minecraft)
                || !Objects.equals(current.loaderKind(), loaderKind)
                || !Objects.equals(current.loaderVersion(), loaderVersion))) {
            return false;
        }
        versions.put(id, new PackVersion(
                id, version, current.status(), minecraft, loaderKind, loaderVersion,
                note, current.createdAt(), null));
        return true;
    }

    @Override
    public long addEntry(long versionId, PackEntryInput input) {
        PackVersion version = versions.get(versionId);
        if (version == null || version.status() != PackVersionStatus.DRAFT) {
            return 0;
        }
        long id = nextEntryId++;
        entries.put(id, entryOf(id, versionId, input));
        return id;
    }

    @Override
    public boolean updateEntry(long entryId, PackEntryInput input) {
        PackEntry current = entries.get(entryId);
        if (current == null || versions.get(current.versionId()).status() != PackVersionStatus.DRAFT) {
            return false;
        }
        entries.put(entryId, entryOf(entryId, current.versionId(), input));
        return true;
    }

    @Override
    public boolean deleteEntry(long entryId) {
        PackEntry current = entries.get(entryId);
        if (current == null || versions.get(current.versionId()).status() != PackVersionStatus.DRAFT) {
            return false;
        }
        entries.remove(entryId);
        return true;
    }

    @Override
    public synchronized Optional<PackVersion> publishVersion(
            long id, long publishedAt, String expectedDiffRevision, boolean confirmRemovals) {
        publishCalls++;
        PackVersion target = versions.get(id);
        if (target == null || target.status() != PackVersionStatus.DRAFT) {
            return Optional.empty();
        }
        requireReviewed(target, expectedDiffRevision, confirmRemovals);
        archiveCurrentPublished();
        PackVersion released = withStatus(target, PackVersionStatus.PUBLISHED, publishedAt);
        versions.put(id, released);
        runAfterReleaseHook();
        return Optional.of(released);
    }

    @Override
    public synchronized Optional<PackVersion> rollbackToVersion(
            long id, String expectedDiffRevision, boolean confirmRemovals) {
        rollbackCalls++;
        PackVersion target = versions.get(id);
        if (target == null || target.status() != PackVersionStatus.ARCHIVED
                || target.publishedAt() == null) {
            return Optional.empty();
        }
        requireReviewed(target, expectedDiffRevision, confirmRemovals);
        archiveCurrentPublished();
        PackVersion released = withStatus(target, PackVersionStatus.PUBLISHED, target.publishedAt());
        versions.put(id, released);
        runAfterReleaseHook();
        return Optional.of(released);
    }

    private void runAfterReleaseHook() {
        Runnable hook = afterReleaseHook;
        afterReleaseHook = null;
        if (hook != null) {
            hook.run();
        }
    }

    private void requireReviewed(PackVersion target, String expectedDiffRevision,
                                 boolean confirmRemovals) {
        Optional<PackVersion> published = findCurrentPublished();
        PackVersionDiff actual = PackReleasePlanner.compare(
                published.orElse(null), target,
                published.map(version -> findEntriesByVersionId(version.id())).orElseGet(List::of),
                findEntriesByVersionId(target.id()));
        PackReleasePlanner.requireReviewed(actual, expectedDiffRevision, confirmRemovals);
    }

    private void archiveCurrentPublished() {
        findCurrentPublished().ifPresent(current -> versions.put(
                current.id(), withStatus(current, PackVersionStatus.ARCHIVED, current.publishedAt())));
    }

    private static PackVersion withStatus(PackVersion version, PackVersionStatus status, Long publishedAt) {
        return new PackVersion(
                version.id(), version.version(), status, version.minecraft(), version.loaderKind(),
                version.loaderVersion(), version.note(), version.createdAt(), publishedAt);
    }

    private static PackEntryInput inputOf(PackEntry entry) {
        return new PackEntryInput(
                entry.path(), entry.kind(), entry.policy(), entry.sha1(), entry.size(), entry.downloadUrl(),
                entry.platform(), entry.projectId(), entry.projectName(), entry.externalVersionId());
    }

    private static PackEntry entryOf(long id, long versionId, PackEntryInput input) {
        return new PackEntry(
                id, versionId, input.path(), input.kind(), input.policy(), input.sha1(), input.size(),
                input.downloadUrl(), input.platform(), input.projectId(), input.projectName(),
                input.externalVersionId());
    }
}
