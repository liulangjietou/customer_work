package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.ModelAgentReferenceMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ModelReferenceAccess} 的共享跨租户扫描与私有租户边界测试。 */
class ModelReferenceAccessTest {

    private static final String STATEMENT_ID =
        ModelAgentReferenceMapper.class.getName() + ".findReferences";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sharedModel_shouldUseExplicitCrossTenantScan() {
        ModelAgentReferenceMapper mapper = mock(ModelAgentReferenceMapper.class);
        AdminTenantProperties properties = enabledTenantProperties();
        ModelReferenceAccess access = new ModelReferenceAccess(mapper, properties);
        AiModelConfig shared = model(7L, TenantContext.DEFAULT);
        when(mapper.findReferences(7L, null)).thenAnswer(invocation -> {
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine(STATEMENT_ID));
            return List.of(reference("tenant-a", 11L, "agent-a"));
        });
        TenantContext.set(TenantContext.DEFAULT);

        List<ModelAgentReference> result = access.findReferences(shared);

        assertEquals(1, result.size());
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine(STATEMENT_ID),
            "跨租户豁免必须在扫描结束后清理");
        verify(mapper).findReferences(7L, null);
    }

    @Test
    void privateModel_shouldKeepCurrentTenantScope() {
        ModelAgentReferenceMapper mapper = mock(ModelAgentReferenceMapper.class);
        ModelReferenceAccess access = new ModelReferenceAccess(mapper, enabledTenantProperties());
        AiModelConfig privateModel = model(8L, "tenant-a");
        when(mapper.findReferences(8L, "tenant-a")).thenAnswer(invocation -> {
            assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine(STATEMENT_ID));
            return List.of();
        });
        TenantContext.set("tenant-a");

        access.findReferences(privateModel);

        verify(mapper).findReferences(8L, "tenant-a");
    }

    @Test
    void privateModel_shouldRejectAnotherTenantContext() {
        ModelAgentReferenceMapper mapper = mock(ModelAgentReferenceMapper.class);
        ModelReferenceAccess access = new ModelReferenceAccess(mapper, enabledTenantProperties());
        TenantContext.set("tenant-b");

        assertThrows(IllegalStateException.class,
            () -> access.findReferences(model(8L, "tenant-a")));
    }

    private AdminTenantProperties enabledTenantProperties() {
        AdminTenantProperties properties = new AdminTenantProperties();
        properties.setEnabled(true);
        return properties;
    }

    private AiModelConfig model(Long id, String tenantId) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setTenantId(tenantId);
        return model;
    }

    private ModelAgentReference reference(String tenantId, Long agentId, String agentCode) {
        ModelAgentReference reference = new ModelAgentReference();
        reference.setTenantId(tenantId);
        reference.setAgentId(agentId);
        reference.setAgentCode(agentCode);
        return reference;
    }
}
