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
        }

        DatabaseManager fresh = new DatabaseManager(freshFolder);
        DatabaseManager migrated = new DatabaseManager(migratedFolder);
        try {
            assertTrue(fresh.initialize().get(), "新库初始化应成功");
            assertTrue(migrated.initialize().get(), "v8 数据库迁移应成功");
            assertEquals(9, databaseVersion(migrated));
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
