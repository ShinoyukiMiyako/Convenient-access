package com.shinoyuki.accesshub.pack;

/** Complete entry value used for both insert and replacement-style update. */
public record PackEntryRequest(
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
