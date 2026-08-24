package com.richard.fyoung.customeradmin.common.gateway;

import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admin 访问客服端库前的 schema 门禁测试。 */
class CustomerWorkFacadeTest {

    private static final String H2_URL =
        "jdbc:h2:mem:customer_work_facade_test;DB_CLOSE_DELAY=-1;MODE=MySQL";

    @Test
    void getShouldMigrateBeforeExposingFacadeAndCacheSuccess() {
        AtomicBoolean migrated = new AtomicBoolean();
        AtomicInteger migrationCalls = new AtomicInteger();
        Object expected = new Object();
        CustomerWorkFacade<Object> facade = builder()
            .schemaMigrator(dataSource -> {
                migrationCalls.incrementAndGet();
                migrated.set(true);
            })
            .build(gateway -> {
                assertTrue(migrated.get(), "业务 Mapper 暴露前必须先完成客服端库迁移");
                return expected;
            });

        try {
            assertSame(expected, facade.get());
            assertSame(expected, facade.get());
            assertEquals(1, migrationCalls.get(), "成功迁移后的门面应缓存复用");
        } finally {
            facade.close();
        }
    }

    @Test
    void migrationFailureShouldNotExposeOrCacheFacadeAndShouldRetry() {
        AtomicInteger migrationCalls = new AtomicInteger();
        AtomicInteger assemblerCalls = new AtomicInteger();
        CustomerWorkFacade<Object> facade = builder()
            .schemaMigrator(dataSource -> {
                migrationCalls.incrementAndGet();
                throw new IllegalStateException("schema migration failed");
            })
            .build(gateway -> {
                assemblerCalls.incrementAndGet();
                return new Object();
            });

        assertThrows(IllegalStateException.class, facade::get);
        assertThrows(IllegalStateException.class, facade::get);
        assertEquals(2, migrationCalls.get(), "失败不能缓存，客服端库恢复后必须允许重试");
        assertEquals(0, assemblerCalls.get(), "迁移失败时不得暴露可能执行坏 SQL 的 Mapper");
        facade.close();
    }

    @Test
    void getShouldSkipMigrationWhenDbaModeDisablesIt() {
        AtomicInteger migrationCalls = new AtomicInteger();
        CustomerWorkFacade<Object> facade = builder(false)
            .schemaMigrator(dataSource -> migrationCalls.incrementAndGet())
            .build(gateway -> new Object());

        try {
            facade.get();
            assertEquals(0, migrationCalls.get());
        } finally {
            facade.close();
        }
    }

    private CustomerWorkFacade.Builder builder() {
        return builder(true);
    }

    private CustomerWorkFacade.Builder builder(boolean schemaMigrationEnabled) {
        AdminTenantProperties tenant = new AdminTenantProperties();
        tenant.setEnabled(false);
        return CustomerWorkFacade.builder("customer-work-facade-test-pool",
                new TestConnection(schemaMigrationEnabled), new AdminCrossDbTenantPlugins(tenant))
            .driverClassName("org.h2.Driver");
    }

    private static final class TestConnection implements CustomerWorkDbConnection {

        private final boolean schemaMigrationEnabled;

        private TestConnection(boolean schemaMigrationEnabled) {
            this.schemaMigrationEnabled = schemaMigrationEnabled;
        }

        @Override
        public String jdbcUrl() {
            return H2_URL;
        }

        @Override
        public String getUsername() {
            return "sa";
        }

        @Override
        public String getPassword() {
            return "";
        }

        @Override
        public boolean isSchemaMigrationEnabled() {
            return schemaMigrationEnabled;
        }
    }
}
