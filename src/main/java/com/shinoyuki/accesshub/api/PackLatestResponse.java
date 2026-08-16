package com.shinoyuki.accesshub.api;

import com.google.gson.annotations.SerializedName;

/** Public pointer to the single currently published modpack version. */
public record PackLatestResponse(
        @SerializedName("pack_id") String packId,
        String version,
        @SerializedName("manifest_url") String manifestUrl,
        @SerializedName("released_at") String releasedAt,
        String note,
        @SerializedName("min_launcher_version") String minLauncherVersion) {
}
