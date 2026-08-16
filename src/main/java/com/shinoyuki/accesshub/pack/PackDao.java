package com.shinoyuki.accesshub.pack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.shinoyuki.accesshub.database.DatabaseManager;

/** SQLite persistence for immutable, versioned modpack manifests. */
public class PackDao implements PackAdminRepository {
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of("modrinth", "curseforge");

    private final DatabaseManager databaseManager;

    public PackDao(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager");
    }

    @Override
    public Optional<PackVersion> findCurrentPublished() throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            return findCurrentPublished(connection);
        }
    }

    @Override
    public Optional<PackVersion> findReleasedByVersion(String version) throws SQLException {
        String sql = "SELECT * FROM pack_version "
                + "WHERE version = ? AND status IN ('published', 'archived')";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireNonBlank(version, "版本号"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapVersion(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PackVersion> findVersionById(long id) throws SQLException {
        String sql = "SELECT * FROM pack_version WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapVersion(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PackEntry> findEntryById(long id) throws SQLException {
        String sql = "SELECT * FROM pack_entry WHERE id = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapEntry(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public List<PackVersion> findAllVersions() throws SQLException {
        String sql = "SELECT * FROM pack_version ORDER BY created_at DESC, id DESC";
        List<PackVersion> versions = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                versions.add(mapVersion(resultSet));
            }
        }
        return List.copyOf(versions);
    }

    @Override
    public List<PackEntry> findEntriesByVersionId(long versionId) throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            return findEntriesByVersionId(connection, versionId);
        }
    }

    @Override
    public long createDraft(String version, String minecraft, String loaderKind, String loaderVersion,
                            String note, long createdAt) throws SQLException {
        requireTimestamp(createdAt, "创建时间");
        try (Connection connection = databaseManager.getConnection()) {
            return insertDraft(connection, version, minecraft, loaderKind, loaderVersion, note, createdAt);
        }
    }

    @Override
    public long copyAsDraft(long sourceId, String newVersion, long createdAt) throws SQLException {
        requireTimestamp(createdAt, "创建时间");
        requireNonBlank(newVersion, "版本号");
        return inTransaction(connection -> {
            PackVersion source = findVersionById(connection, sourceId)
                    .orElseThrow(() -> new SQLException("源整合包版本不存在: " + sourceId));
            long draftId = insertDraft(connection, newVersion, source.minecraft(), source.loaderKind(),
                    source.loaderVersion(), source.note(), createdAt);

            String copyEntries = "INSERT INTO pack_entry "
                    + "(version_id, path, kind, policy, sha1, size, download_url, platform, "
                    + "project_id, project_name, version_ext) "
                    + "SELECT ?, path, kind, policy, sha1, size, download_url, platform, "
                    + "project_id, project_name, version_ext FROM pack_entry WHERE version_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(copyEntries)) {
                statement.setLong(1, draftId);
                statement.setLong(2, sourceId);
                statement.executeUpdate();
            }
            return draftId;
        });
    }

    @Override
    public boolean updateDraft(long id, String version, String minecraft, String loaderKind,
                               String loaderVersion, String note) throws SQLException {
        String validatedVersion = requireNonBlank(version, "版本号");
        String validatedMinecraft = requireNonBlank(minecraft, "Minecraft 版本");
        String validatedLoaderKind = requireNonBlank(loaderKind, "加载器类型");
        String validatedLoaderVersion = requireNonBlank(loaderVersion, "加载器版本");
        String sql = "UPDATE pack_version SET version = ?, minecraft = ?, loader_kind = ?, "
                + "loader_ver = ?, note = ? "
                + "WHERE id = ? AND status = 'draft' AND published_at IS NULL "
                + "AND (NOT EXISTS (SELECT 1 FROM pack_entry "
                + "WHERE pack_entry.version_id = pack_version.id AND kind = 'platform') "
                + "OR (minecraft = ? AND loader_kind = ? AND loader_ver = ?))";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, validatedVersion);
            statement.setString(2, validatedMinecraft);
            statement.setString(3, validatedLoaderKind);
            statement.setString(4, validatedLoaderVersion);
            statement.setString(5, note);
            statement.setLong(6, id);
            statement.setString(7, validatedMinecraft);
            statement.setString(8, validatedLoaderKind);
            statement.setString(9, validatedLoaderVersion);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public long addEntry(long versionId, PackEntryInput entry) throws SQLException {
        PackEntryInput validated = requireValidEntry(entry);
        String sql = "INSERT INTO pack_entry "
                + "(version_id, path, kind, policy, sha1, size, download_url, platform, "
                + "project_id, project_name, version_ext) "
                + "SELECT id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM pack_version "
                + "WHERE id = ? AND status = 'draft' AND published_at IS NULL";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindEntry(statement, validated);
            statement.setLong(11, versionId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("目标整合包版本不存在或不可编辑: " + versionId);
            }
            return generatedId(statement, "整合包条目");
        }
    }

    @Override
    public boolean updateEntry(long entryId, PackEntryInput entry) throws SQLException {
        PackEntryInput validated = requireValidEntry(entry);
        String sql = "UPDATE pack_entry SET path = ?, kind = ?, policy = ?, sha1 = ?, size = ?, "
                + "download_url = ?, platform = ?, project_id = ?, project_name = ?, version_ext = ? "
                + "WHERE id = ? AND EXISTS (SELECT 1 FROM pack_version "
                + "WHERE pack_version.id = pack_entry.version_id "
                + "AND status = 'draft' AND published_at IS NULL)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindEntry(statement, validated);
            statement.setLong(11, entryId);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public boolean deleteEntry(long entryId) throws SQLException {
        String sql = "DELETE FROM pack_entry WHERE id = ? AND EXISTS ("
                + "SELECT 1 FROM pack_version WHERE pack_version.id = pack_entry.version_id "
                + "AND status = 'draft' AND published_at IS NULL)";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, entryId);
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public Optional<PackVersion> publishVersion(long id, long publishedAt,
                                                String expectedDiffRevision,
                                                boolean confirmRemovals) throws SQLException {
        requireTimestamp(publishedAt, "发布时间");
        return switchPublishedVersion(id, PackVersionStatus.DRAFT, publishedAt,
                PackDiffRevision.requireValidExpected(expectedDiffRevision), confirmRemovals);
    }

    @Override
    public Optional<PackVersion> rollbackToVersion(long id, String expectedDiffRevision,
                                                   boolean confirmRemovals) throws SQLException {
        return switchPublishedVersion(id, PackVersionStatus.ARCHIVED, null,
                PackDiffRevision.requireValidExpected(expectedDiffRevision), confirmRemovals);
    }

    private Optional<PackVersion> switchPublishedVersion(
            long targetId, PackVersionStatus requiredStatus, Long firstPublishedAt,
            String expectedDiffRevision, boolean confirmRemovals) throws SQLException {
        return inTransaction(connection -> {
            String lockTarget = "UPDATE pack_version SET status = status "
                    + "WHERE id = ? AND status = ? AND "
                    + (requiredStatus == PackVersionStatus.DRAFT
                            ? "published_at IS NULL"
                            : "published_at IS NOT NULL");
            try (PreparedStatement statement = connection.prepareStatement(lockTarget)) {
                statement.setLong(1, targetId);
                statement.setString(2, requiredStatus.databaseValue());
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }

            PackVersion target = findVersionById(connection, targetId)
                    .orElseThrow(() -> new SQLException("锁定后目标整合包版本不存在: " + targetId));
            Optional<PackVersion> published = findCurrentPublished(connection);
            List<PackEntry> publishedEntries = published.isPresent()
                    ? findEntriesByVersionId(connection, published.get().id())
                    : List.of();
            PackVersionDiff actualDiff = PackReleasePlanner.compare(
                    published.orElse(null), target, publishedEntries,
                    findEntriesByVersionId(connection, targetId));
            PackReleasePlanner.requireReviewed(
                    actualDiff, expectedDiffRevision, confirmRemovals);

            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pack_version SET status = 'archived' WHERE status = 'published'")) {
                statement.executeUpdate();
            }

            String publishTarget = firstPublishedAt == null
                    ? "UPDATE pack_version SET status = 'published' "
                            + "WHERE id = ? AND status = 'archived' AND published_at IS NOT NULL"
                    : "UPDATE pack_version SET status = 'published', published_at = ? "
                            + "WHERE id = ? AND status = 'draft' AND published_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(publishTarget)) {
                if (firstPublishedAt == null) {
                    statement.setLong(1, targetId);
                } else {
                    statement.setLong(1, firstPublishedAt);
                    statement.setLong(2, targetId);
                }
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("切换当前发布版本时目标状态发生变化: " + targetId);
                }
            }
            PackVersion released = findVersionById(connection, targetId)
                    .orElseThrow(() -> new SQLException(
                            "切换成功后目标整合包版本不存在: " + targetId));
            if (released.status() != PackVersionStatus.PUBLISHED) {
                throw new SQLException("切换成功后目标整合包版本状态非法: " + targetId);
            }
            return Optional.of(released);
        });
    }

    private long insertDraft(Connection connection, String version, String minecraft, String loaderKind,
                             String loaderVersion, String note, long createdAt) throws SQLException {
        String sql = "INSERT INTO pack_version "
                + "(version, status, minecraft, loader_kind, loader_ver, note, created_at) "
                + "VALUES (?, 'draft', ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, requireNonBlank(version, "版本号"));
            statement.setString(2, requireNonBlank(minecraft, "Minecraft 版本"));
            statement.setString(3, requireNonBlank(loaderKind, "加载器类型"));
            statement.setString(4, requireNonBlank(loaderVersion, "加载器版本"));
            statement.setString(5, note);
            statement.setLong(6, createdAt);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("创建整合包草稿失败: " + version);
            }
            return generatedId(statement, "整合包版本");
        }
    }

    private Optional<PackVersion> findVersionById(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pack_version WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapVersion(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<PackVersion> findCurrentPublished(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pack_version WHERE status = 'published'");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            PackVersion version = mapVersion(resultSet);
            if (resultSet.next()) {
                throw new SQLException("数据库包含多个 published 整合包版本");
            }
            return Optional.of(version);
        }
    }

    private List<PackEntry> findEntriesByVersionId(Connection connection, long versionId)
            throws SQLException {
        List<PackEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pack_entry WHERE version_id = ? ORDER BY path, id")) {
            statement.setLong(1, versionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(mapEntry(resultSet));
                }
            }
        }
        return List.copyOf(entries);
    }

    private static PackVersion mapVersion(ResultSet resultSet) throws SQLException {
        long publishedAtValue = resultSet.getLong("published_at");
        Long publishedAt = resultSet.wasNull() ? null : publishedAtValue;
        return new PackVersion(
                resultSet.getLong("id"),
                resultSet.getString("version"),
                PackVersionStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getString("minecraft"),
                resultSet.getString("loader_kind"),
                resultSet.getString("loader_ver"),
                resultSet.getString("note"),
                resultSet.getLong("created_at"),
                publishedAt);
    }

    private static PackEntry mapEntry(ResultSet resultSet) throws SQLException {
        return new PackEntry(
                resultSet.getLong("id"),
                resultSet.getLong("version_id"),
                resultSet.getString("path"),
                PackEntryKind.fromDatabase(resultSet.getString("kind")),
                PackEntryPolicy.fromDatabase(resultSet.getString("policy")),
                resultSet.getString("sha1"),
                resultSet.getLong("size"),
                resultSet.getString("download_url"),
                resultSet.getString("platform"),
                resultSet.getString("project_id"),
                resultSet.getString("project_name"),
                resultSet.getString("version_ext"));
    }

    private static PackEntryInput requireValidEntry(PackEntryInput entry) {
        if (entry == null) {
            throw new IllegalArgumentException("整合包条目不能为空");
        }
        PackPathValidator.requireValid(entry.path());
        if (entry.kind() == null) {
            throw new IllegalArgumentException("整合包条目类型不能为空");
        }
        if (entry.policy() == null) {
            throw new IllegalArgumentException("整合包同步策略不能为空");
        }
        PackEntryValidator.requireValidSha1(entry.sha1());
        PackEntryValidator.requireValidSize(entry.size());
        PackEntryValidator.requireValidDownloadUrl(entry.downloadUrl());

        if (entry.kind() == PackEntryKind.PLATFORM) {
            if (entry.platform() == null || !SUPPORTED_PLATFORMS.contains(entry.platform())) {
                throw new IllegalArgumentException("平台条目仅支持 modrinth 或 curseforge");
            }
            requireNonBlank(entry.projectId(), "平台项目 ID");
            requireNonBlank(entry.projectName(), "平台项目名");
            requireNonBlank(entry.externalVersionId(), "平台版本 ID");
        } else if (entry.platform() != null || entry.projectId() != null
                || entry.projectName() != null || entry.externalVersionId() != null) {
            throw new IllegalArgumentException("自研条目不能包含平台元数据");
        }
        return entry;
    }

    private static void bindEntry(PreparedStatement statement, PackEntryInput entry) throws SQLException {
        statement.setString(1, entry.path());
        statement.setString(2, entry.kind().databaseValue());
        statement.setString(3, entry.policy().databaseValue());
        statement.setString(4, entry.sha1());
        statement.setLong(5, entry.size());
        statement.setString(6, entry.downloadUrl());
        statement.setString(7, entry.platform());
        statement.setString(8, entry.projectId());
        statement.setString(9, entry.projectName());
        statement.setString(10, entry.externalVersionId());
    }

    private static long generatedId(PreparedStatement statement, String entity) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException(entity + "写入成功但数据库未返回 ID");
            }
            return keys.getLong(1);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能为空或包含首尾空白");
        }
        return value;
    }

    private static long requireTimestamp(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + "不能为负数");
        }
        return value;
    }

    private <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }
                throw exception;
            }
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
