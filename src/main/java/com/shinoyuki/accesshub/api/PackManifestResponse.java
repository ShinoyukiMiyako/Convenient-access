package com.shinoyuki.accesshub.api;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/** Immutable public manifest consumed by Aurora. */
public record PackManifestResponse(
        int schema,
        @SerializedName("pack_id") String packId,
        String version,
        String minecraft,
        Loader loader,
        List<FileEntry> files) {

    public PackManifestResponse {
        files = List.copyOf(files);
    }

    public record Loader(String kind, String version) {
    }

    public record FileEntry(
            String path,
            String sha1,
            long size,
            String policy,
            List<String> urls) {

        public FileEntry {
            urls = List.copyOf(urls);
        }
    }
}
