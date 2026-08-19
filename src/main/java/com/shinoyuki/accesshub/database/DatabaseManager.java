package com.shinoyuki.accesshub.database;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite数据库管理器
 * 负责数据库连接池管理、初始化、迁移和事务管理
 *
 * v2 起通过外部注入的 dataFolder (Forge mod 由 FMLPaths.GAMEDIR.resolve(modId) 提供) 定位数据库文件,
 * 不再依赖 Bukkit Plugin.getDataFolder().
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    static {
        // 显式注册 SQLite 驱动。Forge 的 SecureJar/模块层对 shade 进来的子 jar 内
        // META-INF/services/java.sql.Driver 的 ServiceLoader 自动发现不可靠, 必须手动注册,
        // 否则 DriverManager.getConnection("jdbc:sqlite:...") 抛 "No suitable driver"。
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                "SQLite JDBC 驱动未在 classpath (检查 shadowJar 是否打包 org.xerial:sqlite-jdbc)");
        }
    }

    private final File dataFolder;
    private final String databasePath;
    private final ExecutorService executorService;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Object initializationMonitor = new Object();
    private CompletableFuture<Boolean> initializationFuture;

    // 数据库版本
    private static final int CURRENT_VERSION = 10; // v10: operation_log 放行 RESET_AUTH

    public DatabaseManager(File dataFolder) {
        this.dataFolder = dataFolder;
        this.databasePath = new File(dataFolder, "whitelist.db").getAbsolutePath();
        // 增加线程池大小以处理更多并发数据库操作
        this.executorService = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "DatabaseManager-Thread");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 初始化数据库
     */
    public CompletableFuture<Boolean> initialize() {
        synchronized (initializationMonitor) {
            if (initializationFuture == null) {
                initializationFuture = CompletableFuture.supplyAsync(this::initializeDatabase, executorService);
            }
            return initializationFuture;
        }
    }

    private boolean initializeDatabase() {
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            try (Connection connection = getConnection()) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode = WAL");
                    stmt.execute("PRAGMA synchronous = NORMAL");
                    stmt.execute("PRAGMA cache_size = 10000");
                    stmt.execute("PRAGMA temp_store = MEMORY");
                    stmt.execute("PRAGMA wal_autocheckpoint = 1000");
                }

                connection.setAutoCommit(false);
                int currentVersion;
                try {
                    currentVersion = getDatabaseVersion(connection);
                    if (currentVersion > CURRENT_VERSION) {
                        throw new SQLException("数据库版本 " + currentVersion
                                + " 高于程序支持的版本 " + CURRENT_VERSION);
                    }
                    if (currentVersion == 0) {
                        createTables(connection);
                        setDatabaseVersion(connection, CURRENT_VERSION);
                    } else if (currentVersion < CURRENT_VERSION) {
                        migrateDatabaseFrom(connection, currentVersion);
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        exception.addSuppressed(rollbackError);
                    }
                    throw exception;
                }

                if (currentVersion == 0) {
                    logger.info("数据库初始化完成，版本: {}", CURRENT_VERSION);
                } else if (currentVersion < CURRENT_VERSION) {
                    logger.info("数据库升级完成，从版本 {} 升级到 {}", currentVersion, CURRENT_VERSION);
                }
                initialized.set(true);
                return true;
            }
        } catch (Exception e) {
            logger.error("数据库初始化失败", e);
            return false;
        }
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement stmt = conn.createStatement()) {
            // SQLite 外键开关是连接级配置，每个 DAO 连接都必须显式启用。
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA recursive_triggers = ON");
            stmt.execute("PRAGMA busy_timeout = 30000"); // 30秒超时
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return conn;
    }
    
    /**
     * 异步执行数据库操作
     */
    public <T> CompletableFuture<T> executeAsync(DatabaseOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                return operation.execute(connection);
            } catch (Exception e) {
                logger.error("数据库操作执行失败", e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }
    
    /**
     * 异步执行事务操作
     */
    public <T> CompletableFuture<T> executeTransactionAsync(DatabaseOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = operation.execute(connection);
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                logger.error("数据库事务执行失败", e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }
    
    /**
     * 创建所有数据库表
     */
    private void createTables(Connection connection) throws SQLException {
        logger.info("开始创建数据库表...");
        
        // 读取SQL脚本并执行（简化版，只保留必要的表）
        String[] sqlScripts = {
            "schema/whitelist.sql",
            "schema/sync_tasks.sql", 
            "schema/operation_log.sql",
            "schema/registration_tokens.sql",
            "schema/admin_users.sql",
            "schema/admin_sessions.sql",
            "schema/auth_logs.sql",
            "schema/player_auth.sql",
            "schema/player_registration_codes.sql",
            "schema/device_keys.sql",
            "schema/admin_personal_codes.sql",
            "schema/admin_qq_bindings.sql",
            "schema/pack.sql",
            "schema/indexes.sql"
        };
        
        for (String scriptPath : sqlScripts) {
            executeScript(connection, scriptPath);
        }
        
        logger.info("数据库表创建完成，共执行 {} 个脚本", sqlScripts.length);
        
        // 插入初始数据
        insertInitialData(connection);
    }
    
    /**
     * 执行SQL脚本
     */
    private void executeScript(Connection connection, String scriptPath) throws SQLException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scriptPath)) {
            if (inputStream == null) {
                throw new SQLException("SQL脚本文件不存在: " + scriptPath);
            }
            
            String sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            String[] statements = splitSqlStatements(sql);
            
            try (Statement stmt = connection.createStatement()) {
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        logger.debug("执行SQL: {}", trimmed);
                        stmt.execute(trimmed);
                    }
                }
            }
            
            logger.info("成功执行SQL脚本: {}", scriptPath);
        } catch (IOException e) {
            logger.error("读取SQL脚本失败: {}", scriptPath, e);
            throw new SQLException("读取SQL脚本失败", e);
        } catch (SQLException e) {
            logger.error("执行SQL脚本失败: {}", scriptPath, e);
            throw e;
        }
    }
    
    /**
     * 智能分割SQL语句（处理字符串中的分号）
     */
    String[] splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringChar = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                    currentStatement.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    currentStatement.append(' ');
                    i++;
                }
                continue;
            }
            if (inString) {
                currentStatement.append(c);
                if (c == stringChar) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == stringChar) {
                        currentStatement.append(sql.charAt(++i));
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                stringChar = c;
                currentStatement.append(c);
                continue;
            }
            if (c == ';') {
                String statement = currentStatement.toString().trim();
                if (isIncompleteTrigger(statement)) {
                    currentStatement.append(c);
                    continue;
                }
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                currentStatement = new StringBuilder();
                continue;
            }

            currentStatement.append(c);
        }

        // 添加最后一个语句
        String lastStatement = currentStatement.toString().trim();
        if (!lastStatement.isEmpty()) {
            statements.add(lastStatement);
        }
        
        return statements.toArray(new String[0]);
    }

    /** CREATE TRIGGER 的 BEGIN/END 块内也包含分号，不能按普通语句提前切开。 */
    private boolean isIncompleteTrigger(String statement) {
        List<String> tokens = sqlTokens(statement);
        if (tokens.isEmpty() || !"CREATE".equals(tokens.get(0))) {
            return false;
        }
        int index = 1;
        if (index < tokens.size()
                && ("TEMP".equals(tokens.get(index)) || "TEMPORARY".equals(tokens.get(index)))) {
            index++;
        }
        if (index >= tokens.size() || !"TRIGGER".equals(tokens.get(index))) {
            return false;
        }

        int blockDepth = 0;
        int caseDepth = 0;
        for (index++; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ("CASE".equals(token)) {
                caseDepth++;
            } else if ("BEGIN".equals(token) && caseDepth == 0) {
                blockDepth++;
            } else if ("END".equals(token)) {
                if (caseDepth > 0) {
                    caseDepth--;
                } else if (blockDepth > 0) {
                    blockDepth--;
                }
            }
        }
        return blockDepth > 0;
    }

    private List<String> sqlTokens(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean inString = false;
        char stringChar = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == stringChar) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == stringChar) {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                flushSqlToken(tokens, token);
                inString = true;
                stringChar = c;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                token.append(Character.toUpperCase(c));
            } else {
                flushSqlToken(tokens, token);
            }
        }
        flushSqlToken(tokens, token);
        return tokens;
    }

    private void flushSqlToken(List<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString().toUpperCase(Locale.ROOT));
            token.setLength(0);
        }
    }
    
    /**
     * 插入初始数据（简化版）
     */
    private void insertInitialData(Connection connection) throws SQLException {
        logger.debug("简化版系统无需插入初始数据");
        // 简化版系统不需要插入角色等初始数据
    }
    
    /**
     * 获取数据库版本
     */
    private int getDatabaseVersion(Connection connection) throws SQLException {
        // 检查版本表是否存在
        String checkTable = """
            SELECT name FROM sqlite_master 
            WHERE type='table' AND name='database_version'
        """;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(checkTable)) {
            
            if (!rs.next()) {
                // 版本表不存在，创建它
                String createVersionTable = """
                    CREATE TABLE database_version (
                        version INTEGER PRIMARY KEY
                    )
                """;
                stmt.execute(createVersionTable);
                return 0;
            }
        }
        
        // 取最高版本号兜底: 历史遗留缺陷曾使 database_version 累积多行 (version 作主键,
        // INSERT OR REPLACE 写新版本号不冲突而追加成行), 无序 LIMIT 1 会读到陈旧值导致每次启动
        // 重复跑迁移。用 MAX 读真实最新版本规避; setDatabaseVersion 已改为单行不变量。
        String getVersion = "SELECT MAX(version) AS version FROM database_version";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(getVersion)) {

            if (rs.next()) {
                int v = rs.getInt("version");
                return rs.wasNull() ? 0 : v;
            } else {
                return 0;
            }
        }
    }

    /**
     * 设置数据库版本. 单行不变量: 先清空再写入, 避免 version 作主键时 INSERT OR REPLACE
     * 因版本号不冲突而追加成多行 (历史遗留缺陷, 随 CURRENT_VERSION 提升被激活)。
     */
    private void setDatabaseVersion(Connection connection, int version) throws SQLException {
        try (Statement del = connection.createStatement()) {
            del.executeUpdate("DELETE FROM database_version");
        }
        String sql = "INSERT INTO database_version (version) VALUES (?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, version);
            stmt.executeUpdate();
        }
    }
    
    /**
     * 数据库迁移
     */
    private void migrateDatabaseFrom(Connection connection, int fromVersion) throws SQLException {
        logger.info("开始数据库迁移，从版本 {} 到版本 {}", fromVersion, CURRENT_VERSION);
        
        for (int version = fromVersion; version < CURRENT_VERSION; version++) {
            String migrationScript = "migrations/migrate_" + version + "_to_" + (version + 1) + ".sql";
            // 迁移必需脚本缺失时拒绝静默推进版本号。
            if (getClass().getClassLoader().getResource(migrationScript) == null) {
                throw new SQLException("迁移脚本缺失, 拒绝静默跳步: " + migrationScript);
            }
            executeScript(connection, migrationScript);
            setDatabaseVersion(connection, version + 1);
            logger.info("数据库迁移完成: {} -> {}", version, version + 1);
        }
    }
    
    /**
     * 检查数据库是否已初始化
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * 关闭数据库管理器
     */
    public void shutdown() {
        executorService.shutdown();
        logger.info("数据库管理器已关闭");
    }
    
    /**
     * 数据库操作接口
     */
    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
