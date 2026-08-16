package com.shinoyuki.accesshub.pack;

/** 数据库中的整合包版本快照，时间字段统一为 Unix epoch 秒。 */
public record PackVersion(
        long id,
        String version,
        PackVersionStatus status,
        String minecraft,
        String loaderKind,
        String loaderVersion,
        String note,
        long createdAt,
        Long publishedAt) {
}
