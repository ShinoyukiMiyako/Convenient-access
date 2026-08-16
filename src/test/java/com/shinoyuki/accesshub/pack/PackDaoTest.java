package com.shinoyuki.accesshub.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shinoyuki.accesshub.database.DatabaseManager;

class PackDaoTest {
    private static final String SHA1_A = "a".repeat(40);
    private static final String SHA1_B = "b".repeat(40);

    @TempDir
    File tempDir;

    private DatabaseManager databaseManager;
    private PackDao dao;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir);
        assertTrue(databaseManager.initialize().get(), "测试数据库必须初始化成功");
        dao = new PackDao(databaseManager);
    }

    @AfterEach
    void tearDown() {
        databaseManager.shutdown();
    }

    @Test
    void draftCrudCopyAndStableEntryOrdering() throws Exception {
        long first = createDraft("1.0.0", 100);
        long zEntry = dao.addEntry(first, customEntry("mods/z.jar", SHA1_A, 30));
        long aEntry = dao.addEntry(first, customEntry("mods/a.jar", SHA1_B, 10));

        assertEquals(List.of("mods/a.jar", "mods/z.jar"), dao.findEntriesByVersionId(first).stream()
                .map(PackEntry::path)
                .toList(), "数据库查询顺序必须稳定按路径排列");

        PackEntryInput updated = new PackEntryInput(
                "mods/b.jar", PackEntryKind.CUSTOM, PackEntryPolicy.OPTIONAL,
                SHA1_A, 42, "https://cdn.example.com/b.jar", null, null, null, null);
        assertTrue(dao.updateEntry(aEntry, updated), "草稿条目应允许更新");
        assertEquals(updated.path(), dao.findEntryById(aEntry).orElseThrow().path());
        assertTrue(dao.updateDraft(first, "1.0.1", "1.20.1", "forge", "47.4.21", "修订"));
        assertEquals("47.4.21", dao.findVersionById(first).orElseThrow().loaderVersion());
        assertThrows(SQLException.class,
                () -> dao.createDraft("1.0.1", "1.20.1", "forge", "47.4.20", null, 101),
                "版本号必须全局唯一");

        long copy = dao.copyAsDraft(first, "2.0.0", 200);
        PackVersion copiedVersion = dao.findVersionById(copy).orElseThrow();
        assertEquals(PackVersionStatus.DRAFT, copiedVersion.status());
        assertEquals("47.4.21", copiedVersion.loaderVersion());
        assertEquals(List.of("mods/b.jar", "mods/z.jar"), dao.findEntriesByVersionId(copy).stream()
                .map(PackEntry::path)
                .toList(), "复制草稿必须复制全部条目");

        long copiedEntry = dao.findEntriesByVersionId(copy).get(0).id();
        assertTrue(dao.deleteEntry(copiedEntry), "复制出的草稿条目应允许删除");
        assertEquals(1, dao.findEntriesByVersionId(copy).size());

        assertThrows(SQLException.class,
                () -> dao.addEntry(first, customEntry("mods/z.jar", SHA1_B, 99)),
                "同一版本不得写入重复路径");
        dao.addEntry(first, customEntry("mods/CaseSensitive.jar", SHA1_A, 12));
        assertThrows(SQLException.class,
                () -> dao.addEntry(first, customEntry("mods/casesensitive.jar", SHA1_B, 13)),
                "Windows 上指向同一文件的大小写变体必须视为重复路径");
        long third = createDraft("3.0.0", 300);
        long thirdEntry = dao.addEntry(third, customEntry("mods/z.jar", SHA1_B, 99));
        assertEquals(third, dao.findEntryById(thirdEntry).orElseThrow().versionId(),
                "不同版本允许复用同一路径");
        assertEquals(first, dao.findEntryById(zEntry).orElseThrow().versionId());
    }

    @Test
    void publishedAndArchivedManifestsRemainImmutableAcrossRollback() throws Exception {
        long first = createDraft("1.0.0", 100);
        long firstEntry = dao.addEntry(first, customEntry("mods/first.jar", SHA1_A, 10));
        assertTrue(dao.findReleasedByVersion("1.0.0").isEmpty(), "草稿不得作为公开版本读取");

        assertTrue(publishReviewed(first, 1_000));
        PackVersion firstPublished = dao.findCurrentPublished().orElseThrow();
        assertEquals(first, firstPublished.id());
        assertEquals(1_000L, firstPublished.publishedAt());
        assertTrue(dao.findReleasedByVersion("1.0.0").isPresent());
        assertThrows(SQLException.class, () -> executeUpdate(
                "INSERT OR REPLACE INTO pack_version "
                        + "(version,status,minecraft,loader_kind,loader_ver,created_at) "
                        + "VALUES ('1.0.0','draft','1.20.1','forge','47.4.20',999)"),
                "REPLACE 不得绕过发布版本不可变性");
        assertEquals(first, dao.findCurrentPublished().orElseThrow().id());
        assertEquals(first, dao.findEntryById(firstEntry).orElseThrow().versionId(),
                "失败的 REPLACE 不得级联删除已发布清单");

        assertFalse(dao.updateDraft(first, "1.0.1", "1.20.1", "forge", "47.4.20", null));
        assertFalse(dao.updateEntry(firstEntry, customEntry("mods/changed.jar", SHA1_B, 11)));
        assertFalse(dao.deleteEntry(firstEntry));
        assertThrows(SQLException.class,
                () -> dao.addEntry(first, customEntry("mods/new.jar", SHA1_B, 11)));
        assertReleasedRowsRejectDirectMutation(first, firstEntry);

        long second = createDraft("2.0.0", 200);
        dao.addEntry(second, customEntry("mods/second.jar", SHA1_B, 20));
        assertThrows(SQLException.class, () -> executeUpdate(
                "UPDATE OR REPLACE pack_version SET version='1.0.0' WHERE id=" + second),
                "UPDATE OR REPLACE 也不得隐式删除已发布版本");
        assertEquals("2.0.0", dao.findVersionById(second).orElseThrow().version());
        assertTrue(publishReviewed(second, 2_000));
        assertEquals(PackVersionStatus.ARCHIVED, dao.findVersionById(first).orElseThrow().status());
        assertEquals(1_000L, dao.findVersionById(first).orElseThrow().publishedAt(),
                "归档不得重写首次发布时间");
        assertReleasedRowsRejectDirectMutation(first, firstEntry);

        assertTrue(rollbackReviewed(first));
        PackVersion rolledBack = dao.findCurrentPublished().orElseThrow();
        assertEquals(first, rolledBack.id());
        assertEquals(1_000L, rolledBack.publishedAt(), "回滚必须保留首次发布时间");
        assertEquals(PackVersionStatus.ARCHIVED, dao.findVersionById(second).orElseThrow().status());
        assertEquals(2_000L, dao.findVersionById(second).orElseThrow().publishedAt());
        assertFalse(rollbackReviewed(first), "当前发布版本不能再次作为归档目标回滚");
        assertEquals(1, countRows("SELECT COUNT(*) FROM pack_version WHERE status='published'"));
    }

    @Test
    void daoValidationAndDatabaseConstraintsRejectInvalidEntries() throws Exception {
        long draft = createDraft("1.0.0", 100);
        assertEquals(1, countRows("PRAGMA foreign_keys"));
        assertEquals(1, countRows("PRAGMA recursive_triggers"));
        for (String path : List.of("../escape.jar", "/mods/absolute.jar", "C:/mods/drive.jar",
                "mods\\backslash.jar", "mods/CON.jar", "mods/COM¹.jar", "mods//empty.jar",
                ".AURORA/state.json", "SAVES/world/level.dat", "screenshots/image.png", "logs/latest.log")) {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.addEntry(draft, customEntry(path, SHA1_A, 1)),
                    "DAO 必须拒绝不安全路径: " + path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> dao.addEntry(draft, customEntry("mods/upper.jar", "A".repeat(40), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> dao.addEntry(draft, customEntry("mods/negative.jar", SHA1_A, -1)));
        assertThrows(IllegalArgumentException.class, () -> dao.addEntry(draft, new PackEntryInput(
                "mods/missing-kind.jar", null, PackEntryPolicy.MANAGED,
                SHA1_A, 1, "https://cdn.example.com/missing-kind.jar", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> dao.addEntry(draft, new PackEntryInput(
                "mods/missing-policy.jar", PackEntryKind.CUSTOM, null,
                SHA1_A, 1, "https://cdn.example.com/missing-policy.jar", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> dao.addEntry(draft, new PackEntryInput(
                "mods/missing-platform.jar", PackEntryKind.PLATFORM, PackEntryPolicy.MANAGED,
                SHA1_A, 1, "https://cdn.example.com/missing-platform.jar",
                null, "project", "Project", "version")));
        assertThrows(IllegalArgumentException.class, () -> dao.addEntry(draft, new PackEntryInput(
                "mods/custom.jar", PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED,
                SHA1_A, 1, "https://cdn.example.com/custom.jar",
                "modrinth", "project", "Project", "version")));

        assertThrows(SQLException.class, () -> executeUpdate(
                "INSERT INTO pack_version "
                        + "(version,status,minecraft,loader_kind,loader_ver,created_at) "
                        + "VALUES ('invalid','broken','1.20.1','forge','47.4.20',1)"));
        assertThrows(SQLException.class, () -> executeUpdate(entryInsertSql(999_999, "mods/orphan.jar",
                "custom", "managed", SHA1_A, null, null, null, null)));
        assertThrows(SQLException.class, () -> executeUpdate(entryInsertSql(draft, "mods/kind.jar",
                "broken", "managed", SHA1_A, null, null, null, null)));
        assertThrows(SQLException.class, () -> executeUpdate(entryInsertSql(draft, "mods/policy.jar",
                "custom", "broken", SHA1_A, null, null, null, null)));
        assertThrows(SQLException.class, () -> executeUpdate(entryInsertSql(draft, "mods/metadata.jar",
                "custom", "managed", SHA1_A, "modrinth", "project", "Project", "version")));
        assertThrows(SQLException.class, () -> executeUpdate(entryInsertSql(draft, "mods/platform.jar",
                "platform", "managed", SHA1_A, null, null, null, null)),
                "平台条目必须带齐平台元数据");
        assertThrows(SQLException.class, () -> executeUpdate(
                "UPDATE pack_version SET status='archived', published_at=1 WHERE id=" + draft),
                "草稿不能跳过 published 直接进入 archived");

        dao.addEntry(draft, customEntry("mods/cascade.jar", SHA1_A, 1));
        executeUpdate("DELETE FROM pack_version WHERE id=" + draft);
        assertEquals(0, countRows("SELECT COUNT(*) FROM pack_entry WHERE version_id=" + draft),
                "每个 DAO 连接都应启用外键并执行级联删除");

        long published = createDraft("2.0.0", 200);
        long competing = createDraft("3.0.0", 300);
        assertTrue(publishReviewed(published, 2_000));
        assertThrows(SQLException.class, () -> executeUpdate(
                "UPDATE pack_version SET status='published', published_at=3000 WHERE id=" + competing),
                "部分唯一索引必须拒绝第二个 published 版本");
        assertEquals(1, countRows("SELECT COUNT(*) FROM pack_version WHERE status='published'"));
    }

    @Test
    void platformEntriesAtomicallyFreezeDraftRuntimeCoordinates() throws Exception {
        long draft = createDraft("1.0.0", 100);
        dao.addEntry(draft, platformEntry("mods/platform.jar"));

        assertTrue(dao.updateDraft(
                draft, "1.0.1", "1.20.1", "forge", "47.4.20", "renamed"));
        assertFalse(dao.updateDraft(
                draft, "1.0.2", "1.20.2", "forge", "47.4.20", "changed minecraft"));
        assertFalse(dao.updateDraft(
                draft, "1.0.2", "1.20.1", "neoforge", "47.4.20", "changed loader"));
        assertFalse(dao.updateDraft(
                draft, "1.0.2", "1.20.1", "forge", "47.4.21", "changed loader version"));

        PackVersion unchanged = dao.findVersionById(draft).orElseThrow();
        assertEquals("1.0.1", unchanged.version());
        assertEquals("1.20.1", unchanged.minecraft());
        assertEquals("forge", unchanged.loaderKind());
        assertEquals("47.4.20", unchanged.loaderVersion());
        assertEquals("renamed", unchanged.note());
    }

    @Test
    void releaseMethodsReturnStableTransactionSnapshotsAfterLaterSwitches() throws Exception {
        long first = createDraft("1.0.0", 100);
        PackVersion firstSnapshot = dao.publishVersion(
                first, 1_000, reviewedRevision(first), true).orElseThrow();
        long second = createDraft("2.0.0", 200);
        assertTrue(publishReviewed(second, 2_000));

        assertEquals(PackVersionStatus.PUBLISHED, firstSnapshot.status());
        assertEquals(1_000L, firstSnapshot.publishedAt());
        assertEquals(PackVersionStatus.ARCHIVED, dao.findVersionById(first).orElseThrow().status());

        PackVersion rollbackSnapshot = dao.rollbackToVersion(
                first, reviewedRevision(first), true).orElseThrow();
        long third = createDraft("3.0.0", 300);
        assertTrue(publishReviewed(third, 3_000));

        assertEquals(PackVersionStatus.PUBLISHED, rollbackSnapshot.status());
        assertEquals(1_000L, rollbackSnapshot.publishedAt());
        assertEquals(PackVersionStatus.ARCHIVED, dao.findVersionById(first).orElseThrow().status());
        assertEquals(third, dao.findCurrentPublished().orElseThrow().id());
    }

    @Test
    void atomicPublishChecksRemovalConfirmationBeforeChangingAnyStatus() throws Exception {
        long current = createDraft("1.0.0", 100);
        dao.addEntry(current, customEntry("mods/removed.jar", SHA1_A, 10));
        assertTrue(publishReviewed(current, 1_000));
        long target = createDraft("2.0.0", 200);
        String reviewedRevision = reviewedRevision(target);

        PackAdminException failure = assertThrows(PackAdminException.class,
                () -> dao.publishVersion(target, 2_000, reviewedRevision, false));

        assertEquals(PackAdminException.Reason.REMOVAL_CONFIRMATION_REQUIRED, failure.reason());
        assertEquals(current, dao.findCurrentPublished().orElseThrow().id());
        assertEquals(PackVersionStatus.DRAFT, dao.findVersionById(target).orElseThrow().status());
    }

    @Test
    void reviewedRevisionRejectsTargetEntryChangesBeforeAtomicPublish() throws Exception {
        long current = createDraft("1.0.0", 100);
        dao.addEntry(current, customEntry("mods/base.jar", SHA1_A, 10));
        assertTrue(publishReviewed(current, 1_000));
        long target = createDraft("2.0.0", 200);
        dao.addEntry(target, customEntry("mods/base.jar", SHA1_A, 10));

        String beforeAddition = reviewedRevision(target);
        long added = dao.addEntry(target, customEntry("mods/unreviewed.jar", SHA1_B, 20));
        PackAdminException additionFailure = assertThrows(PackAdminException.class,
                () -> dao.publishVersion(target, 2_000, beforeAddition, true));
        assertEquals(PackAdminException.Reason.STALE_DIFF, additionFailure.reason());

        String beforeDeletion = reviewedRevision(target);
        assertTrue(dao.deleteEntry(added));
        PackAdminException deletionFailure = assertThrows(PackAdminException.class,
                () -> dao.publishVersion(target, 2_000, beforeDeletion, true));
        assertEquals(PackAdminException.Reason.STALE_DIFF, deletionFailure.reason());
        assertEquals(PackVersionStatus.DRAFT, dao.findVersionById(target).orElseThrow().status());
        assertEquals(current, dao.findCurrentPublished().orElseThrow().id());
    }

    @Test
    void reviewedRevisionRejectsChangedPublishedBaselineBeforeAtomicPublish() throws Exception {
        long current = createDraft("1.0.0", 100);
        dao.addEntry(current, customEntry("mods/base.jar", SHA1_A, 10));
        assertTrue(publishReviewed(current, 1_000));
        long target = createDraft("2.0.0", 200);
        dao.addEntry(target, customEntry("mods/base.jar", SHA1_A, 10));
        long competing = createDraft("3.0.0", 300);
        dao.addEntry(competing, customEntry("mods/base.jar", SHA1_A, 10));

        String targetRevision = reviewedRevision(target);
        assertTrue(publishReviewed(competing, 3_000));
        PackAdminException failure = assertThrows(PackAdminException.class,
                () -> dao.publishVersion(target, 2_000, targetRevision, true));

        assertEquals(PackAdminException.Reason.STALE_DIFF, failure.reason());
        assertEquals(PackVersionStatus.DRAFT, dao.findVersionById(target).orElseThrow().status());
        assertEquals(competing, dao.findCurrentPublished().orElseThrow().id());
    }

    @Test
    void concurrentPublishesRequireFreshBaselineAndLeaveExactlyOneCurrentVersion() throws Exception {
        int versionCount = 6;
        long[] ids = new long[versionCount];
        String[] revisions = new String[versionCount];
        for (int index = 0; index < versionCount; index++) {
            ids[index] = createDraft("1.0." + index, index + 1);
            revisions[index] = reviewedRevision(ids[index]);
        }

        ExecutorService executor = Executors.newFixedThreadPool(versionCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] results = new Future[versionCount];
            for (int index = 0; index < versionCount; index++) {
                int current = index;
                results[index] = executor.submit(() -> {
                    start.await();
                    try {
                        return dao.publishVersion(
                                ids[current], 10_000 + current, revisions[current], true).isPresent();
                    } catch (PackAdminException exception) {
                        if (exception.reason() == PackAdminException.Reason.STALE_DIFF) {
                            return false;
                        }
                        throw exception;
                    }
                });
            }
            start.countDown();
            int successfulPublishes = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successfulPublishes++;
                }
            }
            assertEquals(1, successfulPublishes,
                    "同一已审阅基线只能允许一个并发发布完成");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, countRows("SELECT COUNT(*) FROM pack_version WHERE status='published'"));
        assertEquals(0, countRows("SELECT COUNT(*) FROM pack_version WHERE status='archived'"));
        assertEquals(versionCount - 1,
                countRows("SELECT COUNT(*) FROM pack_version WHERE status='draft'"));
        assertTrue(dao.findCurrentPublished().isPresent());
    }

    private boolean publishReviewed(long versionId, long publishedAt) throws SQLException {
        return dao.publishVersion(
                versionId, publishedAt, reviewedRevision(versionId), true).isPresent();
    }

    private boolean rollbackReviewed(long versionId) throws SQLException {
        return dao.rollbackToVersion(
                versionId, reviewedRevision(versionId), true).isPresent();
    }

    private String reviewedRevision(long targetId) throws SQLException {
        PackVersion target = dao.findVersionById(targetId).orElseThrow();
        var published = dao.findCurrentPublished();
        List<PackEntry> publishedEntries = published.isPresent()
                ? dao.findEntriesByVersionId(published.get().id())
                : List.of();
        return PackReleasePlanner.compare(
                published.orElse(null), target, publishedEntries,
                dao.findEntriesByVersionId(targetId)).revision();
    }

    private long createDraft(String version, long createdAt) throws SQLException {
        return dao.createDraft(version, "1.20.1", "forge", "47.4.20", null, createdAt);
    }

    private static PackEntryInput customEntry(String path, String sha1, long size) {
        return new PackEntryInput(
                path, PackEntryKind.CUSTOM, PackEntryPolicy.MANAGED, sha1, size,
                "https://cdn.example.com/" + Math.abs(path.hashCode()), null, null, null, null);
    }

    private static PackEntryInput platformEntry(String path) {
        return new PackEntryInput(
                path, PackEntryKind.PLATFORM, PackEntryPolicy.MANAGED, SHA1_A, 10,
                "https://cdn.example.com/platform.jar",
                "modrinth", "project", "Project", "version");
    }

    private void assertReleasedRowsRejectDirectMutation(long versionId, long entryId) {
        assertThrows(SQLException.class,
                () -> executeUpdate("UPDATE pack_version SET note='tampered' WHERE id=" + versionId));
        assertThrows(SQLException.class,
                () -> executeUpdate("UPDATE pack_entry SET size=size+1 WHERE id=" + entryId));
        assertThrows(SQLException.class,
                () -> executeUpdate("DELETE FROM pack_entry WHERE id=" + entryId));
        assertThrows(SQLException.class,
                () -> executeUpdate("DELETE FROM pack_version WHERE id=" + versionId));
    }

    private String entryInsertSql(long versionId, String path, String kind, String policy, String sha1,
                                  String platform, String projectId, String projectName,
                                  String externalVersionId) {
        return "INSERT INTO pack_entry "
                + "(version_id,path,kind,policy,sha1,size,download_url,platform,project_id,project_name,version_ext) "
                + "VALUES (" + versionId + ",'" + path + "','" + kind + "','" + policy + "','" + sha1
                + "',1,'https://cdn.example.com/file'," + sqlLiteral(platform) + "," + sqlLiteral(projectId)
                + "," + sqlLiteral(projectName) + "," + sqlLiteral(externalVersionId) + ")";
    }

    private static String sqlLiteral(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private void executeUpdate(String sql) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int countRows(String sql) throws SQLException {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
