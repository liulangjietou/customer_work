package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link ModelConfigAccess} 的租户可见性与 fallback 优先级测试。 */
class ModelConfigAccessTest {

    private AiModelConfigMapper mapper;
    private AdminTenantProperties tenantProperties;
    private AiModelCertificationMapper certificationMapper;
    private ModelConfigAccess access;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new Configuration(), ""), AiModelConfig.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        certificationMapper = mock(AiModelCertificationMapper.class);
        tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        access = new ModelConfigAccess(mapper, certificationMapper, tenantProperties);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void businessTenant_shouldQueryOnlyOwnAndDefaultRows() {
        TenantContext.set("tenant-a");

        access.findVisibleById(7L);

        LambdaQueryWrapper<AiModelConfig> wrapper = capturedSelectOneWrapper();
        Collection<Object> values = wrapper.getParamNameValuePairs().values();
        assertTrue(values.containsAll(List.of(7L, "tenant-a", TenantContext.DEFAULT)));
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("tenant_id") || sql.contains("tenantId"), sql);
    }

    @Test
    void defaultTenant_shouldNotIncludeAnotherBusinessTenant() {
        TenantContext.set(TenantContext.DEFAULT);

        access.findVisibleById(7L);

        Collection<Object> values = capturedSelectOneWrapper().getParamNameValuePairs().values();
        assertTrue(values.containsAll(List.of(7L, TenantContext.DEFAULT)));
        assertFalse(values.contains("tenant-a"));
        assertEquals(2, values.size());
    }

    @Test
    void batchLookup_shouldScopeIdsToOwnAndDefaultRows() {
        TenantContext.set("tenant-a");

        access.listVisibleByIds(List.of(7L, 8L, 7L));

        ArgumentCaptor<LambdaQueryWrapper<AiModelConfig>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<AiModelConfig> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        Collection<Object> values = wrapper.getParamNameValuePairs().values();
        assertTrue(values.containsAll(List.of(7L, 8L, "tenant-a", TenantContext.DEFAULT)));
        assertEquals(1, values.stream().filter(value -> Long.valueOf(7L).equals(value)).count(), "输入 ID 应先去重");
        assertTrue(sql.contains("tenant_id") || sql.contains("tenantId"), sql);
    }

    @Test
    void missingContext_shouldFailClosedBeforeMapperAccess() {
        BizException exception = assertThrows(BizException.class, () -> access.findVisibleById(7L));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void preferredEnabled_shouldPutOwnModelBeforeDefaultFallback() {
        AiModelConfig sharedDefault = model(1L, TenantContext.DEFAULT, 1);
        AiModelConfig tenantOwned = model(2L, "tenant-a", 0);
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(sharedDefault, tenantOwned));
        TenantContext.set("tenant-a");

        List<AiModelConfig> result = access.listPreferredEnabled("dashscope");

        assertEquals(List.of(tenantOwned, sharedDefault), result);
        ArgumentCaptor<LambdaQueryWrapper<AiModelConfig>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<AiModelConfig> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Collection<Object> values = wrapper.getParamNameValuePairs().values();
        assertTrue(values.containsAll(List.of(1, "dashscope", "tenant-a", TenantContext.DEFAULT)));
    }

    @Test
    void disabled_shouldPreserveLegacyUnscopedRead() {
        tenantProperties.setEnabled(false);

        access.findVisibleById(7L);

        LambdaQueryWrapper<AiModelConfig> wrapper = capturedSelectOneWrapper();
        assertEquals(List.of(7L), List.copyOf(wrapper.getParamNameValuePairs().values()));
        assertFalse(wrapper.getSqlSegment().contains("tenant_id"));
    }

    @Test
    void certificationRequired_shouldReturnDeployment_whenSnapshotIsCurrent() {
        AiModelConfig deployment = model(7L, "tenant-a", 0);
        deployment.setCertificationRequired(1);
        deployment.setEndpointRevision(3);
        AiModelCertification certification = certification(7L, "tenant-a", 3,
            LocalDateTime.now().plusHours(1));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(deployment);
        when(certificationMapper.selectOne(any())).thenReturn(certification);
        TenantContext.set("tenant-a");

        AiModelConfig result = access.findVisibleById(7L);

        assertEquals(deployment, result);
    }

    @Test
    void certificationRequired_shouldFailClosed_whenSnapshotExpired() {
        AiModelConfig deployment = model(7L, "tenant-a", 0);
        deployment.setCertificationRequired(1);
        deployment.setEndpointRevision(3);
        AiModelCertification certification = certification(7L, "tenant-a", 3,
            LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(deployment);
        when(certificationMapper.selectOne(any())).thenReturn(certification);
        TenantContext.set("tenant-a");

        AiModelConfig result = access.findVisibleById(7L);

        assertEquals(null, result);
    }

    private AiModelConfig model(Long id, String tenantId, Integer isDefault) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setTenantId(tenantId);
        model.setIsDefault(isDefault);
        model.setStatus(1);
        model.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        model.setCertificationRequired(0);
        return model;
    }

    private AiModelCertification certification(Long modelId, String tenantId, Integer endpointRevision,
                                                 LocalDateTime validUntil) {
        AiModelCertification certification = new AiModelCertification();
        certification.setModelConfigId(modelId);
        certification.setTenantId(tenantId);
        certification.setStatus("PASSED");
        certification.setCertifiedEndpointRevision(endpointRevision);
        certification.setValidUntil(validUntil);
        return certification;
    }

    private LambdaQueryWrapper<AiModelConfig> capturedSelectOneWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<AiModelConfig>> captor = wrapperCaptor();
        verify(mapper).selectOne(captor.capture());
        LambdaQueryWrapper<AiModelConfig> wrapper = captor.getValue();
        // LambdaQueryWrapper 的参数表惰性生成，先渲染 SQL 再读取参数值。
        wrapper.getSqlSegment();
        return wrapper;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaQueryWrapper<AiModelConfig>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
