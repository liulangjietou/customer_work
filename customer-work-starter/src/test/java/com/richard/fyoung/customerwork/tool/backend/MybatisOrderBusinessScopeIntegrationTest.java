package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import com.richard.fyoung.customerwork.tool.OrderTools;
import com.richard.fyoung.customerwork.tool.ToolkitConfigs;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 实际 Toolkit 与 MySQL 验证订单用户归属；只使用本测试创建的随机数据库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MybatisOrderBusinessScopeIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String MYSQL_USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String TENANT_A = "tenant-order-a";
    private static final String TENANT_B = "tenant-order-b";
    private static final String USER = "order-owner";
    private static final String OWN_ORDER = "owned-order";
    private static final String OTHER_TENANT_ORDER = "other-tenant-order";
    private static final String OTHER_USER_ORDER = "other-user-order";
    private static final String NEW_ADDRESS = "新地址";
    private static final String SESSION = "order-scope-session";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final AgentInvocationIdentity OWNER = identity(TENANT_A, QuotaSubjectType.USER, USER, true);
    private static final AgentInvocationIdentity WORKER = identity(TENANT_B, QuotaSubjectType.USER, "worker", true);
    private final String database = "cw_order_scope_" + UUID.randomUUID().toString().replace("-", "");
    private boolean databaseCreated;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private MybatisOrderBackend backend;

    @BeforeAll
    void createDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过真实订单归属验证");
        }
        try (var connection = DriverManager.getConnection(url(""), MYSQL_USER, MYSQL_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            databaseCreated = true;
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(database));
        dataSource.setUsername(MYSQL_USER);
        dataSource.setPassword(MYSQL_PASSWORD);
        dataSource.setMaximumPoolSize(3);
        dataSource.setPoolName("order-business-scope");
        MybatisTestSupport.ensureSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var template = MybatisTestSupport.template(dataSource);
        assertTrue(template.getConfiguration().getInterceptors().stream()
            .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
            .flatMap(plugin -> plugin.getInterceptors().stream())
            .noneMatch(TenantLineInnerInterceptor.class::isInstance));
        backend = new MybatisOrderBackend(template.getMapper(OrderMapper.class));
    }

    @BeforeEach
    void seedOrders() {
        jdbc.update("DELETE FROM cw_order");
        seedOrder(TENANT_A, USER, OWN_ORDER);
        seedOrder(TENANT_B, USER, OTHER_TENANT_ORDER);
        seedOrder(TENANT_A, "other-user", OTHER_USER_ORDER);
        clearContext();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (dataSource != null) dataSource.close();
        if (!databaseCreated) return;
        try (var connection = DriverManager.getConnection(url(""), MYSQL_USER, MYSQL_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void authenticatedOwnerCanUseEveryOperation(Operation operation) {
        String output = asIdentity(OWNER, () -> operation.call(backend, OWN_ORDER).block(TIMEOUT));
        assertNotNull(output);
        assertTrue(output.contains(OWN_ORDER), output);
        assertOwnMutation(operation);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untrustedSubjects")
    void allOperationsRejectUntrustedSubjects(String label, AgentInvocationIdentity identity) {
        assertDenied(identity, OWN_ORDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {OTHER_TENANT_ORDER, OTHER_USER_ORDER, "missing-order"})
    void allOperationsRejectForeignOrMissingOrders(String orderId) {
        assertDenied(OWNER, orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER-OWNER", "order-owner "})
    void databaseCollationAliasesAreDifferentUsers(String alias) {
        assertDenied(identity(TENANT_A, QuotaSubjectType.USER, alias, true), OWN_ORDER);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void nativeToolkitUsesTrustedRuntimeIdentityAndRestoresWorker(Operation operation) {
        AgentInvocationIdentityContext.set(WORKER);
        TenantContext.set(TENANT_B);
        String output = nativeCall(operation, OWN_ORDER);
        assertNotNull(output);
        assertTrue(output.contains(OWN_ORDER), output);
        assertOwnMutation(operation);
        assertEquals(WORKER, AgentInvocationIdentity.capture());
        assertEquals(TENANT_B, TenantContext.get());
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ADDRESS", "CANCEL", "URGE"})
    void writeFaultIsNotMissingOrderAndDoesNotExposePrivateDatabaseText(Operation operation) {
        jdbc.execute("CREATE TRIGGER reject_order_update BEFORE UPDATE ON cw_order FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private order write rejection'");
        try {
            var before = snapshot();
            assertThrows(RuntimeException.class, () -> asIdentity(OWNER, () -> operation.call(backend, OWN_ORDER).block(TIMEOUT)));
            String output = nativeCall(operation, OWN_ORDER);
            assertAll(
                () -> assertEquals(before, snapshot()),
                () -> assertFalse(output.contains("未查询到"), output),
                () -> assertFalse(output.contains("private order write rejection") || output.contains("UPDATE cw_order"), output),
                () -> assertTrue(output.contains("暂时不可用"), output)
            );
        } finally {
            jdbc.execute("DROP TRIGGER reject_order_update");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {OrderStatuses.SHIPPED, OrderStatuses.RECEIVED, OrderStatuses.CANCELLED, OrderStatuses.REFUNDED})
    void shippedOrTerminalOrdersCannotBeCancelled(String status) {
        jdbc.update("UPDATE cw_order SET status=? WHERE order_id=?", status, OWN_ORDER);
        assertThrows(RuntimeException.class, () -> asIdentity(OWNER, () -> backend.cancelOrder(OWN_ORDER, "测试取消").block(TIMEOUT)));
        assertEquals(status, jdbc.queryForObject("SELECT status FROM cw_order WHERE order_id=?", String.class, OWN_ORDER));
    }

    @Test
    void repeatedAddressIsSuccessfulWhenDriverReportsChangedRowsOnly() {
        var changedRows = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            url(database) + "&useAffectedRows=true", MYSQL_USER, MYSQL_PASSWORD);
        var scopedBackend = new MybatisOrderBackend(MybatisTestSupport.template(changedRows).getMapper(OrderMapper.class));
        assertNotNull(asIdentity(OWNER, () -> scopedBackend.modifyAddress(OWN_ORDER, NEW_ADDRESS).block(TIMEOUT)));
        var before = snapshot();
        assertNotNull(asIdentity(OWNER, () -> scopedBackend.modifyAddress(OWN_ORDER, NEW_ADDRESS).block(TIMEOUT)));
        assertEquals(before, snapshot());
    }

    @Test
    void deferredSqlUsesMethodCallIdentityInsteadOfSubscriptionThread() throws Exception {
        Mono<String> pending = asIdentity(OWNER, () -> backend.modifyAddress(OWN_ORDER, NEW_ADDRESS));
        assertEquals("原地址", jdbc.queryForObject("SELECT receiver_addr FROM cw_order WHERE order_id=?", String.class, OWN_ORDER));
        var worker = Executors.newSingleThreadExecutor();
        try {
            // 提交发生在方法返回之后，保证 SQL 订阅确实发生在另一条线程上。
            worker.submit(() -> asIdentity(WORKER, () -> {
                assertNotNull(pending.block(TIMEOUT));
                assertEquals(WORKER, AgentInvocationIdentity.capture());
                assertEquals(TENANT_B, TenantContext.get());
                return null;
            })).get(10, TimeUnit.SECONDS);
            assertOwnMutation(Operation.ADDRESS);
        } finally {
            worker.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void explicitOwnerSqlWorksWithTenantPlugin(Operation operation) {
        var template = MybatisTestSupport.template(dataSource);
        template.getConfiguration().getInterceptors().stream()
            .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
            .forEach(plugin -> plugin.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of())));
        var guarded = new MybatisOrderBackend(template.getMapper(OrderMapper.class));
        assertNotNull(asIdentity(OWNER, () -> operation.call(guarded, OWN_ORDER).block(TIMEOUT)));
        assertOwnMutation(operation);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ADDRESS", "CANCEL", "URGE"})
    void successfulWritesDoNotPromiseUnperformedPaymentOrWarehouseWork(Operation operation) {
        String output = asIdentity(OWNER, () -> operation.call(backend, OWN_ORDER).block(TIMEOUT));
        for (String unsupported : List.of("款项原路退回", "仓库将优先处理", "24 小时内出库", "将按新地址派送")) {
            assertFalse(output.contains(unsupported), output);
        }
    }

    private void assertDenied(AgentInvocationIdentity identity, String orderId) {
        var before = snapshot();
        Stream<Executable> calls = Arrays.stream(Operation.values()).map(operation -> () -> {
            Mono<String> pending = assertDoesNotThrow(() -> asIdentity(identity, () -> operation.call(backend, orderId)));
            assertThrows(RuntimeException.class, () -> pending.block(TIMEOUT), operation + " 必须用错误信号拒绝");
        });
        assertAll(Stream.concat(calls, Stream.of(() -> assertEquals(before, snapshot(), "拒绝操作不能改变任意订单"))));
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT order_id,tenant_id,user_id,status,receiver_addr,logistics_trace FROM cw_order ORDER BY order_id");
    }

    private void assertOwnMutation(Operation operation) {
        if (operation == Operation.ADDRESS) {
            assertEquals(NEW_ADDRESS, jdbc.queryForObject("SELECT receiver_addr FROM cw_order WHERE order_id=?", String.class, OWN_ORDER));
        } else if (operation == Operation.CANCEL) {
            assertEquals(OrderStatuses.CANCELLED, jdbc.queryForObject("SELECT status FROM cw_order WHERE order_id=?", String.class, OWN_ORDER));
        } else if (operation == Operation.URGE) {
            assertTrue(jdbc.queryForObject("SELECT logistics_trace FROM cw_order WHERE order_id=?", String.class, OWN_ORDER).contains("已加急"));
        }
        assertEquals(List.of(OrderStatuses.PAID, OrderStatuses.PAID), jdbc.queryForList(
            "SELECT status FROM cw_order WHERE order_id IN (?,?) ORDER BY order_id", String.class, OTHER_TENANT_ORDER, OTHER_USER_ORDER));
    }

    private String nativeCall(Operation operation, String orderId) {
        Toolkit toolkit = new Toolkit(ToolkitConfigs.sequential());
        toolkit.registerTool(new OrderTools(backend));
        Map<String, Object> input = switch (operation) {
            case ADDRESS -> Map.of("orderId", orderId, "newAddress", NEW_ADDRESS);
            case CANCEL -> Map.of("orderId", orderId, "reason", "测试取消");
            default -> Map.of("orderId", orderId);
        };
        // 输入均为本夹具固定字符串，原生 Toolkit 同时消费 input 和 ToolUseBlock.content。
        String content = "{\"orderId\":\"" + orderId + "\""
            + (operation == Operation.ADDRESS ? ",\"newAddress\":\"" + NEW_ADDRESS + "\"" : "")
            + (operation == Operation.CANCEL ? ",\"reason\":\"测试取消\"" : "") + "}";
        RuntimeContext context = RuntimeContext.builder().sessionId(SESSION).put(AgentInvocationIdentity.class, OWNER).build();
        var result = toolkit.callTool(ToolCallParam.builder()
            .toolUseBlock(ToolUseBlock.builder().id("native-order").name(operation.method).input(input).content(content).build())
            .input(input).runtimeContext(context).build()).block(TIMEOUT);
        assertNotNull(result);
        String output = result.getOutput().toString();
        assertFalse(output.contains("Parameter validation failed"), "夹具必须先通过原生参数校验: " + output);
        return output;
    }

    private static Stream<Arguments> untrustedSubjects() {
        return Stream.of(
            Arguments.of("API_KEY 同名主体", identity(TENANT_A, QuotaSubjectType.API_KEY, USER, true)),
            Arguments.of("ADMIN_USER 同名主体", identity(TENANT_A, QuotaSubjectType.ADMIN_USER, USER, true)),
            Arguments.of("未认证 IP", identity(TENANT_A, QuotaSubjectType.IP, USER, false)),
            Arguments.of("未认证 USER", identity(TENANT_A, QuotaSubjectType.USER, USER, false)),
            Arguments.of("缺租户", identity(null, QuotaSubjectType.USER, USER, true)),
            Arguments.of("缺用户", identity(TENANT_A, QuotaSubjectType.USER, null, true)),
            Arguments.of("无身份", null));
    }

    private static AgentInvocationIdentity identity(String tenant, QuotaSubjectType type, String user, boolean authenticated) {
        return new AgentInvocationIdentity(tenant, type, user, authenticated)
            .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, SESSION, "customer-service");
    }

    private static <T> T asIdentity(AgentInvocationIdentity identity, Supplier<T> action) {
        return TenantContext.callWith(identity == null ? TENANT_A : identity.tenantId(),
            () -> AgentInvocationIdentityContext.callWith(identity, action));
    }

    private void seedOrder(String tenant, String user, String order) {
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,product_name,amount,status,"
            + "receiver_addr,logistics_trace,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)", tenant, order, user,
            "scope-product", "测试商品", "29.00", OrderStatuses.PAID, "原地址", "原物流", 1L);
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    private enum Operation {
        QUERY("queryOrder"), LOGISTICS("queryLogistics"), ADDRESS("modifyAddress"), CANCEL("cancelOrder"), URGE("urgeShipment");
        private final String method;
        Operation(String method) { this.method = method; }
        Mono<String> call(OrderBackend backend, String order) {
            return switch (this) {
                case QUERY -> backend.queryOrder(order);
                case LOGISTICS -> backend.queryLogistics(order);
                case ADDRESS -> backend.modifyAddress(order, NEW_ADDRESS);
                case CANCEL -> backend.cancelOrder(order, "测试取消");
                case URGE -> backend.urgeShipment(order);
            };
        }
    }
}
