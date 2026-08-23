package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 内存语义缓存的运行时配置失效边界测试。 */
class SemanticCacheRuntimeConfigInvalidationTest {

    private final InMemorySemanticCacheStore store = new InMemorySemanticCacheStore();
    private final SemanticCacheService service = new SemanticCacheService(
        store,
        null,
        mock(MultiAgentOrchestrator.class),
        new TenantResolver(new CustomerWorkProperties()),
        new SemanticCacheProperties());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void invalidateCurrentTenant_shouldClearAllScopesAndKeepOtherTenant() {
        TenantContext.runWith("Tenant-A", () -> {
            store.save(entry("user-1", "A-1"));
            store.save(entry("user-2", "A-2"));
        });
        TenantContext.runWith("tenant-b", () -> store.save(entry("user-1", "B-1")));

        TenantContext.runWith("tenant-a", service::invalidateCurrentTenant);

        assertEquals(0L, TenantContext.callWith("Tenant-A", () -> store.count("user-1")));
        assertEquals(0L, TenantContext.callWith("Tenant-A", () -> store.count("user-2")));
        assertEquals(1L, TenantContext.callWith("tenant-b", () -> store.count("user-1")),
            "内存模式也必须与 JDBC tenant_id 语义一致，不得误清其他租户");
    }

    @Test
    void invalidateCurrentTenant_shouldFailClosedWithoutTenantContext() {
        assertThrows(TenantContextMissingException.class, service::invalidateCurrentTenant);
    }

    private SemanticCacheEntry entry(String scopeId, String answer) {
        long now = System.currentTimeMillis();
        return SemanticCacheEntry.of(scopeId, "consult", "如何开票", "1.0,0.0", answer, now);
    }
}
