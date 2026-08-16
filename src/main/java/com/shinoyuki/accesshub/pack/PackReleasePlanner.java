package com.shinoyuki.accesshub.pack;

import static com.shinoyuki.accesshub.pack.PackAdminException.Reason.REMOVAL_CONFIRMATION_REQUIRED;
import static com.shinoyuki.accesshub.pack.PackAdminException.Reason.STALE_DIFF;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure release comparison shared by preview and the transactional repository boundary. */
final class PackReleasePlanner {
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("modrinth", "curseforge");

    private PackReleasePlanner() {
    }

    static PackVersionDiff compare(PackVersion publishedVersion, PackVersion targetVersion,
                                   List<PackEntry> publishedEntryList,
                                   List<PackEntry> targetEntryList) {
        requireValidPersistedVersion(targetVersion);
        if (publishedVersion != null) {
            requireValidCurrentPublication(publishedVersion);
        } else if (!publishedEntryList.isEmpty()) {
            throw new IllegalStateException("不存在当前发布版本时不能返回已发布条目");
        }

        Map<String, PackEntry> publishedEntries = publishedVersion == null
                ? Map.of()
                : indexEntries(publishedVersion, publishedEntryList);
        Map<String, PackEntry> targetEntries = indexEntries(targetVersion, targetEntryList);
        rejectCaseOnlyRenames(publishedEntries, targetEntries);

        List<PackEntry> added = new ArrayList<>();
        List<PackVersionDiff.EntryChange> changed = new ArrayList<>();
        List<PackEntry> removed = new ArrayList<>();
        for (Map.Entry<String, PackEntry> entry : targetEntries.entrySet()) {
            PackEntry before = publishedEntries.get(entry.getKey());
            if (before == null) {
                added.add(entry.getValue());
                continue;
            }
            List<String> fields = changedFields(before, entry.getValue());
            if (!fields.isEmpty()) {
                changed.add(new PackVersionDiff.EntryChange(before, entry.getValue(), fields));
            }
        }
        for (Map.Entry<String, PackEntry> entry : publishedEntries.entrySet()) {
            if (!targetEntries.containsKey(entry.getKey())) {
                removed.add(entry.getValue());
            }
        }

        String revision = PackDiffRevision.compute(
                publishedVersion, targetVersion,
                List.copyOf(publishedEntries.values()), List.copyOf(targetEntries.values()));
        return new PackVersionDiff(revision, publishedVersion, targetVersion, added, changed, removed);
    }

    static void requireReviewed(PackVersionDiff actual, String expectedRevision,
                                boolean confirmRemovals) {
        String expected = PackDiffRevision.requireValidExpected(expectedRevision);
        if (!actual.revision().equals(expected)) {
            throw new PackAdminException(STALE_DIFF,
                    "整合包差异已变化，请重新获取并审阅后再执行版本切换");
        }
        if (actual.hasRemovals() && !confirmRemovals) {
            throw new PackAdminException(REMOVAL_CONFIRMATION_REQUIRED,
                    "发布将移除 " + actual.removed().size() + " 个条目，必须显式确认");
        }
    }

    private static Map<String, PackEntry> indexEntries(PackVersion version, List<PackEntry> entryList) {
        Map<String, PackEntry> entries = new TreeMap<>();
        Set<String> portablePaths = new HashSet<>();
        for (PackEntry entry : entryList) {
            if (entry == null || entry.versionId() != version.id()) {
                throw new IllegalStateException("数据层返回了属于其他版本的条目");
            }
            requireValidPersistedEntry(entry);
            if (!portablePaths.add(portablePath(entry.path()))) {
                throw new IllegalStateException("同一版本包含大小写碰撞路径: " + entry.path());
            }
            entries.put(entry.path(), entry);
        }
        return entries;
    }

