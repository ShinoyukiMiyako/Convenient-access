package com.shinoyuki.accesshub.pack;

/** 新增或更新整合包文件条目所需的数据。 */
public record PackEntryInput(
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
