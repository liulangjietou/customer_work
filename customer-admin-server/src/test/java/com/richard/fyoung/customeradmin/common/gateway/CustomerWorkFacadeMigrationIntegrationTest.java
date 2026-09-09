package com.richard.fyoung.customeradmin.common.gateway;

import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeAggregateRow;
import com.richard.fyoung.customeradmin.businessoutcome.gateway.BusinessOutcomeGateway;
import com.richard.fyoung.customeradmin.businessoutcome.gateway.BusinessOutcomeGatewayFactory;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Admin 跨库门面从空库迁移到真实业务 SQL 的 MySQL 回归测试。 */
class CustomerWorkFacadeMigrationIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USERNAME = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");

    @Test
    void emptyDatabaseShouldMigrateBeforeBusinessOutcomeQuery() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 Admin 跨库迁移回归测试");
        String database = "cw_admin_facade_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过");

        CustomerWorkFacade<BusinessOutcomeGateway> facade = null;
        try {
            CustomerWorkDbProperties connection = new CustomerWorkDbProperties();
            connection.setHost(HOST);
            connection.setPort(PORT);
            connection.setDatabase(database);
            connection.setUsername(USERNAME);
            connection.setPassword(PASSWORD);
            connection.setSchemaMigrationEnabled(true);
            AdminTenantProperties tenant = new AdminTenantProperties();
            tenant.setEnabled(false);
            facade = CustomerWorkFacade.builder("admin-schema-migration-test-pool", connection,
                    new AdminCrossDbTenantPlugins(tenant))
                .mapperClasses(BusinessOutcomeGatewayFactory.MAPPER_CLASSES)
                .build(BusinessOutcomeGatewayFactory::build);

            BusinessOutcomeAggregateRow aggregate = facade.get().mapper()
                .aggregate("default", null, 0L, 1_000L);

            assertEquals(0L, aggregate.getTotalSessions());
            // 客服端当前 schema 版本；starter 加迁移时这里要跟着涨
            assertEquals("24", query(database,
                "SELECT `version` FROM `flyway_schema_history` WHERE `success` = 1 "
                    + "ORDER BY `installed_rank` DESC LIMIT 1"));
            // BusinessOutcomeMapper 正是按 session_id 关联三张 cw_* 表的那条查询，
            // 跨 collation 时报 1267。门面建库后必须已经是对齐的，否则它只能继续靠
            // CAST(x AS BINARY) 绕开——那会让 session_id 索引失效。
            assertEquals("0", query(database,
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                    + "AND table_name LIKE 'cw\\_%' "
                    + "AND table_collation <> 'utf8mb4_unicode_ci'"));
            assertEquals("1", query(database,
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = 'cw_eval_dataset_release'"));
            assertEquals("1", query(database,
                "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'cw_agent_call_log' "
                    + "AND column_name = 'model_segment_count'"));
        } finally {
            if (facade != null) {
                facade.close();
            }
            dropDatabase(database);
        }
    }

    private boolean createDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(""), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
        } catch (Exception ignored) {
            // 清理失败不覆盖原始断言；随机库名可按 cw_admin_facade_* 识别。
        }
    }

    private String query(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1_500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String jdbcUrl(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
