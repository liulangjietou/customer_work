package com.richard.fyoung.customerwork.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 客服端业务库 Flyway 门控测试：同时覆盖空库首次初始化与旧初始化方式创建的存量库接管。
 *
 * <p>每次创建带随机后缀的隔离数据库，结束后只删除本用例创建的库，不接触默认业务库。MySQL 不可达或
 * 测试账号无建库权限时自动跳过。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkSchemaMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");

    @Test
    void migrate_shouldInitializeEmptyDatabaseAndReconcileLegacyDatabase() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端 Flyway 门控测试");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String emptyDatabase = "cw_flyway_empty_" + suffix;
        String legacyDatabase = "cw_flyway_legacy_" + suffix;
        String mirrorDatabase = "cw_flyway_mirror_" + suffix;
        assumeTrue(canCreateDatabases(emptyDatabase, legacyDatabase, mirrorDatabase),
            "MySQL 测试账号无建库权限，跳过");

        try {
            verifyEmptyDatabaseMigration(emptyDatabase);
            verifyLegacyDatabaseMigration(legacyDatabase);
            verifyCompleteMirrorAdoption(mirrorDatabase);
        } finally {
            dropDatabase(emptyDatabase);
            dropDatabase(legacyDatabase);
            dropDatabase(mirrorDatabase);
        }
    }

    private void verifyCompleteMirrorAdoption(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-mirror-test")) {
            Path workingDirectory = Path.of("").toAbsolutePath();
            Path repositoryRoot = Files.isDirectory(workingDirectory.resolve("mysql"))
                ? workingDirectory : workingDirectory.getParent();
            Path mirrorPath = repositoryRoot.resolve(
                "mysql/01-agent-scope-customer-work/customer-work-schema.sql");
            String mirrorSql = Files.readString(mirrorPath, StandardCharsets.UTF_8)
                .replaceFirst("(?is)CREATE DATABASE IF NOT EXISTS .*?;", "")
                .replaceFirst("(?is)USE\\s+`[^`]+`\\s*;", "");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ByteArrayResource(mirrorSql.getBytes(StandardCharsets.UTF_8)));
            populator.execute(dataSource);

            migrate(dataSource, database);
            migrate(dataSource, database);

            assertEquals(44, countBusinessTables(dataSource));
            assertTrue(columnExists(dataSource, "cw_dead_letter", "lease_owner"));
            assertTrue(columnExists(dataSource, "cw_outbox_message", "lease_owner"));
            assertEquals(1, countHistoryRows(dataSource), "完整镜像只应登记一次接管基线");
            assertEquals(1, countHistoryVersion(dataSource, "4"), "完整镜像应从当前版本接管");
        }
    }

    private void verifyEmptyDatabaseMigration(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-empty-test")) {
            migrate(dataSource, database);
            assertEquals(44, countBusinessTables(dataSource));
            assertEquals(4, countHistoryRows(dataSource));
            assertTrue(columnExists(dataSource, "cw_dead_letter", "lease_owner"));
            assertEquals(0, countHistoryVersion(dataSource, "0"), "空库不应写 baseline 记录");
        }
    }

    private void verifyLegacyDatabaseMigration(String database) throws Exception {
        try (HikariDataSource dataSource = dataSource(database, "flyway-legacy-test")) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/customerwork/migration/V1__baseline.sql"));
            populator.execute(dataSource);
            execute(dataSource, "ALTER TABLE `cw_user` DROP COLUMN `avatar_url`");
            execute(dataSource, "ALTER TABLE `cw_chat_attachment` DROP COLUMN `message_id`");

            migrate(dataSource, database);

            assertTrue(columnExists(dataSource, "cw_user", "avatar_url"));
            assertTrue(columnExists(dataSource, "cw_chat_attachment", "message_id"));
            // V4 给存量 cw_user 加的配额等级列：这张表是 V1 就建好的，加列只能靠迁移补
            assertTrue(columnExists(dataSource, "cw_user", "level_code"));
            assertEquals(44, countBusinessTables(dataSource));
            assertEquals(5, countHistoryRows(dataSource));
            assertEquals(1, countHistoryVersion(dataSource, "0"), "非空存量库必须先登记 baseline 0");
        }
    }

    private void migrate(HikariDataSource dataSource, String database) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(database);
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
    }

    private boolean canCreateDatabases(String... databases) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String database : databases) {
                statement.executeUpdate("CREATE DATABASE " + quoted(database)
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            return true;
        } catch (Exception e) {
            for (String database : databases) {
                dropDatabase(database);
            }
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoted(database));
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 cw_flyway_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", USERNAME, PASSWORD);
    }

    private HikariDataSource dataSource(String database, String poolName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName(poolName);
        return dataSource;
    }

    private int countBusinessTables(HikariDataSource dataSource) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name LIKE 'cw\\_%'";
        return queryInt(dataSource, sql);
    }

    private int countHistoryRows(HikariDataSource dataSource) throws Exception {
        return queryInt(dataSource, "SELECT COUNT(*) FROM flyway_schema_history");
    }

    private int countHistoryVersion(HikariDataSource dataSource, String version) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private boolean columnExists(HikariDataSource dataSource, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND column_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int queryInt(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void execute(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
