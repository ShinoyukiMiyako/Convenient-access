package com.shinoyuki.accesshub.pack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Minimal read boundary required to generate public pack manifests. */
public interface PackManifestRepository {

    Optional<PackVersion> findCurrentPublished() throws SQLException;

    Optional<PackVersion> findReleasedByVersion(String version) throws SQLException;

    List<PackEntry> findEntriesByVersionId(long versionId) throws SQLException;
}
