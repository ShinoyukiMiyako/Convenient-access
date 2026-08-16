package com.shinoyuki.accesshub.pack;

/** 数据库中的整合包文件条目快照。 */
public record PackEntry(
        long id,
        long versionId,
        String path,
        PackEntryKind kind,
        PackEntryPolicy policy,
        String sha1,
        long size,
        String downloadUrl,
        String platform,
        String projectId,
        String projectName,
        String externalVersionId) {
}
