package com.shinoyuki.accesshub.pack;

import static com.shinoyuki.accesshub.pack.PackAdminException.Reason.CONFLICT;
import static com.shinoyuki.accesshub.pack.PackAdminException.Reason.IMMUTABLE_VERSION;
import static com.shinoyuki.accesshub.pack.PackAdminException.Reason.NOT_FOUND;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Business rules for editing, publishing and rolling back immutable pack releases. */
public final class PackAdminService {
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("modrinth", "curseforge");

    private final PackAdminRepository repository;
    private final Clock clock;

    public PackAdminService(PackAdminRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public PackAdminService(PackAdminRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<PackVersion> listVersions() throws SQLException {
        List<PackVersion> versions = new ArrayList<>(repository.findAllVersions());
        long publishedCount = versions.stream()
                .filter(version -> version.status() == PackVersionStatus.PUBLISHED)
                .count();
        if (publishedCount > 1) {
            throw new IllegalStateException("数据层返回了多个当前发布版本");
        }
        versions.sort(Comparator.comparingLong(PackVersion::createdAt)
                .thenComparingLong(PackVersion::id)
                .reversed());
        return List.copyOf(versions);
    }

    public PackVersion createDraft(PackDraftRequest request) throws SQLException {
        requireRequest(request);
        String version = PackVersionValidator.requireValidVersion(request.version());
        requireVersionAvailable(version, null);

        long id;
        if (request.copyFromVersionId() != null) {
            requireCopyOnlyFields(request);
            long sourceId = requirePositiveId(request.copyFromVersionId(), "源版本 ID");
            requireVersion(sourceId);
            id = repository.copyAsDraft(sourceId, version, clock.instant().getEpochSecond());
        } else {
            id = repository.createDraft(
                    version,
                    PackVersionValidator.requireValidMinecraft(request.minecraft()),
                    PackVersionValidator.requireValidLoaderKind(request.loaderKind()),
                    PackVersionValidator.requireValidLoaderVersion(request.loaderVersion()),
                    PackVersionValidator.requireValidNote(request.note()),
                    clock.instant().getEpochSecond());
        }
        return requireCreatedVersion(id);
    }

    public PackVersion updateDraft(long versionId, PackVersionUpdateRequest request) throws SQLException {
        requireRequest(request);
        PackVersion existing = requireVersion(requirePositiveId(versionId, "版本 ID"));
        requireDraft(existing);

        String version = PackVersionValidator.requireValidVersion(request.version());
        requireVersionAvailable(version, existing.id());
        boolean updated = repository.updateDraft(
                existing.id(),
                version,
                PackVersionValidator.requireValidMinecraft(request.minecraft()),
                PackVersionValidator.requireValidLoaderKind(request.loaderKind()),
                PackVersionValidator.requireValidLoaderVersion(request.loaderVersion()),
                PackVersionValidator.requireValidNote(request.note()));
        if (!updated) {
            throw conflict("草稿版本已被其他操作修改或删除");
        }
        return requireVersion(existing.id());
    }

    public List<PackEntry> listEntries(long versionId) throws SQLException {
        PackVersion version = requireVersion(requirePositiveId(versionId, "版本 ID"));
        List<PackEntry> entries = new ArrayList<>(repository.findEntriesByVersionId(version.id()));
        entries.sort(Comparator.comparing(PackEntry::path));
        return List.copyOf(entries);
    }

    public PackEntry addEntry(long versionId, PackEntryRequest request) throws SQLException {
        PackVersion version = requireVersion(requirePositiveId(versionId, "版本 ID"));
        requireDraft(version);
        PackEntryInput input = validateEntry(request);
        requirePathAvailable(version.id(), input.path(), null);

        long entryId = repository.addEntry(version.id(), input);
        return requireCreatedEntry(entryId);
    }

    public PackEntry updateEntry(long entryId, PackEntryRequest request) throws SQLException {
        PackEntry existing = requireEntry(requirePositiveId(entryId, "条目 ID"));
        PackVersion version = requireVersion(existing.versionId());
        requireDraft(version);
        PackEntryInput input = validateEntry(request);
        requirePathAvailable(version.id(), input.path(), existing.id());

        if (!repository.updateEntry(existing.id(), input)) {
            throw conflict("整合包条目已被其他操作修改或删除");
        }
        return requireEntry(existing.id());
    }

    public void deleteEntry(long entryId) throws SQLException {
        PackEntry existing = requireEntry(requirePositiveId(entryId, "条目 ID"));
        requireDraft(requireVersion(existing.versionId()));
        if (!repository.deleteEntry(existing.id())) {
            throw conflict("整合包条目已被其他操作修改或删除");
        }
    }

    public PackVersionDiff diffFromPublished(long versionId) throws SQLException {
        PackVersion target = requireVersion(requirePositiveId(versionId, "版本 ID"));
        Optional<PackVersion> published = repository.findCurrentPublished();
        List<PackEntry> publishedEntries = published.isPresent()
                ? repository.findEntriesByVersionId(published.get().id())
                : List.of();
        return PackReleasePlanner.compare(
                published.orElse(null), target, publishedEntries,
                repository.findEntriesByVersionId(target.id()));
    }

    public PackVersion publish(long versionId, boolean confirmRemovals,
                               String expectedDiffRevision) throws SQLException {
        String expectedRevision = PackDiffRevision.requireValidExpected(expectedDiffRevision);
        PackVersion target = requireVersion(requirePositiveId(versionId, "版本 ID"));
        requireDraft(target);

        return repository.publishVersion(target.id(), clock.instant().getEpochSecond(),
                        expectedRevision, confirmRemovals)
                .orElseThrow(() -> conflict("草稿版本已被其他操作修改，发布未生效"));
    }

    public PackVersion rollback(long versionId, boolean confirmRemovals,
                                String expectedDiffRevision) throws SQLException {
        String expectedRevision = PackDiffRevision.requireValidExpected(expectedDiffRevision);
        PackVersion target = requireVersion(requirePositiveId(versionId, "版本 ID"));
        if (target.status() != PackVersionStatus.ARCHIVED || target.publishedAt() == null) {
            throw conflict("只能回滚到曾经发布过的归档版本");
        }

        return repository.rollbackToVersion(target.id(), expectedRevision, confirmRemovals)
                .orElseThrow(() -> conflict("归档版本已被其他操作修改，回滚未生效"));
    }

    private PackEntryInput validateEntry(PackEntryRequest request) {
        requireRequest(request);
        String path = PackPathValidator.requireValid(request.path());
        PackEntryKind kind = requireField(request.kind(), "kind");
        PackEntryPolicy policy = requireField(request.policy(), "policy");
        String sha1 = PackEntryValidator.requireValidSha1(request.sha1());
        long size = PackEntryValidator.requireValidSize(request.size());
        String downloadUrl = PackEntryValidator.requireValidDownloadUrl(request.downloadUrl());

        if (kind == PackEntryKind.PLATFORM) {
            if (request.platform() == null || !SUPPORTED_PLATFORMS.contains(request.platform())) {
                throw new IllegalArgumentException("平台条目仅支持 modrinth 或 curseforge");
            }
            requireMetadata(request.projectId(), "平台项目 ID");
            requireMetadata(request.projectName(), "平台项目名");
            requireMetadata(request.externalVersionId(), "平台版本 ID");
        } else {
            requireAbsent(request.platform(), "platform");
            requireAbsent(request.projectId(), "projectId");
            requireAbsent(request.projectName(), "projectName");
            requireAbsent(request.externalVersionId(), "externalVersionId");
        }

        return new PackEntryInput(path, kind, policy, sha1, size, downloadUrl,
                request.platform(), request.projectId(), request.projectName(), request.externalVersionId());
    }

    private void requirePathAvailable(long versionId, String path, Long ignoredEntryId) throws SQLException {
        for (PackEntry entry : repository.findEntriesByVersionId(versionId)) {
            if (entry.path().equalsIgnoreCase(path) && !Objects.equals(entry.id(), ignoredEntryId)) {
                throw conflict("该版本已存在相同或大小写碰撞路径: " + path);
            }
        }
    }

    private void requireVersionAvailable(String version, Long ignoredVersionId) throws SQLException {
        for (PackVersion existing : repository.findAllVersions()) {
            if (existing.version().equals(version) && !Objects.equals(existing.id(), ignoredVersionId)) {
                throw conflict("整合包版本号已存在: " + version);
            }
        }
    }

    private PackVersion requireVersion(long versionId) throws SQLException {
        return repository.findVersionById(versionId)
                .orElseThrow(() -> new PackAdminException(NOT_FOUND, "整合包版本不存在: " + versionId));
    }

    private PackEntry requireEntry(long entryId) throws SQLException {
        return repository.findEntryById(entryId)
                .orElseThrow(() -> new PackAdminException(NOT_FOUND, "整合包条目不存在: " + entryId));
    }

    private PackVersion requireCreatedVersion(long id) throws SQLException {
        if (id <= 0) {
            throw new IllegalStateException("数据层未返回有效的新版本 ID");
        }
        return requireVersion(id);
    }

    private PackEntry requireCreatedEntry(long id) throws SQLException {
        if (id <= 0) {
            throw new IllegalStateException("数据层未返回有效的新条目 ID");
        }
        return requireEntry(id);
    }

    private static void requireDraft(PackVersion version) {
        if (version.status() != PackVersionStatus.DRAFT) {
            throw new PackAdminException(IMMUTABLE_VERSION,
                    "已发布或归档版本不可修改，请先复制为新草稿");
        }
    }

    private static void requireCopyOnlyFields(PackDraftRequest request) {
        if (request.minecraft() != null || request.loaderKind() != null
                || request.loaderVersion() != null || request.note() != null) {
            throw new IllegalArgumentException("复制草稿时只能提交源版本 ID 与新版本号");
        }
    }

    private static long requirePositiveId(long id, String field) {
        if (id <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return id;
    }

    private static <T> T requireRequest(T request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return request;
    }

    private static <T> T requireField(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("缺少必要字段: " + field);
        }
        return value;
    }

    private static String requireMetadata(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " 不能为空或包含首尾空白");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(field + " 不能包含控制字符");
            }
        }
        return value;
    }

    private static void requireAbsent(String value, String field) {
        if (value != null) {
            throw new IllegalArgumentException("自研条目不能设置 " + field);
        }
    }

    private static PackAdminException conflict(String message) {
        return new PackAdminException(CONFLICT, message);
    }
}
