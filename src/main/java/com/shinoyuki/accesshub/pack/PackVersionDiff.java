package com.shinoyuki.accesshub.pack;

import java.util.List;

/** Path-keyed comparison of a candidate version against the current publication. */
public record PackVersionDiff(
        String revision,
        PackVersion publishedVersion,
        PackVersion targetVersion,
        List<PackEntry> added,
        List<EntryChange> changed,
        List<PackEntry> removed) {

    public PackVersionDiff {
        revision = PackDiffRevision.requireValidExpected(revision);
        added = List.copyOf(added);
        changed = List.copyOf(changed);
        removed = List.copyOf(removed);
    }

    public boolean hasRemovals() {
        return !removed.isEmpty();
    }

    public record EntryChange(PackEntry before, PackEntry after, List<String> changedFields) {
        public EntryChange {
            changedFields = List.copyOf(changedFields);
        }
    }
}
