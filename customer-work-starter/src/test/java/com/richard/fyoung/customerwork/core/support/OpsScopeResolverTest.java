package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.core.memory.MemorySubjectKey;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 运营统计分区解析。
 *
 * <p>可信请求下，运营分区与 {@link TenantResolver} 都应落到接入层确认的租户；
 * 用户级隐私隔离由 {@link MemorySubjectResolver} 在租户内继续细分。</p>
 * @author owlzhangfq@gmail.com
 */
class OpsScopeResolverTest {

    private final OpsScopeResolver resolver = new OpsScopeResolver();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
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
    void trustedRequest_shouldShareTenantScopeAndKeepMemorySubjectsIsolated() {
        String sessionId = "u42:conv-abc";
        TenantContext.set("tenantA");
        QuotaSubjectContext.set(QuotaSubject.user("u42"));

        String dataScope = new TenantResolver(new CustomerWorkProperties()).resolveDataScope(sessionId);
        MemorySubjectResolver memoryResolver = new MemorySubjectResolver();
        MemorySubjectKey user42 = memoryResolver.resolve(
            sessionId, MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);
        QuotaSubjectContext.set(QuotaSubject.user("u43"));
        MemorySubjectKey user43 = memoryResolver.resolve(
            "u43:conv-def", MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);

        assertEquals("tenantA", dataScope, "可信租户上下文不得被客户端会话前缀覆盖");
        assertEquals(dataScope, resolver.resolve(), "运营统计与数据行都归属同一租户");
        assertNotEquals(user42.scopeId(), user43.scopeId(), "长期记忆仍须按验签用户隔离");
    }
}
