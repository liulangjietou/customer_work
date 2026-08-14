package com.richard.fyoung.customerwork.data.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.data.outbox.MybatisOutboxStore;
import com.richard.fyoung.customerwork.data.outbox.OutboxHandler;
import com.richard.fyoung.customerwork.data.outbox.OutboxMessage;
import com.richard.fyoung.customerwork.data.outbox.OutboxService;
import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkPersistenceConfig;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.infra.config.properties.OutboxProperties;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 门控测试：工单、审计事件和 Outbox 必须同成同败。 */
class TicketOutboxTransactionIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    @Test
    void create_shouldAtomicallyCommitOrRollbackTicketEventAndOutbox() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过工单 Outbox 事务门控测试");
        String database = "cw_outbox_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过");

        try (HikariDataSource dataSource = dataSource(database)) {
            migrate(dataSource, database);
            SqlSessionTemplate template = MybatisTestSupport.template(dataSource);
            MybatisTicketStore ticketStore = new MybatisTicketStore(
                template.getMapper(com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper.class),
                template.getMapper(com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper.class));
            OutboxService outboxService = new OutboxService(
                new MybatisOutboxStore(template.getMapper(OutboxMessageMapper.class)),
                new OutboxProperties(), List.of());
            OutboxTicketEventPublisher delegate = new OutboxTicketEventPublisher(outboxService, new ObjectMapper());
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            CustomerWorkTransactionExecutor executor = action ->
                transactionTemplate.execute(status -> action.get());

            TicketEventPublisher failingPublisher = (ticket, event) -> {
                delegate.publish(ticket, event);
                throw new IllegalStateException("simulated publish failure");
            };
            TicketService failingService = new TicketService(ticketStore, failingPublisher, executor);
            assertThrows(IllegalStateException.class, () -> failingService.createForSession(
                "S-ROLLBACK", "U-1", "rollback", TicketCategory.ORDER));
            assertEquals(0, count(dataSource, "cw_ticket"));
            assertEquals(0, count(dataSource, "cw_ticket_event"));
            assertEquals(0, count(dataSource, "cw_outbox_message"));

            TicketService service = new TicketService(ticketStore, delegate, executor);
            service.createForSession("S-COMMIT", "U-1", "commit", TicketCategory.ORDER);
            assertEquals(1, count(dataSource, "cw_ticket"));
            assertEquals(1, count(dataSource, "cw_ticket_event"));
            assertEquals(1, count(dataSource, "cw_outbox_message"));
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void outboxShouldDispatchAcrossTenantsWithProductionTenantInterceptor() throws Exception {
        assumeTrue(reachable(), "MySQL 不可达，跳过 Outbox 多租户门控测试");
        String database = "cw_outbox_tenant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assumeTrue(createDatabase(database), "MySQL 测试账号无建库权限，跳过");

        try (HikariDataSource dataSource = dataSource(database)) {
            CustomerWorkProperties customerWorkProperties = new CustomerWorkProperties();
            customerWorkProperties.getSession().getMysql().setDatabase(database);
            customerWorkProperties.getSession().getMysql().setMigrationEnabled(true);
            customerWorkProperties.getTenant().setEnabled(true);
            new CustomerWorkSchemaMigrator(dataSource, customerWorkProperties).afterPropertiesSet();

            SqlSessionFactory factory = new CustomerWorkPersistenceConfig()
                .customerWorkSqlSessionFactory(dataSource, customerWorkProperties);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            MybatisOutboxStore store = new MybatisOutboxStore(template.getMapper(OutboxMessageMapper.class));
            AtomicReference<String> handledTenant = new AtomicReference<>();
            OutboxHandler handler = new OutboxHandler() {
                @Override
                public String type() {
                    return "tenant-probe";
                }

                @Override
                public void handle(OutboxMessage message) {
                    handledTenant.set(TenantContext.require());
                }
            };
            OutboxService service = new OutboxService(store, new OutboxProperties(), List.of(handler));

            TenantContext.runWith("tenant-a", () -> service.publish("tenant-probe", "TK-1", "{}"));
            assertEquals("tenant-a", scalar(dataSource,
                "SELECT tenant_id FROM cw_outbox_message WHERE aggregate_id = 'TK-1'"));
            assertEquals(1, service.dispatchDue());
            assertEquals("tenant-a", handledTenant.get());
            assertNull(TenantContext.get());
        } finally {
            dropDatabase(database);
        }
    }

    private void migrate(HikariDataSource dataSource, String database) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setDatabase(database);
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
    }

    private int count(HikariDataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String scalar(HikariDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private boolean createDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + database + "`");
        } catch (Exception ignored) {
            // 随机测试库清理失败不覆盖原始断言，可按 cw_outbox_tx_* 识别。
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT
            + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", USERNAME, PASSWORD);
    }

    private HikariDataSource dataSource(String database) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        dataSource.setUsername(USERNAME);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaximumPoolSize(3);
        dataSource.setPoolName("ticket-outbox-tx-test");
        return dataSource;
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
