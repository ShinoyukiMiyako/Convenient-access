package com.shinoyuki.accesshub.pack;

/** Input for creating either an empty draft or a copy of an existing version. */
public record PackDraftRequest(
        String version,
        String minecraft,
        String loaderKind,
        String loaderVersion,
        String note,
        Long copyFromVersionId) {

    public static PackDraftRequest empty(String version, String minecraft, String loaderKind,
                                         String loaderVersion, String note) {
        return new PackDraftRequest(version, minecraft, loaderKind, loaderVersion, note, null);
    }

    public static PackDraftRequest copy(long sourceVersionId, String version) {
        return new PackDraftRequest(version, null, null, null, null, sourceVersionId);
    }
}
