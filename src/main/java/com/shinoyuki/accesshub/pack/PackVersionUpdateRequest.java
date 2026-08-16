package com.shinoyuki.accesshub.pack;

/** Mutable metadata of a draft pack version. */
public record PackVersionUpdateRequest(
        String version,
        String minecraft,
        String loaderKind,
        String loaderVersion,
        String note) {
}
