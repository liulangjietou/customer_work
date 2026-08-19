package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 运营统计分区解析。
 *
 * <p>重点是它与 {@link TenantResolver} 的分野：后者是数据分区（从 sessionId 前缀解析，
 * 用户端解析出来的其实是用户标识），前者是运营分区（取租户）。两者混用过一次，
 * 代价是 CSAT 与知识盲区两个看板长期查不到任何数据。</p>
 * @author owlzhangfq@gmail.com
 */
class OpsScopeResolverTest {

    private final OpsScopeResolver resolver = new OpsScopeResolver();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void withTenantContext_shouldUseTenant() {
        TenantContext.set("tenantA");

        assertEquals("tenantA", resolver.resolve());
    }

    @Test
    void withoutTenantContext_shouldFallBackToDefault() {
        // 运营指标是旁路数据，缺上下文回落而非抛错——与持久层的 fail-closed 刻意相反
        assertEquals(TenantContext.DEFAULT, resolver.resolve());
    }

    @Test
    void shouldDifferFromDataScope() {
        // 用户端 sessionId 形如 u{userId}:conv-xxx：数据分区解析出用户（隔离要的正是这个），
        // 运营分区必须是租户，否则每个用户各成一张报表
        String sessionId = "u42:conv-abc";
        TenantContext.set("tenantA");

        String dataScope = new TenantResolver(new CustomerWorkProperties()).resolve(sessionId);

        assertEquals("u42", dataScope);
        assertNotEquals(dataScope, resolver.resolve());
    }
}
