package com.richard.fyoung.customerworkapp.dao;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import com.richard.fyoung.customerworkapp.controller.UserOrderController;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 现有 Controller 测试 mock 掉了整个 DAO，既不运行 SQL，也没有同 userId 的多租户订单。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserOrderDaoIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private String database;
    private UserOrderDao dao;
    private JdbcTemplate jdbc;
    private UserJwtService jwtService;
    private WebTestClient webTestClient;

    @BeforeAll
    void createDatabase() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1000);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过用户订单隔离集成验证");
        }
        String candidate = "cw_order_read_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            database = candidate;
        }
        var dataSource = new DriverManagerDataSource(url(database), USER, PASSWORD);
        var properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("orders", dataSource);
        dao = new UserOrderDao(beans.getBeanProvider(javax.sql.DataSource.class));
        jwtService = new UserJwtService(properties);
        webTestClient = WebTestClient.bindToController(new UserOrderController(dao))
            .webFilter(new UserAuthWebFilter(jwtService)).build();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,product_name,amount,status,"
            + "receiver_addr,logistics_trace,created_at_ms) VALUES"
            + "('tenant-A','A-OLD','shared-user','P1','耳机',12.30,'PAID','A 地址','A 历史轨迹',1000),"
            + "('tenant-A','A-NEW','shared-user','P2','键盘',99.00,'SHIPPED','A 新地址','A 新轨迹',2000),"
            + "('tenant-B','B-SECRET','shared-user','P3','私有商品',1.01,'PAID','B 私有地址','B 私有轨迹',3000),"
            + "('tenant-A','A-OTHER','other-user','P4','其他用户商品',2.00,'PAID','其他用户地址','其他用户轨迹',4000),"
            + "('default','DEFAULT-ORDER','legacy-user','P5','存量商品',3.00,'PAID','存量地址','存量轨迹',5000)");
    }

    @BeforeEach
    void tenant() {
        TenantContext.set("tenant-A");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void listDoesNotReadOtherTenantOrdersForTheSameUserId() {
        var orders = dao.listByUser("shared-user");
        assertEquals(List.of("A-NEW", "A-OLD"), orders.stream().map(UserOrderDao.OrderView::orderId).toList());
        assertEquals("99.00", orders.get(0).amount());
        assertEquals("A 新地址", orders.get(0).receiverAddr());
        assertNull(orders.get(0).logisticsTrace());
        assertEquals(List.of("B-SECRET"), TenantContext.callWith("tenant-B", () -> dao.listByUser("shared-user"))
            .stream().map(UserOrderDao.OrderView::orderId).toList());
    }

    @Test
    void detailDoesNotReadOtherTenantOrderEvenWhenUserIdMatches() {
        assertTrue(dao.findById("shared-user", "B-SECRET").isEmpty(), "另一租户相同 userId 的地址和物流不能被取出");
        assertTrue(TenantContext.callWith("tenant-B", () -> dao.findById("shared-user", "A-NEW")).isEmpty());
    }

    @Test
    void detailDoesNotReadAnotherUsersOrderWithinTheTenant() {
        assertTrue(dao.findById("shared-user", "A-OTHER").isEmpty(), "读取行之前就应限定已认证用户，不能先取出他人的订单");
    }

    @Test
    void listWithoutTenantIsRejectedInsteadOfQueryingDefaultOrAllTenants() {
        TenantContext.clear();
        assertThrows(TenantContextMissingException.class, () -> dao.listByUser("shared-user"));
    }

    @Test
    void detailWithoutTenantIsRejected() {
        TenantContext.clear();
        assertThrows(TenantContextMissingException.class, () -> dao.findById("shared-user", "A-NEW"));
    }

    @Test
    void detailReturnsOwnedOrderWithUnchangedPublicFields() {
        var order = dao.findById("shared-user", "A-NEW").orElseThrow();
        assertEquals("shared-user", order.userId());
        assertEquals(new UserOrderDao.OrderView("A-NEW", "P2", "键盘", "99.00", "SHIPPED",
            "A 新地址", "A 新轨迹", 2000L), order.view());
    }

    @Test
    void tenantCaseAndExplicitDefaultFollowExistingDatabaseContract() {
        TenantContext.set("TENANT-a");
        assertEquals("TENANT-a", TenantContext.get(), "业务租户上下文保留原大小写");
        assertEquals(2, dao.listByUser("shared-user").size(), "按现有数据库排序规则匹配业务租户");
        assertTrue(dao.findById("shared-user", "A-NEW").isPresent());
        TenantContext.set("DeFaUlT");
        assertEquals(TenantContext.DEFAULT, TenantContext.get());
        assertEquals(List.of("DEFAULT-ORDER"), dao.listByUser("legacy-user").stream()
            .map(UserOrderDao.OrderView::orderId).toList());
        assertTrue(dao.findById("legacy-user", "DEFAULT-ORDER").isPresent());
        assertTrue(dao.listByUser("shared-user").isEmpty());
    }

    @Test
    void unknownAndSqlLikeKeysRemainBoundValues() {
        assertTrue(dao.listByUser("unknown-user").isEmpty());
        assertTrue(dao.findById("shared-user", "unknown-order").isEmpty());
        assertTrue(dao.listByUser("shared-user' OR 1=1 -- ").isEmpty());
        assertTrue(dao.findById("shared-user' OR 1=1 -- ", "A-NEW").isEmpty());
        assertTrue(dao.findById("shared-user", "A-NEW' OR 1=1 -- ").isEmpty());
    }

    @Test
    void authenticatedHttpListRestoresJwtTenantAfterSchedulerSwitch() {
        TenantContext.clear();
        webTestClient.get().uri("/api/customer/user/orders")
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
            .header("X-Tenant-Id", "tenant-B")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].orderId").isEqualTo("A-NEW")
            .jsonPath("$[1].orderId").isEqualTo("A-OLD");
        webTestClient.get().uri("/api/customer/user/orders?userId=other-user")
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-B"))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].orderId").isEqualTo("B-SECRET");
    }

    @Test
    void authenticatedHttpDetailRestoresJwtTenantAfterSchedulerSwitch() {
        TenantContext.clear();
        webTestClient.get().uri("/api/customer/user/orders/A-NEW")
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.orderId").isEqualTo("A-NEW")
            .jsonPath("$.logisticsTrace").isEqualTo("A 新轨迹")
            .jsonPath("$.userId").doesNotExist();
        for (String inaccessible : List.of("B-SECRET", "A-OTHER", "unknown-order")) {
            webTestClient.get().uri("/api/customer/user/orders/" + inaccessible)
                .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
                .exchange().expectStatus().isNotFound();
        }
        webTestClient.get().uri("/api/customer/user/orders/B-SECRET")
            .header(HttpHeaders.AUTHORIZATION, bearer("tenant-B"))
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.receiverAddr").isEqualTo("B 私有地址")
            .jsonPath("$.logisticsTrace").isEqualTo("B 私有轨迹");
    }

    @Test
    void databaseQueryFailuresKeepSqlCauseAndReturn503() {
        // 只临时改名本测试随机库中的表，稳定制造查询异常，finally 恢复后继续其他断言。
        jdbc.execute("RENAME TABLE cw_order TO cw_order_unavailable");
        try {
            var listFailure = assertThrows(IllegalStateException.class, () -> dao.listByUser("shared-user"));
            assertInstanceOf(SQLException.class, listFailure.getCause());
            var detailFailure = assertThrows(IllegalStateException.class, () -> dao.findById("shared-user", "A-NEW"));
            assertInstanceOf(SQLException.class, detailFailure.getCause());
            TenantContext.clear();
            for (String path : List.of("/api/customer/user/orders", "/api/customer/user/orders/A-NEW")) {
                webTestClient.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearer("tenant-A"))
                    .exchange().expectStatus().isEqualTo(503);
            }
        } finally {
            jdbc.execute("RENAME TABLE cw_order_unavailable TO cw_order");
        }
    }

    private String bearer(String tenantId) {
        return "Bearer " + jwtService.issue("shared-user", "alice", "Alice", tenantId);
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
