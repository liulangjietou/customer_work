package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实迁移、跨库装配与租户插件共同验证排行，不用 mock 代替行级隔离。 */
class KnowledgeGapGatewayIntegrationTest {

    @Test
    void boardMustUseCurrentScopeKeepTenantIsolationAndExposeReadFailure() throws Exception {
        var connection = new CustomerWorkDbProperties();
        connection.setHost(System.getenv().getOrDefault("MYSQL_HOST", "localhost"));
        connection.setPort(Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306")));
        connection.setUsername(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        connection.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "root"));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(connection.getHost(), connection.getPort()), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过知识缺口跨库测试");
        }
        String database = "knowledge_gap_" + UUID.randomUUID().toString().replace("-", "");
        connection.setDatabase("");
        String serverUrl = connection.jdbcUrl();
        try (var db = DriverManager.getConnection(serverUrl, connection.getUsername(), connection.getPassword());
             var statement = db.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        connection.setDatabase(database);
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        var provider = new OpsGatewayProvider(connection, new AdminCrossDbTenantPlugins(tenant));
        try {
            var service = new OpsAdminService(provider);
            TenantContext.set("tenant-a");
            var store = provider.get().knowledgeGap();
            store.recordMiss("如何申请电子发票", "tenant-a", 1L);
            store.recordMiss("如何申请电子发票", "tenant-a", 2L);
            TenantContext.set("tenant-b");
            store.recordMiss("租户乙的问题", "tenant-b", 3L);
            store.recordMiss("相同分区也不能混入租户甲", "tenant-a", 4L);
            assertEquals("租户乙的问题", service.topKnowledgeGaps(null, 50).get(0).question());

            TenantContext.set("tenant-a");
            var gaps = service.topKnowledgeGaps(null, 50);
            assertEquals(1, gaps.size());
            assertEquals("如何申请电子发票", gaps.get(0).question());
            assertEquals(2, gaps.get(0).missCount());
            assertTrue(service.topKnowledgeGaps("tenant-b", 50).isEmpty(),
                "显式兼容分区参数仍叠加当前租户过滤");
            TenantContext.clear();
            assertThrows(RuntimeException.class, () -> service.topKnowledgeGaps(null, 50),
                "缺失租户不能因默认分区而绕过 SQL 插件");

            TenantContext.set("tenant-a");
            try (var db = DriverManager.getConnection(connection.jdbcUrl(),
                connection.getUsername(), connection.getPassword()); var statement = db.createStatement()) {
                statement.execute("DROP TABLE cw_knowledge_gap");
            }
            assertThrows(RuntimeException.class, () -> service.topKnowledgeGaps(null, 50),
                "连接可用但查询失败时必须返回错误，而不是空榜");
        } finally {
            TenantContext.clear();
            provider.close();
            try (var db = DriverManager.getConnection(serverUrl, connection.getUsername(), connection.getPassword());
                 var statement = db.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }
}
