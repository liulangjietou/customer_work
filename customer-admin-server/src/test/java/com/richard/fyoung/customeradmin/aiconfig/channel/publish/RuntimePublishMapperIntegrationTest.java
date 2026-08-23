package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 MySQL 验证发布顺序、租约行锁和 ACK 乱序单调性。 */
class RuntimePublishMapperIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    @Test
    void mapperXml_shouldKeepOrderingFencingAndMonotonicAckContracts() throws Exception {
        String publishSql = Files.readString(
            Path.of("src/main/resources/mapper/RuntimePublishTaskMapper.xml"), StandardCharsets.UTF_8);
        String ackSql = Files.readString(
            Path.of("src/main/resources/mapper/RuntimeConfigAckMapper.xml"), StandardCharsets.UTF_8);

        assertTrue(publishSql.contains("older.status IN ('PENDING', 'PROCESSING')"));
        assertTrue(publishSql.contains("older.data_id &lt;=&gt; candidate.data_id"));
        assertTrue(publishSql.contains("older.group_name &lt;=&gt; candidate.group_name"));
        assertFalse(publishSql.contains("older.target_id = candidate.target_id"));
        assertTrue(publishSql.contains("newer.seq &gt; candidate.seq"));
        assertTrue(publishSql.contains("newer.group_name &lt;=&gt; candidate.group_name"));
        assertFalse(publishSql.contains("newer.target_id = candidate.target_id"));
        assertTrue(publishSql.contains("AND newer.id IS NULL"));
        assertTrue(publishSql.contains(
            "CASE WHEN newer.id IS NULL THEN 'FAILED' ELSE 'SUPERSEDED' END"));
        assertTrue(publishSql.contains("FOR UPDATE"));
        assertTrue(publishSql.contains("id=\"lockByRevisionForAck\""));
        assertTrue(publishSql.contains("lease_until_ms &gt;= #{nowMs}"));
        assertTrue(publishSql.contains("status &lt;&gt; 'APPLIED' OR #{status} = 'APPLIED'"));
        assertTrue(ackSql.contains("VALUES(applied_at_ms) &gt; applied_at_ms"));
        assertTrue(ackSql.contains("VALUES(status) = 'APPLIED' AND status &lt;&gt; 'APPLIED'"));
    }

    @Test
    void mapperSql_shouldSerializeSameNacosKeyAcrossAgentsAndIgnoreStaleAck() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过运行时发布 Mapper 集成测试");
        String database = "runtime_publish_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        PooledDataSource dataSource = null;
        try {
            createDatabase(database);
            created = true;
            createSchemaAndTasks(database);
            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            SqlSessionFactory factory = sqlSessionFactory(dataSource);

            try (SqlSession session = factory.openSession(true)) {
                RuntimePublishTaskMapper taskMapper = session.getMapper(RuntimePublishTaskMapper.class);
                List<RuntimePublishTask> initial = taskMapper.findDueCandidates(1_000L, 10);
                assertEquals(List.of("task-oldest", "task-other-data", "task-other-group"),
                    initial.stream().map(RuntimePublishTask::getId).toList());
                assertEquals(0, taskMapper.claim("task-newer", "worker-a", 1_000L, 2_000L));

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate(
                        "UPDATE ai_runtime_publish_task SET status = 'PUBLISHED' WHERE id = 'task-oldest'");
                }
                assertEquals(List.of("task-newer", "task-other-data", "task-other-group"),
                    taskMapper.findDueCandidates(1_000L, 10).stream()
                    .map(RuntimePublishTask::getId).toList());

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate(
                        "UPDATE ai_runtime_publish_task SET status = 'PARTIAL' WHERE id = 'task-oldest'");
                }
                assertEquals(List.of("task-newer", "task-other-data", "task-other-group"),
                    taskMapper.findDueCandidates(1_000L, 10).stream()
                        .map(RuntimePublishTask::getId).toList());

                try (Statement statement = session.getConnection().createStatement()) {
                    statement.executeUpdate("UPDATE ai_runtime_publish_task "
                        + "SET status = 'BLOCKED', gate_status = 'BLOCKED' WHERE id = 'task-oldest'");
                }
                assertEquals(List.of("task-newer", "task-other-data", "task-other-group"),
                    taskMapper.findDueCandidates(1_000L, 10).stream()
                        .map(RuntimePublishTask::getId).toList());
                assertEquals(0, taskMapper.retryGateBlocked("task-oldest", "tenant-a", 1_000L));
                assertEquals(0, taskMapper.overrideGateBlocked("task-oldest", "tenant-a", 99L, 1_000L));

                assertEquals(1, taskMapper.claim("task-newer", "worker-a", 1_000L, 2_000L));
                assertNotNull(taskMapper.lockLeaseForPublish("task-newer", "worker-a", 1_500L));
                assertNull(taskMapper.lockLeaseForPublish("task-newer", "stale-worker", 1_500L));
                assertEquals(1, taskMapper.retryGateBlocked(
                    "task-latest-blocked", "tenant-a", 1_000L));
                assertEquals(1, taskMapper.overrideGateBlocked(
                    "task-latest-override", "tenant-a", 88L, 1_000L));
                assertEquals(1, taskMapper.markContentChangedTerminal(
                    "task-content-old", "worker-z", "content changed", 1_000L));
                assertTaskStatus(session, "task-content-old", "SUPERSEDED");
                assertEquals(1, taskMapper.markContentChangedTerminal(
                    "task-content-orphan", "worker-z", "content changed", 1_000L));
                assertTaskStatus(session, "task-content-orphan", "FAILED");

                RuntimeConfigAckMapper ackMapper = session.getMapper(RuntimeConfigAckMapper.class);
                ackMapper.upsert(ack("APPLIED", "new applied", 200L));
                ackMapper.upsert(ack("REJECTED", "delayed reject", 100L));
                assertAck(session, "APPLIED", "new applied", 200L);

                ackMapper.upsert(ack("REJECTED", "new reject", 300L));
                assertAck(session, "REJECTED", "new reject", 300L);
                ackMapper.upsert(ack("APPLIED", "same-time applied wins", 300L));
                assertAck(session, "APPLIED", "same-time applied wins", 300L);
            }
        } finally {
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            if (created) {
                dropDatabase(database);
            }
        }
    }

    @Test
    void mapperSql_shouldRejectOverlappingClaimFromDifferentAgentSharingNacosKey() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过运行时发布 Mapper 并发测试");
        String database = "runtime_publish_race_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        PooledDataSource dataSource = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            createDatabase(database);
            created = true;
            createSchemaAndTasks(database);
            dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl(database),
                USERNAME, PASSWORD);
            SqlSessionFactory factory = sqlSessionFactory(dataSource);

            try (SqlSession oldestSession = factory.openSession(false)) {
                RuntimePublishTaskMapper oldestMapper = oldestSession.getMapper(RuntimePublishTaskMapper.class);
                assertEquals(1, oldestMapper.claim("task-oldest", "worker-old", 1_000L, 2_000L));

                CountDownLatch started = new CountDownLatch(1);
                Future<Integer> newerClaim = executor.submit(() -> {
                    try (SqlSession newerSession = factory.openSession(true)) {
                        started.countDown();
                        return newerSession.getMapper(RuntimePublishTaskMapper.class)
                            .claim("task-newer", "worker-new", 1_000L, 2_000L);
                    }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));

                Integer resultBeforeCommit = null;
                try {
                    resultBeforeCommit = newerClaim.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException expectedLockWait) {
                    // 多表 UPDATE 可能等待最旧任务的未提交行锁；提交后仍必须拒绝新任务。
                }
                oldestSession.commit();
                int newerChanged = resultBeforeCommit == null
                    ? newerClaim.get(5, TimeUnit.SECONDS) : resultBeforeCommit;
                assertEquals(0, newerChanged,
                    "不同 Agent 写同一 tenant/dataId/group 时不得并发 claim");
            }
        } finally {
            executor.shutdownNow();
            if (dataSource != null) {
                dataSource.forceCloseAll();
            }
            if (created) {
                dropDatabase(database);
            }
        }
    }

    private SqlSessionFactory sqlSessionFactory(PooledDataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("runtime-publish-test",
            new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        parseMapper(configuration, "mapper/RuntimePublishTaskMapper.xml");
        parseMapper(configuration, "mapper/RuntimeConfigAckMapper.xml");
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private void parseMapper(MybatisConfiguration configuration, String resource) throws Exception {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML 不存在: " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private RuntimeConfigAckEntity ack(String status, String reason, long appliedAtMs) {
        RuntimeConfigAckEntity ack = new RuntimeConfigAckEntity();
        ack.setTenantId("tenant-a");
        ack.setRevision("revision-1");
        ack.setContentHash("hash-1");
        ack.setInstanceId("pod-1");
        ack.setStatus(status);
        ack.setReason(reason);
        ack.setAppliedAtMs(appliedAtMs);
        ack.setCreatedAtMs(1_000L);
        ack.setUpdatedAtMs(1_000L + appliedAtMs);
        return ack;
    }

    private void assertAck(SqlSession session, String status, String reason, long appliedAtMs) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT status, reason, applied_at_ms FROM ai_runtime_config_ack "
                     + "WHERE tenant_id = 'tenant-a' AND revision = 'revision-1' AND instance_id = 'pod-1'")) {
            result.next();
            assertEquals(status, result.getString("status"));
            assertEquals(reason, result.getString("reason"));
            assertEquals(appliedAtMs, result.getLong("applied_at_ms"));
        }
    }

    private void assertTaskStatus(SqlSession session, String taskId, String expectedStatus) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT status FROM ai_runtime_publish_task WHERE id = '" + taskId + "'")) {
            result.next();
            assertEquals(expectedStatus, result.getString("status"));
        }
    }

    private void createDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private void createSchemaAndTasks(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE ai_runtime_publish_task (
                    id VARCHAR(64) PRIMARY KEY,
                    seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
                    tenant_id VARCHAR(64) NOT NULL,
                    target_id BIGINT NOT NULL,
                    data_id VARCHAR(255),
                    group_name VARCHAR(128),
                    status VARCHAR(32) NOT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at_ms BIGINT NOT NULL,
                    lease_owner VARCHAR(128),
                    lease_until_ms BIGINT NOT NULL,
                    last_error VARCHAR(1000),
                    gate_status VARCHAR(16),
                    gate_eval_run_ids_json TEXT,
                    gate_decision_json TEXT,
                    gate_evaluated_at_ms BIGINT,
                    gate_override_id BIGINT,
                    updated_at_ms BIGINT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE ai_runtime_config_ack (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    tenant_id VARCHAR(64) NOT NULL,
                    revision VARCHAR(64) NOT NULL,
                    content_hash VARCHAR(128) NOT NULL,
                    instance_id VARCHAR(128) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    reason VARCHAR(1000),
                    applied_at_ms BIGINT NOT NULL,
                    created_at_ms BIGINT NOT NULL,
                    updated_at_ms BIGINT NOT NULL,
                    UNIQUE KEY uk_runtime_ack (tenant_id, revision, instance_id)
                )
                """);
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-oldest', 'tenant-a', 42, 'data-a', 'group-1', 'PENDING', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-newer', 'tenant-a', 99, 'data-a', 'group-1', 'PENDING', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-other-data', 'tenant-a', 42, 'data-b', 'group-1', 'PENDING', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-other-group', 'tenant-a', 42, 'data-a', 'group-2', 'PENDING', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, gate_status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-latest-blocked', 'tenant-a', 43, 'data-c', 'group-1', "
                + "'BLOCKED', 'BLOCKED', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, gate_status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-latest-override', 'tenant-a', 44, 'data-d', 'group-1', "
                + "'BLOCKED', 'BLOCKED', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, attempts, next_attempt_at_ms, "
                + "lease_owner, lease_until_ms, updated_at_ms) VALUES "
                + "('task-content-old', 'tenant-a', 50, 'data-e', 'group-1', 'PROCESSING', 0, 0, "
                + "'worker-z', 2000, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, next_attempt_at_ms, "
                + "lease_until_ms, updated_at_ms) VALUES "
                + "('task-content-new', 'tenant-a', 51, 'data-e', 'group-1', 'PUBLISHED', 0, 0, 0)");
            statement.execute("INSERT INTO ai_runtime_publish_task "
                + "(id, tenant_id, target_id, data_id, group_name, status, attempts, next_attempt_at_ms, "
                + "lease_owner, lease_until_ms, updated_at_ms) VALUES "
                + "('task-content-orphan', 'tenant-a', 52, 'data-f', 'group-1', 'PROCESSING', 0, 0, "
                + "'worker-z', 2000, 0)");
        }
    }

    private void dropDatabase(String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
