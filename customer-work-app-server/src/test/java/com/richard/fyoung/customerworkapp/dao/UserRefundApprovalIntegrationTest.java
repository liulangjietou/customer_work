package com.richard.fyoung.customerworkapp.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.customerwork.capability.approval.*;
import com.richard.fyoung.customerwork.capability.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.data.ticket.*;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.controller.UserRefundApprovalController;
import com.richard.fyoung.customerworkapp.service.UserRefundApprovalQueryService;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 自有随机 MySQL 库中的真实 JWT、会话守卫、订单 JOIN 与两种标准审批存储验证。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRefundApprovalIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String BASE_PATH = "/api/customer/user/sessions/session-shared/refund-approvals";
    private final InMemoryApprovalStore memoryStore = new InMemoryApprovalStore();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private String database;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ApprovalStore jdbcStore;
    private UserRefundApprovalDao approvalDao;
    private UserOrderDao orderDao;
    private UserSessionGuard sessionGuard;
    private UserJwtService jwtService;

    @BeforeAll
    void createDatabaseAndFacts() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过退款办理记录集成验证");
        }
        String candidate = "cw_refund_read_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            database = candidate;
        }
        dataSource = new DriverManagerDataSource(url(database), USER, PASSWORD);
        beans.registerSingleton("customerWorkDataSource", dataSource);
        var properties = properties(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        var template = new SqlSessionTemplate(new CustomerWorkPersistenceConfig()
            .customerWorkSqlSessionFactory(dataSource, properties));
        beans.registerSingleton("approvalMapper", template.getMapper(ApprovalMapper.class));
        jdbcStore = new ApprovalConfig().approvalStore(properties, beans.getBeanProvider(ApprovalMapper.class));
        approvalDao = new UserRefundApprovalDao(beans.getBeanProvider(DataSource.class));
        orderDao = new UserOrderDao(beans.getBeanProvider(DataSource.class));
        sessionGuard = guard(template);
        jwtService = new UserJwtService(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO cw_ticket(tenant_id,id,session_id,user_id,status,created_at_ms,updated_at_ms) VALUES "
            + "('tenant-A','TA','session-shared','shared-user','CLOSED',1,1),"
            + "('tenant-B','TB','session-shared','shared-user','CLOSED',2,2),"
            + "('tenant-A','TA-other','session-other','other-user','CLOSED',3,3),"
            + "('tenant-B','TB-only','session-B-only','shared-user','CLOSED',4,4),"
            + "('tenant-A','TA-empty','session-empty','shared-user','CLOSED',5,5)");
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,amount,status,created_at_ms) VALUES "
            + "('tenant-A','A-owned','shared-user','P1',10.00,'PAID',1),"
            + "('tenant-A','A-other','other-user','P1',20.00,'PAID',1),"
            + "('tenant-A','A-case-user','SHARED-USER','P1',30.00,'PAID',1),"
            + "('tenant-B','B-owned','shared-user','P1',40.00,'PAID',1)");
        add("tenant-A", "AP-01", "session-shared", "A-owned", null, ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 1000);
        add("tenant-A", "AP-02", "session-shared", "A-owned", "20.500", ApprovalStatus.DENIED, ExecutionStatus.NOT_APPLICABLE, 2000);
        add("tenant-A", "AP-03", "session-shared", "A-owned", "30.00", ApprovalStatus.APPROVED, ExecutionStatus.NOT_APPLICABLE, 3000);
        add("tenant-A", "AP-04", "session-shared", "A-owned", "40.00", ApprovalStatus.APPROVED, ExecutionStatus.EXECUTING, 4000);
        add("tenant-A", "AP-05", "session-shared", "A-owned", "50.00", ApprovalStatus.APPROVED, ExecutionStatus.EXECUTED, 5000);
        add("tenant-A", "AP-06", "session-shared", "A-owned", "60.00", ApprovalStatus.APPROVED, ExecutionStatus.EXECUTE_FAILED, 5000);
        add("tenant-A", "AP-other", "session-shared", "A-other", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-A", "AP-order-case", "session-shared", "A-case-user", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-A", "AP-cross-order", "session-shared", "B-owned", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-A", "AP-missing-order", "session-shared", null, "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-A", "AP-case", "SESSION-SHARED", "A-owned", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-A", "AP-other-session", "another-session", "A-owned", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 6000);
        add("tenant-B", "BP-01", "session-shared", "B-owned", "70.00", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 7000);
        // 另一租户即使错误地关联本租户可见订单，也不能成为本租户的审批事实。
        add("tenant-B", "BP-cross-order", "session-shared", "A-owned", "secret", ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE, 7000);
        save("tenant-A", new ApprovalRequest("AP-transfer", ApprovalType.TRANSFER_HUMAN,
            "session-shared", "A-owned", "secret", "internal reason", 8000));
    }

    @BeforeEach
    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    void dropDatabase() throws Exception {
        TenantContext.clear();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "memory"})
    void authenticatedPageFiltersAllVisibilityBoundariesAndReturnsOnlyPublicFacts(String mode) {
        JsonNode page = get(client(mode), BASE_PATH, "tenant-A");
        assertEquals(Set.of("total", "items"), fields(page));
        assertEquals(6, page.get("total").asLong());
        assertEquals(List.of("AP-06", "AP-05", "AP-04", "AP-03", "AP-02", "AP-01"), ids(page));
        for (JsonNode item : page.get("items")) {
            assertEquals(Set.of("id", "orderId", "amount", "approvalStatus", "executionStatus", "createdAtMs", "decidedAtMs"), fields(item));
        }
        assertEquals("EXECUTE_FAILED", page.at("/items/0/executionStatus").asText());
        assertEquals("EXECUTED", page.at("/items/1/executionStatus").asText());
        assertEquals("EXECUTING", page.at("/items/2/executionStatus").asText());
        assertEquals("APPROVED", page.at("/items/3/approvalStatus").asText());
        assertEquals("NOT_APPLICABLE", page.at("/items/3/executionStatus").asText());
        assertEquals("DENIED", page.at("/items/4/approvalStatus").asText());
        assertEquals("20.500", page.at("/items/4/amount").asText());
        assertEquals("PENDING", page.at("/items/5/approvalStatus").asText());
        assertTrue(page.at("/items/5/amount").isNull());
        assertEquals(5000, page.at("/items/0/createdAtMs").asLong());
        assertEquals(5001, page.at("/items/0/decidedAtMs").asLong());
        assertEquals(List.of("BP-01"), ids(get(client(mode), BASE_PATH, "tenant-B")));
        assertNull(TenantContext.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "memory"})
    void pagingUsesCreatedTimeThenIdAndKeepsAuthorizedTotal(String mode) {
        var client = client(mode);
        assertEquals(List.of("AP-06", "AP-05"), ids(get(client, BASE_PATH + "?page=1&size=2", "tenant-A")));
        JsonNode second = get(client, BASE_PATH + "?page=2&size=2", "tenant-A");
        assertEquals(6, second.get("total").asLong());
        assertEquals(List.of("AP-04", "AP-03"), ids(second));
        assertEquals(List.of("AP-02", "AP-01"), ids(get(client, BASE_PATH + "?page=3&size=2", "tenant-A")));
        JsonNode beyond = get(client, BASE_PATH + "?page=2147483647&size=50", "tenant-A");
        assertEquals(6, beyond.get("total").asLong());
        assertTrue(ids(beyond).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "memory"})
    void unknownOtherUserOtherTenantAndDifferentSessionCaseReturn404(String mode) {
        var client = client(mode);
        for (String session : List.of("unknown", "session-other", "session-B-only", "SESSION-SHARED")) {
            client.get().uri("/api/customer/user/sessions/" + session + "/refund-approvals")
                .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
                .exchange().expectStatus().isNotFound().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        JsonNode empty = get(client, "/api/customer/user/sessions/session-empty/refund-approvals", "tenant-A");
        assertEquals(0, empty.get("total").asLong());
        assertTrue(ids(empty).isEmpty());
    }

    @Test
    void sessionSqlOwnershipStillHoldsWhenMybatisTenantPluginIsDisabled() throws Exception {
        var template = new SqlSessionTemplate(new CustomerWorkPersistenceConfig()
            .customerWorkSqlSessionFactory(dataSource, properties(false)));
        var client = client(jdbcStore, approvalDao, orderDao, guard(template));
        client.get().uri("/api/customer/user/sessions/session-B-only/refund-approvals")
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
            .exchange().expectStatus().isNotFound();
        assertEquals(6, get(client, BASE_PATH, "tenant-A").get("total").asLong());
    }

    @ParameterizedTest
    @CsvSource({"jdbc,cw_approval", "jdbc,cw_order", "jdbc,cw_ticket", "memory,cw_order", "memory,cw_ticket"})
    void databaseFailuresReturn503WithoutPretendingThereAreNoRecords(String mode, String table) {
        jdbc.execute("RENAME TABLE " + table + " TO " + table + "_unavailable");
        try {
            client(mode).get().uri(BASE_PATH).header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
                .exchange().expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        } finally {
            jdbc.execute("RENAME TABLE " + table + "_unavailable TO " + table);
        }
    }

    @Test
    void customStoreIsUnavailableWithoutReadingUnverifiableTenantFacts() {
        var custom = mock(ApprovalStore.class);
        client(custom, approvalDao, orderDao, sessionGuard).get().uri(BASE_PATH)
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
            .exchange().expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        verifyNoInteractions(custom);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc", "memory"})
    void missingOrderDataSourceIsUnavailableForEitherStandardStore(String mode) {
        var absent = new DefaultListableBeanFactory().getBeanProvider(DataSource.class);
        client(store(mode), new UserRefundApprovalDao(absent), new UserOrderDao(absent), sessionGuard)
            .get().uri(BASE_PATH).header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
            .exchange().expectStatus().isEqualTo(503).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private WebTestClient client(String mode) { return client(store(mode), approvalDao, orderDao, sessionGuard); }
    private ApprovalStore store(String mode) { return "jdbc".equals(mode) ? jdbcStore : memoryStore; }

    private WebTestClient client(ApprovalStore store, UserRefundApprovalDao queryDao, UserOrderDao orders, UserSessionGuard guard) {
        return WebTestClient.bindToController(new UserRefundApprovalController(guard,
            new UserRefundApprovalQueryService(store, queryDao, orders)))
            .webFilter(new UserAuthWebFilter(jwtService)).build();
    }

    private JsonNode get(WebTestClient client, String uri, String tenantId) {
        return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(tenantId))
            .header("X-Tenant-Id", "tenant-spoofed")
            .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private String bearer(String tenantId) { return "Bearer " + jwtService.issue("shared-user", "alice", "Alice", tenantId); }

    private List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private UserSessionGuard guard(SqlSessionTemplate template) {
        // 手动创建工厂时没有运行 @MapperScan，补注册没有 XML 的事件 Mapper。
        var configuration = template.getSqlSessionFactory().getConfiguration();
        if (!configuration.hasMapper(TicketEventMapper.class)) configuration.addMapper(TicketEventMapper.class);
        return new UserSessionGuard(new TicketService(new MybatisTicketStore(template.getMapper(TicketMapper.class),
            template.getMapper(TicketEventMapper.class)), beans.getBeanProvider(TicketEventListener.class)));
    }

    private CustomerWorkProperties properties(boolean tenantEnabled) {
        var properties = new CustomerWorkProperties();
        properties.getHumanApproval().setStoreMode("jdbc");
        properties.getSession().getMysql().setMigrationEnabled(true);
        properties.getTenant().setEnabled(tenantEnabled);
        return properties;
    }

    private void add(String tenantId, String id, String session, String order, String amount,
        ApprovalStatus status, ExecutionStatus execution, long createdAt) {
        var request = new ApprovalRequest(id, ApprovalType.REFUND, session, order, amount, "internal reason", createdAt);
        if (status == ApprovalStatus.APPROVED) request.approve("internal operator", "internal decision note", createdAt + 1);
        if (status == ApprovalStatus.DENIED) request.deny("internal operator", "internal decision note", createdAt + 1);
        if (execution == ExecutionStatus.EXECUTING) request.markExecuting(createdAt + 2, "internal fencing token");
        if (execution == ExecutionStatus.EXECUTED) request.markExecuted();
        if (execution == ExecutionStatus.EXECUTE_FAILED) request.markExecutionFailed("internal execution error");
        save(tenantId, request);
    }

    private void save(String tenantId, ApprovalRequest request) {
        TenantContext.runWith(tenantId, () -> { jdbcStore.save(request); memoryStore.save(request); });
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
