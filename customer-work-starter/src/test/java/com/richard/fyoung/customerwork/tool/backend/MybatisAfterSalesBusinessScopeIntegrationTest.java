package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import com.richard.fyoung.customerwork.tool.AfterSalesTools;
import com.richard.fyoung.customerwork.tool.ToolkitConfigs;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
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
import java.util.concurrent.CountDownLatch;
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

/** 真实 MySQL 与原生 Toolkit 验证售后订单归属；随机独占库使用正式迁移，关闭租户插件。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MybatisAfterSalesBusinessScopeIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String MYSQL_USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private static final String TENANT_A = "tenant-aftersales-a";
    private static final String TENANT_B = "tenant-aftersales-b";
    private static final String USER = "user-shared";
    private static final String OWN_ORDER = "scope-order-a";
    private static final String OTHER_TENANT_ORDER = "scope-order-b";
    private static final String OTHER_USER_ORDER = "scope-order-other-user";
    private static final String MISSING_ORDER = "scope-order-missing";
    private static final String SESSION = "session-owned-refund";
    private static final String REFUND_TABLE = "cw_refund";
    private static final String INVOICE_TABLE = "cw_invoice_request";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final AgentInvocationIdentity OWNER = identity(TENANT_A, QuotaSubjectType.USER, USER, true);
    private static final AgentInvocationIdentity WORKER_IDENTITY =
        identity(TENANT_B, QuotaSubjectType.USER, "worker-user", true);

    private final String database = "cw_aftersales_scope_" + UUID.randomUUID().toString().replace("-", "");
    private boolean databaseCreated;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private MybatisAfterSalesBackend backend;

    @BeforeAll
    void createIsolatedDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过售后真实订单隔离测试");
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
        dataSource.setPoolName("aftersales-business-scope");
        MybatisTestSupport.ensureSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        var template = MybatisTestSupport.template(dataSource);
        assertTrue(template.getConfiguration().getInterceptors().stream()
            .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
            .flatMap(interceptor -> interceptor.getInterceptors().stream())
            .noneMatch(TenantLineInnerInterceptor.class::isInstance), "此回归必须在未安装租户插件时验证真实 SQL");
        backend = new MybatisAfterSalesBackend(template.getMapper(RefundMapper.class),
            template.getMapper(InvoiceRequestMapper.class), template.getMapper(OrderMapper.class));
    }

    @BeforeEach
    void seedOwnedOrders() {
        jdbc.update("DELETE FROM cw_invoice_request");
        jdbc.update("DELETE FROM cw_refund");
        jdbc.update("DELETE FROM cw_order");
        seedOrder(TENANT_A, USER, OWN_ORDER);
        seedOrder(TENANT_B, USER, OTHER_TENANT_ORDER);
        seedOrder(TENANT_A, "other-user", OTHER_USER_ORDER);
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @AfterEach
    void clearCallerContext() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @AfterAll
    void removeIsolatedDatabase() throws Exception {
        if (dataSource != null) dataSource.close();
        if (databaseCreated) {
            try (var connection = DriverManager.getConnection(url(""), MYSQL_USER, MYSQL_PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REFUND", "RETURN", "EXCHANGE", "INVOICE"})
    void pluginDisabledPersistsEveryOwnedApplicationUnderAuthenticatedTenant(Operation operation) {
        String result = asIdentity(OWNER, () -> operation.call(backend, OWN_ORDER).block(TIMEOUT));
        assertNotNull(result);
        assertAll(
            () -> assertEquals(1, rows(operation.table(), TENANT_A, OWN_ORDER), "真实记录应落在认证租户"),
            () -> assertEquals(0, rows(operation.table(), TenantContext.DEFAULT, OWN_ORDER), "不能利用数据库默认值写入 default"),
            () -> assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM " + operation.table()
                + " WHERE order_id=?", String.class, OWN_ORDER), "只创建待处理申请，不执行支付")
        );
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REFUND", "RETURN", "EXCHANGE", "INVOICE"})
    void explicitOwnerSqlAlsoWorksWithTenantPluginEnabled(Operation operation) {
        var template = MybatisTestSupport.template(dataSource);
        template.getConfiguration().getInterceptors().stream()
            .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
            .forEach(interceptor -> interceptor.addInnerInterceptor(TenantInterceptors.build("tenant_id", List.of())));
        var pluginBackend = new MybatisAfterSalesBackend(template.getMapper(RefundMapper.class),
            template.getMapper(InvoiceRequestMapper.class), template.getMapper(OrderMapper.class));
        asIdentity(OWNER, () -> {
            assertNotNull(operation.call(pluginBackend, OWN_ORDER).block(TIMEOUT));
            assertTrue(pluginBackend.checkRefundEligibility(OWN_ORDER, "true").block(TIMEOUT).contains("满足"));
            assertNotNull(pluginBackend.queryRefundProgress(OWN_ORDER).block(TIMEOUT));
            return null;
        });
        assertEquals(1, rows(operation.table(), TENANT_A, OWN_ORDER));
        assertEquals(0, rows(operation.table(), TenantContext.DEFAULT, OWN_ORDER));
    }

    @Test
    void deferredSqlUsesMethodCallIdentityAndRestoresBothCallerAndWorker() throws Exception {
        AgentInvocationIdentityContext.set(WORKER_IDENTITY);
        TenantContext.set(TENANT_B);
        Mono<String> pending = asIdentity(OWNER, () -> backend.submitRefund(OWN_ORDER, "19.00", "测试退款"));
        assertEquals(0, totalApplications(), "方法调用时只冻结身份，不提前写入");
        assertEquals(WORKER_IDENTITY, AgentInvocationIdentity.capture());
        assertEquals(TENANT_B, TenantContext.get());
        var worker = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var completion = worker.submit(() -> {
                AgentInvocationIdentityContext.set(WORKER_IDENTITY);
                TenantContext.set(TENANT_B);
                try {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    String result = pending.block(TIMEOUT);
                    assertEquals(WORKER_IDENTITY, AgentInvocationIdentity.capture());
                    assertEquals(TENANT_B, TenantContext.get(), "SQL 完成后必须还原工作线程原有上下文");
                    return result;
                } finally {
                    AgentInvocationIdentityContext.clear();
                    TenantContext.clear();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertNotNull(completion.get(10, TimeUnit.SECONDS));
            assertAll(
                () -> assertEquals(1, rows(REFUND_TABLE, TENANT_A, OWN_ORDER), "延迟 SQL 应使用调用时冻结的身份"),
                () -> assertEquals(0, rows(REFUND_TABLE, TENANT_B, OWN_ORDER)),
                () -> assertEquals(0, rows(REFUND_TABLE, TenantContext.DEFAULT, OWN_ORDER))
            );
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untrustedSubjects")
    void allSevenOperationsRejectSubjectsWithoutOrderUserAuthority(String label, AgentInvocationIdentity identity) {
        assertDeniedWithoutCreating(identity, OWN_ORDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {OTHER_TENANT_ORDER, OTHER_USER_ORDER, MISSING_ORDER})
    void allSevenOperationsRejectForeignOrMissingOrders(String orderId) {
        assertDeniedWithoutCreating(OWNER, orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER-SHARED", "user-shared "})
    void databaseCollationAliasesAreNotTheAuthenticatedOrderUser(String alias) {
        assertDeniedWithoutCreating(identity(TENANT_A, QuotaSubjectType.USER, alias, true), OWN_ORDER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeToolkitPassesOwnerToActualJdbcWithAndWithoutApprovalService(boolean approvalEnabled) {
        PendingApprovalService approvals = new PendingApprovalService();
        TenantContext.set(TENANT_B);
        AgentInvocationIdentityContext.set(WORKER_IDENTITY);
        String output = callNativeRefund(approvalEnabled ? approvals : null);
        assertAll(
            () -> assertEquals(1, rows(REFUND_TABLE, TENANT_A, OWN_ORDER), "原生工具实际 SQL 必须收到完整 USER 身份"),
            () -> assertEquals(0, rows(REFUND_TABLE, TenantContext.DEFAULT, OWN_ORDER)),
            () -> assertEquals(approvalEnabled ? 1 : 0, TenantContext.callWith(TENANT_A, approvals::list).size()),
            () -> assertTrue(TenantContext.callWith(TENANT_B, approvals::list).isEmpty()),
            () -> assertTrue(output.contains("已生成退款工单"), output),
            () -> assertEquals(TENANT_B, TenantContext.get()),
            () -> assertEquals(WORKER_IDENTITY, AgentInvocationIdentity.capture())
        );
        if (approvalEnabled) {
            assertEquals(SESSION, TenantContext.callWith(TENANT_A, approvals::list).get(0).getSessionId());
        }
    }

    @Test
    void nativeWriteFailureDoesNotRegisterApprovalOrClaimCreation() {
        PendingApprovalService approvals = new PendingApprovalService();
        rejectInserts(REFUND_TABLE);
        try {
            String output = callNativeRefund(approvals);
            assertAll(
                () -> assertEquals(0, totalApplications()),
                () -> assertTrue(TenantContext.callWith(TENANT_A, approvals::list).isEmpty(), "业务写入失败不得登记审批"),
                () -> assertFalse(output.contains("审批单号"), output),
                () -> assertFalse(output.contains("已生成退款工单"), output),
                () -> assertFalse(output.contains("已为您转接人工"), "未执行转人工就不能声称已转接"),
                () -> assertTrue(output.contains("售后服务暂时不可用"), "必须保留明确的工具错误结果: " + output),
                () -> assertFalse(output.contains("cw_refund") || output.contains("INSERT INTO")
                    || output.contains("owned test write rejection"), "数据库内部错误不能进入模型工具结果: " + output)
            );
        } finally {
            allowInserts(REFUND_TABLE);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REFUND", "RETURN", "EXCHANGE", "INVOICE"})
    void actualDatabaseWriteFailureIsAnErrorSignal(Operation operation) {
        rejectInserts(operation.table());
        try {
            assertMonoError(OWNER, operation, OWN_ORDER);
            assertEquals(0, totalApplications());
        } finally {
            allowInserts(operation.table());
        }
    }

    @Test
    void refundProgressDoesNotReadNewerRecordFromAnotherTenant() {
        seedRefund(TENANT_A, "refund-a", "PENDING", 100);
        seedRefund(TENANT_B, "refund-b", "APPROVED", 200);
        String result = asIdentity(OWNER, () -> backend.queryRefundProgress(OWN_ORDER).block(TIMEOUT));
        assertAll(
            () -> assertTrue(result.contains("待人工复核"), result),
            () -> assertFalse(result.contains("审核通过"), "不能读取其他租户较新的退款状态")
        );
    }

    @Test
    void approvedRefundDoesNotClaimMoneyWasReturned() {
        seedRefund(TENANT_A, "refund-approved", "APPROVED", 100);
        String result = asIdentity(OWNER, () -> backend.queryRefundProgress(OWN_ORDER).block(TIMEOUT));
        assertAll(
            () -> assertTrue(result.contains("审核通过"), result),
            () -> assertFalse(result.contains("款项已原路退回"), "APPROVED 仅代表审核状态，没有到账证据"),
            () -> assertFalse(result.contains("预计 1-3 个工作日到账"), "没有支付渠道事实不能承诺到账时效")
        );
    }

    @Test
    void deniedRefundReportsTheStoredDecisionInsteadOfPending() {
        seedRefund(TENANT_A, "refund-denied", "DENIED", 100);
        String result = asIdentity(OWNER, () -> backend.queryRefundProgress(OWN_ORDER).block(TIMEOUT));
        assertTrue(result.contains("审核未通过"), result);
        assertFalse(result.contains("待人工复核"), result);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"REFUND", "RETURN", "EXCHANGE", "PRICE_PROTECTION", "INVOICE"})
    void successfulResultOnlyDescribesActionsActuallyPerformed(Operation operation) {
        String result = asIdentity(OWNER, () -> operation.call(backend, OWN_ORDER).block(TIMEOUT));
        List<String> unsupportedClaims = List.of("已转人工坐席复核", "预计 1 个工作日", "请在 7 天内",
            "短信发送", "新规格有货", "在价保有效期内", "当前未监测到降价", "暂无可补差价", "24 小时内发送");
        assertAll(unsupportedClaims.stream().map(claim -> (Executable) () ->
            assertFalse(result.contains(claim), () -> "没有真实依据的承诺: " + claim + "; output=" + result)));
    }

    private void assertDeniedWithoutCreating(AgentInvocationIdentity identity, String orderId) {
        Stream<Executable> operations = Arrays.stream(Operation.values()).map(operation ->
            () -> assertMonoError(identity, operation, orderId));
        assertAll(Stream.concat(operations, Stream.of(
            () -> assertEquals(0, totalApplications(), "无授权操作不能创建售后或发票记录"))));
    }

    private void assertMonoError(AgentInvocationIdentity identity, Operation operation, String orderId) {
        Mono<String> pending = assertDoesNotThrow(() -> asIdentity(identity, () -> operation.call(backend, orderId)),
            "异步接口应通过 Mono 错误信号返回拒绝");
        assertThrows(RuntimeException.class, () -> pending.block(TIMEOUT),
            () -> operation + " 必须传播拒绝/失败，不能返回正常成功文案");
    }

    private String callNativeRefund(PendingApprovalService approvals) {
        Toolkit toolkit = new Toolkit(ToolkitConfigs.sequential());
        toolkit.registerTool(new AfterSalesTools(backend, approvals, SESSION));
        Map<String, Object> input = Map.of("orderId", OWN_ORDER, "amount", "19.00", "reason", "测试退款");
        RuntimeContext context = RuntimeContext.builder().sessionId(SESSION)
            .put(AgentInvocationIdentity.class, OWNER).build();
        var result = toolkit.callTool(ToolCallParam.builder()
            .toolUseBlock(ToolUseBlock.builder().id("actual-jdbc-refund").name("submitRefund").input(input)
                .content("{\"orderId\":\"" + OWN_ORDER + "\",\"amount\":\"19.00\",\"reason\":\"测试退款\"}").build())
            .input(input).runtimeContext(context).build()).block(TIMEOUT);
        assertNotNull(result);
        String output = result.getOutput().toString();
        assertFalse(output.contains("Parameter validation failed"), "测试输入必须先通过真实 Toolkit 参数校验: " + output);
        return output;
    }

    private static Stream<Arguments> untrustedSubjects() {
        return Stream.of(
            Arguments.of("API_KEY 的同名 subjectId 不是订单用户", identity(TENANT_A, QuotaSubjectType.API_KEY, USER, true)),
            Arguments.of("ADMIN_USER 的同名 subjectId 不是订单用户", identity(TENANT_A, QuotaSubjectType.ADMIN_USER, USER, true)),
            Arguments.of("IP 不具有订单访问权", identity(TENANT_A, QuotaSubjectType.IP, USER, false)),
            Arguments.of("未认证 USER 不具有订单访问权", identity(TENANT_A, QuotaSubjectType.USER, USER, false)),
            Arguments.of("缺租户不能借用线程租户", identity(null, QuotaSubjectType.USER, USER, true)),
            Arguments.of("缺用户不能反查订单补身份", identity(TENANT_A, QuotaSubjectType.USER, null, true)),
            Arguments.of("无身份不能回落默认用户", null));
    }

    private static AgentInvocationIdentity identity(String tenant, QuotaSubjectType type, String user, boolean authenticated) {
        return new AgentInvocationIdentity(tenant, type, user, authenticated)
            .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, SESSION, "customer-service");
    }

    private static <T> T asIdentity(AgentInvocationIdentity identity, Supplier<T> action) {
        // 即使线程上有正确租户，主体类型仍必须独立验证；租户不是订单用户的凭据。
        return TenantContext.callWith(identity == null ? TENANT_A : identity.tenantId(),
            () -> AgentInvocationIdentityContext.callWith(identity, action));
    }

    private void seedOrder(String tenant, String user, String orderId) {
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,product_name,amount,status,created_at_ms)"
            + " VALUES(?,?,?,?,?,?,?,?)", tenant, orderId, user, "scope-product", "测试商品", "29.00", OrderStatuses.PAID, 1L);
    }

    private void seedRefund(String tenant, String refundNo, String status, long createdAt) {
        jdbc.update("INSERT INTO cw_refund(tenant_id,refund_no,order_id,type,status,amount,created_at_ms) VALUES(?,?,?,?,?,?,?)",
            tenant, refundNo, OWN_ORDER, "REFUND", status, "19.00", createdAt);
    }

    private int rows(String table, String tenant, String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND order_id=?",
            Integer.class, tenant, orderId);
    }

    private int totalApplications() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM cw_refund) +"
            + " (SELECT COUNT(*) FROM cw_invoice_request)", Integer.class);
    }

    private void rejectInserts(String table) {
        jdbc.execute("CREATE TRIGGER reject_" + table + " BEFORE INSERT ON " + table
            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned test write rejection'");
    }

    private void allowInserts(String table) {
        jdbc.execute("DROP TRIGGER reject_" + table);
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }

    private enum Operation {
        ELIGIBILITY, REFUND, PROGRESS, RETURN, EXCHANGE, PRICE_PROTECTION, INVOICE;

        Mono<String> call(AfterSalesBackend backend, String orderId) {
            return switch (this) {
                case ELIGIBILITY -> backend.checkRefundEligibility(orderId, "true");
                case REFUND -> backend.submitRefund(orderId, "19.00", "测试退款");
                case PROGRESS -> backend.queryRefundProgress(orderId);
                case RETURN -> backend.submitReturn(orderId, "测试退货");
                case EXCHANGE -> backend.submitExchange(orderId, "测试换货", "白色");
                case PRICE_PROTECTION -> backend.checkPriceProtection(orderId);
                case INVOICE -> backend.requestInvoice(orderId, "测试发票抬头");
            };
        }

        String table() {
            return this == INVOICE ? INVOICE_TABLE : REFUND_TABLE;
        }
    }
}
