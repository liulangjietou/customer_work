package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelAssetMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型资产可见性和租户部署引用共享资产的兼容测试。 */
class ModelAssetServiceTest {

    @Test
    void batchLookup_shouldResolveDefaultAssetReferencedByBusinessTenantDeployment() {
        AiModelAssetMapper mapper = mock(AiModelAssetMapper.class);
        AiModelAsset shared = asset(5L, TenantContext.DEFAULT);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(shared));
        ModelAssetService service = new ModelAssetService(mapper);
        AiModelConfig deployment = new AiModelConfig();
        deployment.setAssetId(5L);
        deployment.setTenantId("tenant-a");

        Map<Long, AiModelAsset> result = service.findByModels(List.of(deployment));

        assertEquals(shared, result.get(5L));
        ArgumentCaptor<QueryWrapper<AiModelAsset>> wrapper = queryCaptor();
        verify(mapper).selectList(wrapper.capture());
        wrapper.getValue().getSqlSegment();
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("tenant-a"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(TenantContext.DEFAULT));
    }

    @Test
    void singleTenantMode_shouldPreserveLegacyUnscopedAssetOptions() {
        AiModelAssetMapper mapper = mock(AiModelAssetMapper.class);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        ModelAssetService service = new ModelAssetService(mapper);

        service.listVisible(TenantContext.DEFAULT, false);

        ArgumentCaptor<QueryWrapper<AiModelAsset>> wrapper = queryCaptor();
        verify(mapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertFalse(sql.contains("tenant_id"), sql);
    }

    private AiModelAsset asset(Long id, String tenantId) {
        AiModelAsset asset = new AiModelAsset();
        asset.setId(id);
        asset.setTenantId(tenantId);
        return asset;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<QueryWrapper<AiModelAsset>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }
}
