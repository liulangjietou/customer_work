package com.richard.fyoung.customerwork.safety.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用 MySQL 的不区分大小写排序规则验证生产租户插件的读、改、删与连接过滤。 */
class TenantPredicateCaseIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static String ownDatabase;

    @BeforeAll
    static void createOwnDatabase() throws Exception {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 500); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，无法验证租户比较语义"); }
        String candidate = "tenant_case_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            ownDatabase = candidate;
        }
        execute("CREATE TABLE tenant_case_row(id BIGINT PRIMARY KEY, tenant_id VARCHAR(64), val VARCHAR(32), KEY ix_tenant(tenant_id))");
    }

    @BeforeEach
    void prepareRows() throws Exception {
        execute("DELETE FROM tenant_case_row");
        execute("INSERT INTO tenant_case_row VALUES (1,'acme','own'),(2,'ACME','foreign'),(3,'other','unrelated')");
        TenantContext.set("acme");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (ownDatabase != null) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + ownDatabase); }
        }
    }

    @Test
    void selectDoesNotIncludeCaseVariantTenant() throws Exception {
        assertEquals(List.of(1L), ids(filtered("SELECT id FROM tenant_case_row ORDER BY id")));
    }

    @Test
    void updateCannotChangeCaseVariantTenant() throws Exception {
        assertEquals(1, execute(filtered("UPDATE tenant_case_row SET val='changed'")));
        assertEquals(List.of(1L), ids("SELECT id FROM tenant_case_row WHERE val='changed'"));
    }

    @Test
    void deleteCannotRemoveCaseVariantTenant() throws Exception {
        assertEquals(1, execute(filtered("DELETE FROM tenant_case_row")));
        assertEquals(List.of(2L, 3L), ids("SELECT id FROM tenant_case_row ORDER BY id"));
    }

    @Test
    void aliasesOnBothSidesOfJoinRemainTenantScoped() throws Exception {
        assertEquals(List.of(1L), ids(filtered("SELECT a.id FROM tenant_case_row a JOIN tenant_case_row b ON a.id=b.id ORDER BY a.id")));
    }

    @Test
    void insertStillStoresOriginalTenantText() throws Exception {
        execute(filtered("INSERT INTO tenant_case_row(id,val) VALUES (4,'new')"));
        try (var connection = DriverManager.getConnection(url(ownDatabase), USER, PASSWORD);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT tenant_id FROM tenant_case_row WHERE id=4")) {
            result.next();
            assertEquals("acme", result.getString(1));
        }
    }

    @Test
    void missingContextStillFailsBeforeQuery() {
        TenantContext.clear();
        assertThrows(TenantContextMissingException.class, () -> filtered("SELECT id FROM tenant_case_row"));
    }

    private static String filtered(String sql) { return TenantInterceptors.build().parserSingle(sql, null); }

    private static List<Long> ids(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url(ownDatabase), USER, PASSWORD);
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            var ids = new ArrayList<Long>();
            while (result.next()) ids.add(result.getLong(1));
            return ids;
        }
    }

    private static int execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url(ownDatabase), USER, PASSWORD);
             var statement = connection.createStatement()) { return statement.executeUpdate(sql); }
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }
}
