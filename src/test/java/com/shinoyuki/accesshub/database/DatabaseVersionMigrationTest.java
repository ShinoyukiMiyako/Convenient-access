package com.shinoyuki.accesshub.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * database_version 单行不变量 + 迁移自愈 (走真 SQLite).
 * 复现历史缺陷: version 作主键时 INSERT OR REPLACE 写新版本号不冲突而追加成多行,
 * 无序 LIMIT 1 读到陈旧值导致每次重启重复迁移。断言修复后:
 *  - 新库初始化为单行、版本 = CURRENT_VERSION;
 *  - 脏的多行 v3 库升级后折叠为单行最新版本且跑完全部迁移。
 * 删掉单行不变量 / MAX 读取, 脏库用例必挂。
 */
class DatabaseVersionMigrationTest {

    private static final int CURRENT_VERSION = 10;

    @TempDir
    File tempDir;

    private int rowCount(DatabaseManager db) throws SQLException {
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM database_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int version(DatabaseManager db) throws SQLException {
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(version) FROM database_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(DatabaseManager db, String name) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(DatabaseManager db, String table, String column) throws SQLException {
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * operation_log 的 CHECK 是否放行该操作类型。
     * DAO 把 SQLException 吞成 false, 漏配的类型只会静默丢日志而不报错, 故只能在此直接探测。
     */
    private boolean operationTypeAccepted(DatabaseManager db, String type) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO operation_log (operation_type, target_name) VALUES (?, 'probe')")) {
            ps.setString(1, type);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    void freshDbInitializesToSingleRowCurrentVersion() throws Exception {
        DatabaseManager db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "新库初始化应成功");
        assertEquals(1, rowCount(db), "version 表应恒为单行");
        assertEquals(CURRENT_VERSION, version(db), "新库应直接为最新版本");
        assertTrue(tableExists(db, "player_registration_codes"), "新库应建出注册码表");
        assertTrue(columnExists(db, "whitelist", "qq"), "新库 whitelist 应含 qq 列");
        assertTrue(tableExists(db, "admin_personal_codes"), "新库应建出个人识别码表");
        assertTrue(tableExists(db, "admin_qq_bindings"), "新库应建出 QQ 绑定表");
        assertTrue(tableExists(db, "pack_version"), "新库应建出整合包版本表");
        assertTrue(tableExists(db, "pack_entry"), "新库应建出整合包条目表");
        assertTrue(operationTypeAccepted(db, "RESET_AUTH"),
                "新库 operation_log 应放行 RESET_AUTH, 否则重置玩家认证的审计会被 CHECK 静默拒收");
        db.shutdown();
    }

    @Test
    void dirtyMultiRowV3DbSelfHealsToSingleRowCurrentVersion() throws Exception {
        // 预置"脏库": 模拟历史缺陷累积的多行 version 表 {1,2,3}, 且尚无 v4 注册码表
        File dbFile = new File(tempDir, "whitelist.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement s = c.createStatement()) {
            // 真实 v3 库自 v1 起就有 whitelist 与 operation_log 两张表:
            // migrate_5_to_6 是 ALTER 需前者; migrate_6_to_7 重建后者并迁数据, 故按当时的
            // 结构(旧 CHECK, 不含 SET_ACTIVE/GENCODE)预置, 并塞一行验证迁移不丢数据。
            s.execute("CREATE TABLE whitelist (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
            s.execute("CREATE TABLE operation_log ("
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
                    + "('ADD','REMOVE','QUERY','BATCH_ADD','BATCH_REMOVE','SYNC','UNAUTHORIZED_ACCESS')))");
            s.execute("INSERT INTO operation_log (operation_type, target_name) VALUES ('ADD', 'legacyrow')");
            s.execute("CREATE TABLE database_version (version INTEGER PRIMARY KEY)");
            s.execute("INSERT INTO database_version (version) VALUES (1)");
            s.execute("INSERT INTO database_version (version) VALUES (2)");
            s.execute("INSERT INTO database_version (version) VALUES (3)");
        }

        DatabaseManager db = new DatabaseManager(tempDir);
        assertTrue(db.initialize().get(), "脏库升级初始化应成功");
        // MAX 读到 3 (而非无序 LIMIT 1 的陈旧 1) -> 只跑 3->4; setDatabaseVersion 折叠为单行
        assertEquals(1, rowCount(db), "脏的多行 version 表应被折叠为单行");
        assertEquals(CURRENT_VERSION, version(db), "应升级到最新版本");
        assertTrue(tableExists(db, "player_registration_codes"), "3->4 迁移应建出注册码表");
        assertTrue(columnExists(db, "whitelist", "qq"), "5->6 迁移应给 whitelist 加 qq 列");
        assertTrue(tableExists(db, "admin_personal_codes"), "7->8 迁移应建出个人识别码表");
        assertTrue(tableExists(db, "admin_qq_bindings"), "7->8 迁移应建出 QQ 绑定表");
        assertTrue(tableExists(db, "pack_version"), "8->9 迁移应建出整合包版本表");
        assertTrue(tableExists(db, "pack_entry"), "8->9 迁移应建出整合包条目表");

        // 6->7 重建 operation_log: 既有数据必须原样迁过来, 且新类型此时应可写入
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM operation_log WHERE target_name='legacyrow'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "6->7 重建表不应丢失既有日志");
        }
        try (Connection c = db.getConnection();
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO operation_log (operation_type, target_name) VALUES ('SET_ACTIVE', 'x')");
            s.execute("INSERT INTO operation_log (operation_type, target_name) VALUES ('GENCODE', 'x')");
        }
        // 9->10 放宽 CHECK: 老库一路升上来也必须能写 RESET_AUTH
        assertTrue(operationTypeAccepted(db, "RESET_AUTH"), "9->10 迁移应放行 RESET_AUTH");

        db.shutdown();
    }
}
