package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.data.order.OrderDirectoryService;
import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** HMAC 验签、Controller 调度、真实目录 SQL 联调；显式覆盖租户插件关闭的默认配置。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentOrderBusinessScopeIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String SECRET = "owned-agent-order-test-secret";
    private static final String TENANT_A = "tenant-agent-order-a";
    private static final String TENANT_B = "tenant-agent-order-b";
    private static final String BASE = "/api/customer/agent/orders";
    private final String database = "cw_agent_order_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicReference<Runnable> beforeUpdate = new AtomicReference<>();
    private boolean created;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private WebTestClient withPlugin;
    private WebTestClient withoutPlugin;

    @BeforeAll
    void openDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过坐席订单真实 SQL 验证");
        }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(database));
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(3);
        var properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        jdbc = new JdbcTemplate(dataSource);
        withPlugin = client(true);
        withoutPlugin = client(false);
    }

    private WebTestClient client(boolean enabled) throws Exception {
        var properties = new CustomerWorkProperties();
        properties.getTenant().setEnabled(enabled);
        properties.getAgentAccess().setSecret(SECRET);
        var factory = new CustomerWorkPersistenceConfig().customerWorkSqlSessionFactory(dataSource, properties);
        factory.getConfiguration().addInterceptor(new BeforeOrderUpdate(beforeUpdate));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("orders", new SqlSessionTemplate(factory).getMapper(OrderMapper.class));
        var service = new OrderDirectoryService(beans.getBeanProvider(OrderMapper.class));
        return WebTestClient.bindToController(new AgentOrderController(service))
            .webFilter(new AgentAuthWebFilter(properties)).build();
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM cw_order");
        jdbc.update("DELETE FROM cw_user");
        jdbc.update("INSERT INTO cw_user(id,tenant_id,username,password_hash,nickname,created_at_ms) VALUES "
            + "('foreign-owner',?,'foreign-secret','test','B用户',1),('exact-owner',?,'alice','test','A用户',1)", TENANT_B, TENANT_A);
        seedOrder(TENANT_A, "A-FOREIGN-JOIN", "foreign-owner", 100);
        seedOrder(TENANT_A, "A-OWN", "exact-owner", 200);
        seedOrder(TENANT_A, "A-ALIAS-JOIN", "EXACT-OWNER", 300);
        seedOrder(TENANT_B, "B-PRIVATE", "foreign-owner", 400);
        beforeUpdate.set(null);
        TenantContext.clear();
    }

    @AfterEach
    void clearContext() {
        beforeUpdate.set(null);
        TenantContext.clear();
    }

    @AfterAll
    void closeDatabase() throws Exception {
        if (dataSource != null) dataSource.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedTenantLimitsPageAndUsernameJoinAfterSchedulerSwitch(boolean plugin) {
        clientFor(plugin).get().uri(BASE).header("X-Agent-Token", token(TENANT_A))
            .header("X-Tenant-Id", TENANT_B).exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.total").isEqualTo(3)
            .jsonPath("$.items[0].orderId").isEqualTo("A-ALIAS-JOIN")
            .jsonPath("$.items[0].username").isEmpty()
            .jsonPath("$.items[1].username").isEqualTo("alice")
            .jsonPath("$.items[2].username").isEmpty();
        assertNull(TenantContext.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void otherTenantDetailsAndWritesAreUnavailableAndDoNotMutate(boolean plugin) {
        var client = clientFor(plugin);
        var before = jdbc.queryForMap("SELECT * FROM cw_order WHERE order_id='B-PRIVATE'");
        client.get().uri(BASE + "/B-PRIVATE").header("X-Agent-Token", token(TENANT_A))
            .exchange().expectStatus().isNotFound();
        client.post().uri(BASE + "/B-PRIVATE/modify-address").header("X-Agent-Token", token(TENANT_A))
            .bodyValue(Map.of("newAddress", "越权地址")).exchange().expectStatus().isNotFound();
        client.post().uri(BASE + "/B-PRIVATE/cancel").header("X-Agent-Token", token(TENANT_A))
            .bodyValue(Map.of("reason", "越权取消")).exchange().expectStatus().isNotFound();
        assertEquals(before, jdbc.queryForMap("SELECT * FROM cw_order WHERE order_id='B-PRIVATE'"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedSeatCanReadModifyAndCancelItsTenantOrder(boolean plugin) {
        var client = clientFor(plugin);
        client.get().uri(BASE + "/A-OWN").header("X-Agent-Token", token(TENANT_A))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.username").isEqualTo("alice").jsonPath("$.amount").isEqualTo("29.00");
        client.post().uri(BASE + "/A-OWN/modify-address").header("X-Agent-Token", token(TENANT_A))
            .bodyValue(Map.of("newAddress", "新地址")).exchange().expectStatus().isOk();
        assertEquals("新地址", jdbc.queryForObject("SELECT receiver_addr FROM cw_order WHERE order_id='A-OWN'", String.class));
        client.post().uri(BASE + "/A-OWN/cancel").header("X-Agent-Token", token(TENANT_A))
            .bodyValue(Map.of("reason", "取消")).exchange().expectStatus().isOk();
        assertEquals(OrderStatuses.CANCELLED, jdbc.queryForObject("SELECT status FROM cw_order WHERE order_id='A-OWN'", String.class));
        assertEquals(OrderStatuses.PAID, jdbc.queryForObject("SELECT status FROM cw_order WHERE order_id='B-PRIVATE'", String.class));
    }

    @Test
    void legacyCredentialWithoutTenantIsRejectedEvenWhenPluginIsDisabled() {
        String legacy = AgentAccessCredential.sign("seat", System.currentTimeMillis() + 60_000, SECRET);
        withoutPlugin.get().uri(BASE).header("X-Agent-Token", legacy).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void subscriptionCredentialCannotReadOrders() {
        String subscription = AgentAccessCredential.signSubscription("seat", TENANT_A, System.currentTimeMillis() + 60_000, SECRET);
        withoutPlugin.get().uri(BASE).header("X-Agent-Token", subscription).exchange().expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shipmentCommittedImmediatelyBeforeCancellationIsNotOverwritten(boolean plugin) {
        beforeUpdate.set(() -> jdbc.update("UPDATE cw_order SET status=? WHERE order_id='A-OWN'", OrderStatuses.SHIPPED));
        clientFor(plugin).post().uri(BASE + "/A-OWN/cancel").header("X-Agent-Token", token(TENANT_A))
            .bodyValue(Map.of("reason", "取消")).exchange().expectStatus().isEqualTo(409);
        assertEquals(OrderStatuses.SHIPPED, jdbc.queryForObject("SELECT status FROM cw_order WHERE order_id='A-OWN'", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"modify-address", "cancel"})
    void concurrentDeletionDoesNotReturnFakeSuccess(String action) {
        beforeUpdate.set(() -> jdbc.update("DELETE FROM cw_order WHERE order_id='A-OWN'"));
        withoutPlugin.post().uri(BASE + "/A-OWN/" + action).header("X-Agent-Token", token(TENANT_A))
            .bodyValue(action.equals("cancel") ? Map.of("reason", "取消") : Map.of("newAddress", "新地址"))
            .exchange().expectStatus().isNotFound();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cw_order WHERE order_id='A-OWN'", Integer.class));
    }

    @Test
    void realDatabaseWriteFailureIs503() {
        jdbc.execute("CREATE TRIGGER reject_order_update BEFORE UPDATE ON cw_order FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private agent write rejection'");
        try {
            withoutPlugin.post().uri(BASE + "/A-OWN/modify-address").header("X-Agent-Token", token(TENANT_A))
                .bodyValue(Map.of("newAddress", "新地址")).exchange().expectStatus().isEqualTo(503);
            assertEquals("原地址", jdbc.queryForObject("SELECT receiver_addr FROM cw_order WHERE order_id='A-OWN'", String.class));
        } finally {
            jdbc.execute("DROP TRIGGER reject_order_update");
        }
    }

    private WebTestClient clientFor(boolean plugin) { return plugin ? withPlugin : withoutPlugin; }
    private String token(String tenant) { return AgentAccessCredential.sign("seat", tenant, System.currentTimeMillis() + 60_000, SECRET); }
    private void seedOrder(String tenant, String order, String user, long timestamp) {
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,product_name,amount,status,receiver_addr,created_at_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?)", tenant, order, user, "product", "测试商品", "29.00", OrderStatuses.PAID, "原地址", timestamp);
    }
    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    /** 只拦截目标订单 UPDATE，第二连接先提交竞争事实，再让真实 MyBatis SQL 执行。 */
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class BeforeOrderUpdate implements Interceptor {
        private final AtomicReference<Runnable> action;
        BeforeOrderUpdate(AtomicReference<Runnable> action) { this.action = action; }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().startsWith(OrderMapper.class.getName() + ".")) {
                Runnable pending = action.getAndSet(null);
                if (pending != null) pending.run();
            }
            return invocation.proceed();
        }
    }
}
