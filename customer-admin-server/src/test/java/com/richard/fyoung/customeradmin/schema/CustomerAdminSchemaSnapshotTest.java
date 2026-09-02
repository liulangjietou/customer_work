package com.richard.fyoung.customeradmin.schema;

import com.richard.fyoung.customerwork.infra.migration.SchemaSnapshotExporter;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 后台管理库全量表结构快照的生成与漂移门禁。
 *
 * <p>与客服端库那一侧同构：默认新建临时库跑完 {@code classpath:db/migration} 的全部迁移，
 * 把导出结果与仓库内快照逐字比对；带 {@code -Dschema.snapshot.write=true} 时改为覆写快照文件。
 * 导出逻辑复用 starter 的 {@code SchemaSnapshotExporter}，两个库的归一化规则只有一处。</p>
 *
 * <p>本库此前没有任何全量结构文件，建新库只能按序执行 {@code mysql/02-customer-admin/} 下近百个
 * 迁移副本。快照补上的是「当前结构长什么样」这个问题的直接答案，不替代那批副本的建库职责。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerAdminSchemaSnapshotTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    private static final String SNAPSHOT_PATH = "mysql/schema-snapshot/customer_admin.sql";
    private static final String DATABASE_NAME = "customer_admin";
    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final int SOCKET_TIMEOUT_MS = 500;

    /**
     * 本库的快照带系统种子数据段。
     *
     * <p>后台库的迁移自带菜单权限树、角色、默认租户与 admin 账号，缺了它们建出来的库<b>登录页
     * 就过不去</b>——快照虽自称能「全新建库」，建出来却没人进得去。客服端库那侧不开这个开关：
     * 它的演示业务数据归 {@code mysql/01} 的完整镜像管，引进快照只会多一份要清理的脏数据。</p>
     */
    private static final boolean INCLUDE_SEED_DATA = true;

    /** 登录一次至少要读到的四张表，缺任何一张按快照建出来的库都进不去。 */
    private static final java.util.List<String> LOGIN_CHAIN_TABLES =
        java.util.List.of("sys_tenant", "sys_user", "sys_role", "sys_user_role");

    @Test
    void snapshotShouldStayInSyncWithMigrations() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过后台库结构快照门禁");
        String database = "admin_snapshot_" + randomSuffix();
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过后台库结构快照门禁");

        try (HikariDataSource dataSource = dataSource(database)) {
            migrate(dataSource);
            String exported;
            try (Connection connection = dataSource.getConnection()) {
                exported = SchemaSnapshotExporter.export(connection, header(connection), INCLUDE_SEED_DATA);
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
                "后台库结构快照与迁移产物不一致。新增或修改迁移后必须执行 "
                    + "scripts/export-schema-snapshot.sh 重新生成快照。\n" + difference);
        } finally {
            dropDatabase(database);
        }
    }

    /**
     * 种子段的两条硬性质：导出可复现，且 admin 的登录链路真的在里面。
     *
     * <p><b>可复现</b>：同一个库连导两次必须逐字一致。种子行里只要混进一个随导出时刻变化的值
     * （{@code DEFAULT CURRENT_TIMESTAMP} 是最常见的一个，将来还可能是 {@code DEFAULT (UUID())}），
     * 上面那条漂移门禁就会变成「每跑必红」，而红的原因与任何人的改动都无关——那种门禁没人会信，
     * 几次之后就被当噪音跳过了。这条断言把那种故障提前变成一句指名道姓的失败。</p>
     *
     * <p><b>登录链路</b>：本快照存在的意义是「执行完能登进去」。漂移门禁只保证快照等于迁移产物，
     * 哪天迁移把 admin 种子挪走或改了绑定，快照会忠实地跟着变、门禁照样绿，而建出来的库谁也进不去。
     * 所以这里单独钉住那条链路：admin 账号启用、绑着超管角色、所属租户存在且激活，且这些行确实
     * 进了快照文本。</p>
     */
    @Test
    void seedDataShouldBeReproducibleAndCarryAdminLoginChain() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过后台库种子数据门禁");
        String first = migrateAndExport();
        assumeTrue(first != null, "MySQL 测试账号无建库权限，跳过后台库种子数据门禁");
        String second = migrateAndExport();
        assumeTrue(second != null, "MySQL 测试账号无建库权限，跳过后台库种子数据门禁");

        assertNull(SchemaSnapshotExporter.describeDifference(first, second),
            "两个独立建起来的库导出结果不一致：种子行里混进了随建库时刻变化的值，"
                + "会让上面那条漂移门禁变成「每跑必红」。给该列在 SchemaSnapshotExporter 里补跳过规则。");

        for (String table : LOGIN_CHAIN_TABLES) {
            assertTrue(first.contains("INSERT INTO `" + table + "`"),
                "快照缺少 " + table + " 的种子数据段，按它建出来的库无法登录");
        }
    }

    /**
     * 新建一个库跑完迁移并导出，顺带断言这一份产物里的 admin 登录链路完整。
     *
     * <p>两次调用必须建<b>两个不同的库</b>：种子行的 create_time 取的是迁移执行那一刻，同一个库
     * 连导两次当然一样，那种写法是恒真断言，照不出任何问题。只有两次独立建库才能让「时刻」真的
     * 不同，从而验出有没有随时刻变化的值漏进 INSERT。</p>
     *
     * @return 导出的快照文本；测试账号无建库权限时返回 {@code null}
     */
    private String migrateAndExport() throws Exception {
        String database = "admin_seed_" + randomSuffix();
        if (!createDatabase(database)) {
            return null;
        }
        try (HikariDataSource dataSource = dataSource(database)) {
            migrate(dataSource);
            try (Connection connection = dataSource.getConnection()) {
                assertEquals(1, adminLoginChainCount(connection),
                    "迁移没有留下可登录的超级管理员：需要 sys_user(admin, 启用) → sys_user_role → "
                        + "sys_role(super_admin) → sys_tenant(该用户所属租户, ACTIVE) 这条链路完整，"
                        + "否则按本快照建出来的库登录页过不去。");
                return SchemaSnapshotExporter.export(connection, header(connection), INCLUDE_SEED_DATA);
            }
        } finally {
            dropDatabase(database);
        }
    }

    /** 登录链路是否完整：admin 启用、绑超管角色、所属租户存在且激活，三者缺一不可。 */
    private int adminLoginChainCount(Connection connection) throws Exception {
        String sql = "SELECT COUNT(*) FROM `sys_user` u "
            + "JOIN `sys_user_role` ur ON ur.`user_id` = u.`id` "
            + "JOIN `sys_role` r ON r.`id` = ur.`role_id` "
            + "JOIN `sys_tenant` t ON t.`tenant_code` = u.`tenant_id` "
            + "WHERE u.`username` = 'admin' AND u.`status` = 1 AND u.`deleted` = 0 "
            + "AND r.`role_code` = 'super_admin' AND t.`status` = 'ACTIVE'";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
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
            + "--           新建临时空库执行 " + MIGRATION_LOCATION + " 的全部迁移后逐表导出，\n"
            + "--           自增当前值已抹除。\n"
            + "-- 对应版本：Flyway V" + lastAppliedVersion(connection) + "\n"
            + "-- 真源：customer-admin-server/src/main/resources/db/migration/\n"
            + "--       改结构一律新增迁移，改本文件不会生效。\n"
            + "-- 内容：全部表结构 + 迁移写入的系统种子数据（菜单权限树、角色、默认租户、admin 账号等）。\n"
            + "--       种子行里的 create_time/update_time 不进 INSERT，由建库时的默认值现场填充——\n"
            + "--       那两列的值是迁移执行那一秒，写死会让快照每次重新生成都不一样。\n"
            + "-- 用途：结构查阅与全新建库。执行完即可用 admin / admin 登录（超级管理员，\n"
            + "--       AuthService 检测到仍是初始密码会强制改密）。**不要对已有库执行**，\n"
            + "--       这里既没有 IF NOT EXISTS 保护，种子数据也会撞主键。\n"
            + "--       生产手工初始化仍按 mysql/02-customer-admin/ 的迁移副本顺序执行，\n"
            + "--       那条路径会留下 flyway_schema_history，之后能继续增量升级；本文件没有它，\n"
            + "--       建出来的库无法再走 Flyway 增量升级。\n"
            + "--       随迁移带出的示例配置（SQL 查询功能等）用 scripts/clear-demo-data.sh --public 清理。\n"
            + "-- 建库：CREATE DATABASE `" + DATABASE_NAME + "`\n"
            + "--         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
            + "-- COLLATE 说明：V100 全量对齐之后，本快照应只出现 utf8mb4_unicode_ci 一种排序规则；\n"
            + "--               再冒出 utf8mb4_0900_ai_ci 就是有人建表漏写了 COLLATE。MySQL 8 的规则：\n"
            + "--               建表语句写了 DEFAULT CHARSET=utf8mb4 却没写 COLLATE 时，用的是该字符集\n"
            + "--               的默认 collation(utf8mb4_0900_ai_ci) 而非库的；显式写了 COLLATE 的按其\n"
            + "--               声明；只有既不写 CHARSET 也不写 COLLATE 的表才继承上面的建库参数——\n"
            + "--               所以导出必须固定按上面的参数建库，换一套参数会让那几张表的输出跟着变。\n"
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

    /**
     * 按生产同款参数执行迁移。
     *
     * <p>{@code placeholderReplacement(false)} 不能省：V14 的种子数据含 {@code ${now-14d}}
     * （SqlParamValueResolver 的动态日期语法），开着占位符替换会让 Flyway 把它当自己的占位符，
     * 直接中断迁移。</p>
     */
    private void migrate(HikariDataSource dataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .baselineOnMigrate(true)
            .placeholderReplacement(false)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load()
            .migrate();
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
            // 清理失败不覆盖原始断言；随机库名不会影响业务库，残留可按 admin_snapshot_* 识别。
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
        dataSource.setPoolName("admin-schema-snapshot");
        return dataSource;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? defaultValue : value;
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
