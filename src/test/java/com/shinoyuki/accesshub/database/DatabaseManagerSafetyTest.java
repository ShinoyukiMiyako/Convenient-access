package com.shinoyuki.accesshub.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseManagerSafetyTest {

    @TempDir
    File tempDir;

    @Test
    void failedMultiStepMigrationRollsBackSchemaAndVersionTogether() throws Exception {
        File dbFile = new File(tempDir, "whitelist.db");
        createVersionFiveDatabase(dbFile, true);

        DatabaseManager failedManager = new DatabaseManager(tempDir);
        assertFalse(failedManager.initialize().get(), "冲突的中间表应使迁移明确失败");
        assertFalse(failedManager.isInitialized());
        failedManager.shutdown();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            assertEquals(5, scalar(statement, "SELECT MAX(version) FROM database_version"),
                    "失败迁移不得推进版本号");
            assertEquals(0, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('whitelist') WHERE name='qq'"),
                    "先执行成功的 5->6 ALTER 也必须随整个迁移回滚");
            statement.execute("DROP TABLE operation_log_new");
        }

        DatabaseManager retryManager = new DatabaseManager(tempDir);
        try {
            assertTrue(retryManager.initialize().get(), "移除外部冲突后迁移应能从 v5 完整重试");
            try (Connection connection = retryManager.getConnection();
                 Statement statement = connection.createStatement()) {
                assertEquals(9, scalar(statement, "SELECT MAX(version) FROM database_version"));
                assertEquals(1, scalar(statement,
                        "SELECT COUNT(*) FROM pragma_table_info('whitelist') WHERE name='qq'"));
                assertEquals(1, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='pack_version'"));
            }
        } finally {
            retryManager.shutdown();
        }
    }

    @Test
    void futureDatabaseVersionIsRejectedBeforeReadyState() throws Exception {
        File dbFile = new File(tempDir, "whitelist.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE database_version (version INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO database_version(version) VALUES (999)");
        }

        DatabaseManager manager = new DatabaseManager(tempDir);
        try {
            assertFalse(manager.initialize().get());
            assertFalse(manager.isInitialized());
            try (Connection connection = manager.getConnection();
                 Statement statement = connection.createStatement()) {
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='pack_version'"));
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void concurrentInitializeCallsShareOneAtomicInitialization() throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> initializationFutures = new ArrayList<>();
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                results.add(callers.submit(() -> {
                    start.await();
                    CompletableFuture<Boolean> initialization = manager.initialize();
                    synchronized (initializationFutures) {
                        initializationFutures.add(initialization);
                    }
                    return initialization.get();
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }

            CompletableFuture<Boolean> shared = initializationFutures.get(0);
            for (CompletableFuture<Boolean> initialization : initializationFutures) {
                assertSame(shared, initialization, "并发调用必须观察同一个初始化 Future");
            }
            assertTrue(manager.isInitialized());
            try (Connection connection = manager.getConnection();
                 Statement statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM database_version"));
                assertEquals(9, scalar(statement, "SELECT MAX(version) FROM database_version"));
            }
        } finally {
            callers.shutdownNow();
            manager.shutdown();
        }
    }

    @Test
    void sqlSplitterKeepsCaseTriggerBodyAndIgnoresCommentSemicolons() throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir);
        try {
            String script = """
                    -- 此处分号不能生成空语句 ;
                    CREATE TABLE t(x INTEGER);
                    CREATE TABLE audit(v TEXT);
                    /* 块注释中的 ; 同样不能切分 */
                    CREATE TRIGGER tr AFTER UPDATE ON t
                    BEGIN
                        SELECT CASE WHEN NEW.x = 1 THEN 1 ELSE 0 END;
                        INSERT INTO audit(v) VALUES('second;value');
                    END;
                    INSERT INTO t(x) VALUES(0);
                    UPDATE t SET x=1;
                    """;
            String[] statements = manager.splitSqlStatements(script);
            assertEquals(5, statements.length, "复杂触发器必须保留为一条完整 SQL");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                try (ResultSet resultSet = statement.executeQuery("SELECT v FROM audit")) {
                    assertTrue(resultSet.next());
                    assertEquals("second;value", resultSet.getString(1));
                    assertFalse(resultSet.next());
                }
            }
        } finally {
            manager.shutdown();
        }
    }

    private static void createVersionFiveDatabase(File dbFile, boolean createConflict) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE database_version (version INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO database_version(version) VALUES (5)");
            statement.execute("CREATE TABLE whitelist (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
            statement.execute("CREATE TABLE operation_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "operation_type VARCHAR(20) NOT NULL,"
                    + "target_uuid VARCHAR(36), target_name VARCHAR(16), operator_ip VARCHAR(45),"
                    + "operator_agent TEXT, request_data TEXT, response_status INTEGER,"
                    + "execution_time INTEGER, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "CONSTRAINT chk_operation_type CHECK (operation_type IN "
                    + "('ADD','REMOVE','QUERY','BATCH_ADD','BATCH_REMOVE','SYNC','UNAUTHORIZED_ACCESS'))) ");
            if (createConflict) {
                statement.execute("CREATE TABLE operation_log_new (conflict INTEGER)");
            }
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
