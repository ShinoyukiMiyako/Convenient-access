package com.shinoyuki.accesshub.pack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Persistence boundary used by the pack administration service. */
public interface PackAdminRepository extends PackManifestRepository {
    Optional<PackVersion> findVersionById(long id) throws SQLException;

    Optional<PackEntry> findEntryById(long id) throws SQLException;

    List<PackVersion> findAllVersions() throws SQLException;

    long createDraft(String version, String minecraft, String loaderKind, String loaderVersion,
                     String note, long createdAt) throws SQLException;

    long copyAsDraft(long sourceId, String newVersion, long createdAt) throws SQLException;

    boolean updateDraft(long id, String version, String minecraft, String loaderKind,
                        String loaderVersion, String note) throws SQLException;

    long addEntry(long versionId, PackEntryInput entry) throws SQLException;

    boolean updateEntry(long entryId, PackEntryInput entry) throws SQLException;

    boolean deleteEntry(long entryId) throws SQLException;

    Optional<PackVersion> publishVersion(long id, long publishedAt, String expectedDiffRevision,
                                         boolean confirmRemovals) throws SQLException;

    Optional<PackVersion> rollbackToVersion(long id, String expectedDiffRevision,
                                            boolean confirmRemovals) throws SQLException;
}
