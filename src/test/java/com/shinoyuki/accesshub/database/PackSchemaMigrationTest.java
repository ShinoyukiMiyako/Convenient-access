package com.shinoyuki.accesshub.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackSchemaMigrationTest {

    @TempDir
    File tempDir;

    @Test
    void v8MigrationMatchesFreshPackSchema() throws Exception {
        File freshFolder = new File(tempDir, "fresh");
        File migratedFolder = new File(tempDir, "migrated");
        assertTrue(freshFolder.mkdirs());
        assertTrue(migratedFolder.mkdirs());

        File migratedFile = new File(migratedFolder, "whitelist.db");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + migratedFile.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE database_version (version INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO database_version (version) VALUES (8)");
            // 真实 v8 库自 v1 起就有 operation_log, 且 v7 已把它重建成含 SET_ACTIVE/GENCODE 的形态。
            // 9->10 要再次重建该表以放行 RESET_AUTH, 夹具缺表会让迁移失败在与 pack schema 无关的地方。
            statement.execute("CREATE TABLE operation_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "operation_type VARCHAR(20) NOT NULL,"
                    + "target_uuid VARCHAR(36),"
                    + "target_name VARCHAR(16),"
                    + "operator_ip VARCHAR(45),"
                    + "operator_agent TEXT,"
                    + "request_data TEXT,"
                    + "response_status INTEGER,"
                    + "execution_time INTEGER,"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "CONSTRAINT chk_operation_type CHECK (operation_type IN "
                    + "('ADD','REMOVE','QUERY','BATCH_ADD','BATCH_REMOVE','SYNC',"
                    + "'UNAUTHORIZED_ACCESS','SET_ACTIVE','GENCODE')))");
        }

        DatabaseManager fresh = new DatabaseManager(freshFolder);
        DatabaseManager migrated = new DatabaseManager(migratedFolder);
        try {
            assertTrue(fresh.initialize().get(), "新库初始化应成功");
            assertTrue(migrated.initialize().get(), "v8 数据库迁移应成功");
            assertEquals(10, databaseVersion(migrated));
            assertEquals(packSchema(fresh), packSchema(migrated),
                    "新库 schema 与 8->9 迁移结果必须完全一致");
            assertEquals(packTableMetadata(fresh, "pack_version"),
                    packTableMetadata(migrated, "pack_version"));
            assertEquals(packTableMetadata(fresh, "pack_entry"),
                    packTableMetadata(migrated, "pack_entry"));
            assertEquals(1, pragmaForeignKeys(fresh));
            assertEquals(1, pragmaForeignKeys(migrated));
        } finally {
            fresh.shutdown();
            migrated.shutdown();
        }
    }

    private static int databaseVersion(DatabaseManager manager) throws SQLException {
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT MAX(version) FROM database_version")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static List<String> packSchema(DatabaseManager manager) throws SQLException {
        String sql = "SELECT type, name, sql FROM sqlite_master "
                + "WHERE tbl_name IN ('pack_version', 'pack_entry') "
                + "AND type IN ('table', 'index', 'trigger') ORDER BY type, name";
        List<String> schema = new ArrayList<>();
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                schema.add(resultSet.getString("type") + "|" + resultSet.getString("name")
                        + "|" + resultSet.getString("sql"));
            }
        }
        return schema;
    }

    private static List<String> packTableMetadata(DatabaseManager manager, String table) throws SQLException {
        List<String> metadata = new ArrayList<>();
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                metadata.add(resultSet.getInt("cid") + "|" + resultSet.getString("name") + "|"
                        + resultSet.getString("type") + "|" + resultSet.getInt("notnull") + "|"
                        + resultSet.getString("dflt_value") + "|" + resultSet.getInt("pk"));
            }
        }
        return metadata;
    }

    private static int pragmaForeignKeys(DatabaseManager manager) throws SQLException {
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