    private static void requireValidPersistedEntry(PackEntry entry) {
        try {
            if (entry.id() <= 0) {
                throw new IllegalArgumentException("条目 ID 必须为正整数");
            }
            PackPathValidator.requireValid(entry.path());
            if (entry.kind() == null || entry.policy() == null) {
                throw new IllegalArgumentException("条目类型与同步策略不能为空");
            }
            PackEntryValidator.requireValidSha1(entry.sha1());
            PackEntryValidator.requireValidSize(entry.size());
            PackEntryValidator.requireValidDownloadUrl(entry.downloadUrl());
            if (entry.kind() == PackEntryKind.PLATFORM) {
                if (entry.platform() == null || !SUPPORTED_PLATFORMS.contains(entry.platform())) {
                    throw new IllegalArgumentException("平台条目仅支持 modrinth 或 curseforge");
                }
                requireMetadata(entry.projectId(), "平台项目 ID");
                requireMetadata(entry.projectName(), "平台项目名");
                requireMetadata(entry.externalVersionId(), "平台版本 ID");
            } else if (entry.platform() != null || entry.projectId() != null
                    || entry.projectName() != null || entry.externalVersionId() != null) {
                throw new IllegalArgumentException("自研条目不能包含平台元数据");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("数据库中的整合包条目非法: " + entry.id(), exception);
        }
    }

    private static void requireValidPersistedVersion(PackVersion version) {
        if (version == null) {
            throw new IllegalStateException("数据层未返回目标整合包版本");
        }
        try {
            if (version.id() <= 0 || version.createdAt() < 0 || version.status() == null) {
                throw new IllegalArgumentException("版本标识、时间或状态非法");
            }
            PackVersionValidator.requireValidVersion(version.version());
            PackVersionValidator.requireValidMinecraft(version.minecraft());
            PackVersionValidator.requireValidLoaderKind(version.loaderKind());
            PackVersionValidator.requireValidLoaderVersion(version.loaderVersion());
            PackVersionValidator.requireValidNote(version.note());
            if (version.status() == PackVersionStatus.DRAFT && version.publishedAt() != null) {
                throw new IllegalArgumentException("草稿版本不能包含发布时间");
            }
            if (version.status() != PackVersionStatus.DRAFT
                    && (version.publishedAt() == null || version.publishedAt() < 0)) {
                throw new IllegalArgumentException("已发布版本必须包含有效发布时间");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("数据库中的整合包版本非法: " + version.id(), exception);
        }
    }

    private static void requireValidCurrentPublication(PackVersion version) {
        requireValidPersistedVersion(version);
        if (version.status() != PackVersionStatus.PUBLISHED) {
            throw new IllegalStateException("当前发布版本状态非法: " + version.id());
        }
    }

    private static void rejectCaseOnlyRenames(Map<String, PackEntry> published,
                                              Map<String, PackEntry> target) {
        Map<String, String> publishedPaths = new LinkedHashMap<>();
        published.keySet().forEach(path -> publishedPaths.put(portablePath(path), path));
        for (String targetPath : target.keySet()) {
            String publishedPath = publishedPaths.get(portablePath(targetPath));
            if (publishedPath != null && !publishedPath.equals(targetPath)) {
                throw new PackAdminException(PackAdminException.Reason.CONFLICT,
                        "单次版本切换不能只修改路径大小写: "
                                + publishedPath + " -> " + targetPath);
            }
        }
    }

    private static String portablePath(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static String requireMetadata(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能为空或包含首尾空白");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + "不能包含控制字符");
            }
        }
        return value;
    }

    private static List<String> changedFields(PackEntry before, PackEntry after) {
        Map<String, Boolean> comparisons = new LinkedHashMap<>();
        comparisons.put("kind", before.kind() != after.kind());
        comparisons.put("policy", before.policy() != after.policy());
        comparisons.put("sha1", !Objects.equals(before.sha1(), after.sha1()));
        comparisons.put("size", before.size() != after.size());
        comparisons.put("downloadUrl", !Objects.equals(before.downloadUrl(), after.downloadUrl()));
        comparisons.put("platform", !Objects.equals(before.platform(), after.platform()));
        comparisons.put("projectId", !Objects.equals(before.projectId(), after.projectId()));
        comparisons.put("projectName", !Objects.equals(before.projectName(), after.projectName()));
        comparisons.put("externalVersionId",
                !Objects.equals(before.externalVersionId(), after.externalVersionId()));
        return comparisons.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
    }
}
