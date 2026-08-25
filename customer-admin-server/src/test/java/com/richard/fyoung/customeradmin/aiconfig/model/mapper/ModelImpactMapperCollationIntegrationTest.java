package com.richard.fyoung.customeradmin.aiconfig.model.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactItemVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 治理详情影响预检的真库回归。
 *
 * <p>findImpacts 把 bigint 主键 CAST 成字符串后与 ai_runtime_publish_task 的 varchar 主键放进同一个 UNION 列，
 * CAST 结果取 collation_connection（MySQL 8 默认库上是 utf8mb4_0900_ai_ci）而表列是 utf8mb4_unicode_ci，
 * 两侧可强制性都是隐式，MySQL 直接抛 1271。此前这条 SQL 只有单测覆盖 Service 编排、从未在真库执行过，
 * 因此整条治理详情链路在页面上表现为「系统繁忙」。
 */
class ModelImpactMapperCollationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = env("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = env("ADMIN_MYSQL_PASSWORD", "root");

    private static final long MODEL_ID = 9001L;
    private static final long AGENT_ID = 9002L;
    private static final String TENANT = "tenant-impact";

    @Test
    void findImpactsShouldRunOnRealDatabaseWithMismatchedConnectionCollation() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过治理详情影响预检真库测试");
        String database = "admin_impact_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过治理详情影响预检真库测试");

        PooledDataSource dataSource = null;
        try {
            migrate(database);
            try (Connection connection = connection(database)) {
                // 前提必须成立：连接排序规则与表排序规则不同，否则这条用例根本照不出线上的失败形态。
                assertEquals("utf8mb4_0900_ai_ci", connectionCollation(connection));
                assertEquals("utf8mb4_unicode_ci",
                    columnCollation(connection, "ai_runtime_publish_task", "id"));
                assertNotEquals(connectionCollation(connection),
                    columnCollation(connection, "ai_runtime_publish_task", "id"));

                // 不带 COLLATE 的等价写法此刻必然抛 1271，证明 Mapper 里的显式声明不是多余的。
                SQLException error = assertThrows(SQLException.class, () -> castVersusColumnUnion(connection));
                assertEquals(1271, error.getErrorCode());

                seedImpactGraph(connection);
            }

            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database), USERNAME, PASSWORD);
            SqlSessionFactory factory = sqlSessionFactory(dataSource);
            try (SqlSession session = factory.openSession()) {
                ModelImpactMapper mapper = session.getMapper(ModelImpactMapper.class);

                // 控制面共享模型预检：不带租户，跨全部租户扫描。
                assertImpactGraph(mapper.findImpacts(MODEL_ID, null));
                // 普通租户视角：显式带租户。
                assertImpactGraph(mapper.findImpacts(MODEL_ID, TENANT));
                // 无引用的模型必须返回空集而不是报错。
                assertTrue(mapper.findImpacts(-1L, TENANT).isEmpty());
            }
        } finally {
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            dropDatabase(database);
        }
    }

    /** UNION 各列都要落到同一排序规则，任一分支漏写 COLLATE 都会在这里失败。 */
    private void assertImpactGraph(List<ModelImpactItemVO> items) {
        Map<String, ModelImpactItemVO> byType = items.stream()
            .collect(Collectors.toMap(ModelImpactItemVO::getResourceType, Function.identity()));
        assertEquals(2, byType.size(), "应命中 Agent 主模型引用与运行时投递任务两类资源");

        ModelImpactItemVO agent = byType.get("AGENT");
        assertNotNull(agent, "缺少 Agent 引用");
        assertEquals(TENANT, agent.getTenantId());
        assertEquals("PRIMARY_MODEL", agent.getRelationType());
        assertEquals(String.valueOf(AGENT_ID), agent.getResourceId());
        assertEquals("agent-impact", agent.getResourceCode());
        assertEquals("1", agent.getStatus());
        assertTrue(agent.getBlocking());

        ModelImpactItemVO task = byType.get("PUBLISH_TASK");
        assertNotNull(task, "缺少运行时投递任务引用");
        assertEquals("RUNTIME_DELIVERY", task.getRelationType());
        assertEquals("task-impact", task.getResourceId());
        assertEquals("revision-impact", task.getResourceCode());
        assertEquals("PUBLISHED", task.getStatus());
        assertTrue(task.getBlocking());
    }

    private void seedImpactGraph(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ai_agent "
                + "(id, tenant_id, agent_code, agent_name, model_id, status, deleted) VALUES "
                + "(" + AGENT_ID + ", '" + TENANT + "', 'agent-impact', '影响预检智能体', " + MODEL_ID + ", 1, 0)");
            statement.executeUpdate("INSERT INTO ai_runtime_publish_task "
                + "(id, seq, tenant_id, target_id, revision, status, next_attempt_at_ms, "
                + "created_at_ms, updated_at_ms) VALUES "
                + "('task-impact', 1, '" + TENANT + "', " + AGENT_ID + ", 'revision-impact', 'PUBLISHED', 0, 1, 1)");
        }
    }

    /** findImpacts 里 resource_id 一列的最小形态：CAST 出来的字符串与 varchar 主键放在同一 UNION 位置。 */
    private void castVersusColumnUnion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "SELECT CAST(id AS CHAR) AS resource_id FROM ai_agent "
                     + "UNION ALL SELECT id FROM ai_runtime_publish_task")) {
            resultSet.next();
        }
    }

    private SqlSessionFactory sqlSessionFactory(PooledDataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("model-impact-collation-test",
            new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/ModelImpactMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML 不存在: " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private String connectionCollation(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@collation_connection")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private String columnCollation(Connection connection, String table, String column) throws SQLException {
        String sql = "SELECT COLLATION_NAME FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private void migrate(String database) {
        Flyway.configure()
            .dataSource(jdbcUrl(database), USERNAME, PASSWORD)
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .load()
            .migrate();
    }

    private boolean createDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoted(database)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
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
            // 清理失败不覆盖原始断言；随机库名可按 admin_impact_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
    }

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
    }

    /** 与 application.yml 的连接参数保持一致，否则测出来的排序规则组合与线上不是一回事。 */
    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1_500);
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

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
