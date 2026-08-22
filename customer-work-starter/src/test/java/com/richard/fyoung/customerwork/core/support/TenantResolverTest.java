package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TenantResolver} 的历史平台 scope 兼容测试。 */
class TenantResolverTest {

    private final TenantResolver resolver = new TenantResolver(new CustomerWorkProperties());

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void legacyPlatformSessionPrefix_shouldResolveToDefaultWithoutChangingSessionId() {
        assertEquals("__platform__", resolver.resolve("__platform__:conversation-1"),
            "短期 AgentState 仍需旧 userId 才能读回历史会话");
        assertEquals("default", resolver.resolveDataScope("__platform__:conversation-1"));
        assertEquals("default", resolver.resolveDataScope("__platform__"));
        assertEquals("tenant-a", resolver.resolveDataScope("tenant-a:conversation-1"));
        assertEquals("xxplatformyy", resolver.resolveDataScope("xxplatformyy:conversation-1"));
    }

    @Test
    void trustedTenantContext_shouldOverrideForgedSessionPrefix() {
        TenantContext.set("tenant-a");

        assertEquals("tenant-a", resolver.resolve("tenant-b:conversation-1"));
        assertEquals("tenant-a", resolver.resolveDataScope("tenant-b:conversation-1"));
    }
}
