package com.shinoyuki.accesshub.pack;

/** Confirmation body shared by publish and rollback administration endpoints. */
public record PackReleaseRequest(boolean confirmRemovals, String expectedDiffRevision) {
}
