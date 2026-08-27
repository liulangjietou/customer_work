package com.richard.fyoung.customerwork.infra.migration;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 客服端业务库全量表结构快照的生成与漂移门禁。
 *
 * <p>默认跑门禁：新建临时库跑完全部迁移，把导出结果与仓库内快照逐字比对，不一致直接红——
 * 加了迁移却忘了刷新快照会在这里被拦下。带 {@code -Dschema.snapshot.write=true} 时改为
 * 覆写快照文件，供 {@code scripts/export-schema-snapshot.sh} 调用。生成与校验共用同一段导出
 * 逻辑，不存在「脚本生成的和门禁期望的不是一回事」。</p>
 *
 * <p>刻意不从开发机的长期业务库导出：那个库被并行分支的迁移反复试跑过，沉积的结构无法与迁移
 * 产物区分，快照会把它们一并固化。MySQL 不可达或账号无建库权限时自动跳过。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkSchemaSnapshotTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");

    private static final String SNAPSHOT_PATH = "mysql/schema-snapshot/agent_scope_customer_work.sql";
    private static final String DATABASE_NAME = "agent_scope_customer_work";
    private static final int SOCKET_TIMEOUT_MS = 500;

    @Test
    void snapshotShouldStayInSyncWithMigrations() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过客服端库结构快照门禁");
        String database = "cw_snapshot_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过客服端库结构快照门禁");

        try (HikariDataSource dataSource = dataSource(database)) {
            migrate(dataSource, database);
            String exported;
            try (Connection connection = dataSource.getConnection()) {
                exported = SchemaSnapshotExporter.export(connection, header(connection));
            }

            Path snapshot = repositoryRoot().resolve(SNAPSHOT_PATH);
            if (SchemaSnapshotExporter.writeModeEnabled()) {
                Files.createDirectories(snapshot.getParent());
                Files.writeString(snapshot, exported, StandardCharsets.UTF_8);
                return;
            }

            assertTrue(Files.exists(snapshot),
                "快照文件缺失：" + SNAPSHOT_PATH + "，执行 scripts/export-schema-snapshot.sh 生成");
            String difference = SchemaSnapshotExporter.describeDifference(
                Files.readString(snapshot, StandardCharsets.UTF_8), exported);
            assertNull(difference,
                "客服端库结构快照与迁移产物不一致。新增或修改迁移后必须执行 "
                    + "scripts/export-schema-snapshot.sh 重新生成快照。\n" + difference);
        } finally {
            dropDatabase(database);
        }
    }

    /**
     * 构造快照文件头。
     *
     * <p>只放由迁移本身决定的信息：写入生成时间或机器名会让每次导出的文本都不同，门禁就永远是红的。</p>
     */
    private String header(Connection connection) throws Exception {
        return "-- ----------------------------------------------------------------------------\n"
            + "-- " + DATABASE_NAME + " 全量表结构快照（自动生成，请勿手工编辑）\n"
            + "-- ----------------------------------------------------------------------------\n"
            + "-- 生成方式：scripts/export-schema-snapshot.sh\n"
            + "--           新建临时空库执行 classpath:db/customerwork/migration 的全部迁移\n"
            + "--           （含 V2/V9 两个 Java 迁移）后逐表导出，自增当前值已抹除。\n"
            + "-- 对应版本：Flyway V" + lastAppliedVersion(connection) + "\n"
            + "-- 真源：customer-work-starter/src/main/resources/db/customerwork/migration/\n"
            + "--       + com.richard.fyoung.customerwork.infra.migration 下的 Java 迁移。\n"
            + "--       改结构一律新增迁移，改本文件不会生效。\n"
            + "-- 用途：结构查阅与全新建库。**不要对已有库执行**，这里没有 IF NOT EXISTS 保护。\n"
            + "-- 缺表说明：框架会话表 agentscope_sessions 不在本快照内——它由 AgentScope 的\n"
            + "--           MysqlSession 在首次连接时按内置 DDL 自建，不归 Flyway 管，因此也没有\n"
            + "--           迁移可以导出它。用本快照建库后由框架自行补上，不是遗漏。\n"
            + "-- 建库：CREATE DATABASE `" + DATABASE_NAME + "`\n"
            + "--         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
            + "-- COLLATE 说明：快照里同时出现 utf8mb4_0900_ai_ci 与 utf8mb4_unicode_ci 是既有状况，\n"
            + "--               不是导出错误。MySQL 8 的规则：建表语句写了 DEFAULT CHARSET=utf8mb4\n"
            + "--               却没写 COLLATE 时，用的是该字符集的默认 collation(utf8mb4_0900_ai_ci)\n"
            + "--               而非库的；显式写了 COLLATE 的按其声明；只有既不写 CHARSET 也不写\n"
            + "--               COLLATE 的少数表才继承上面的建库参数——所以导出必须固定按上面的参数\n"
            + "--               建库，换一套参数会让那几张表的输出跟着变。\n"
            + "-- ----------------------------------------------------------------------------\n"
            + "\n"
            + "SET NAMES utf8mb4;\n";
    }

    /** 取最后执行的那条迁移的版本号：空库全量迁移时它就是当前最高版本。 */
    private String lastAppliedVersion(Connection connection) throws Exception {
        String sql = "SELECT `version` FROM `flyway_schema_history` "
            + "WHERE `version` IS NOT NULL ORDER BY `installed_rank` DESC LIMIT 1";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : "unknown";
        }
    }

    private void migrate(HikariDataSource dataSource, String database) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(database);
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        return Files.isDirectory(workingDirectory.resolve("mysql"))
            ? workingDirectory : workingDirectory.getParent();
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), SOCKET_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean createDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoted(database)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            return true;
        } catch (Exception e) {
            dropDatabase(database);
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + quoted(database));
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 cw_snapshot_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", USERNAME, PASSWORD);
    }

    private HikariDataSource dataSource(String database) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("cw-schema-snapshot");
        return dataSource;
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
