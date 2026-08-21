package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ModelConfigService} 的 default 共享基线与租户写边界测试。 */
class ModelConfigServiceTenantVisibilityTest {

    private AiModelConfigMapper mapper;
    private ModelReferenceAccess modelReferenceAccess;
    private CrossTenantAuthority crossTenantAuthority;
    private AesGcmCryptoUtil cryptoUtil;
    private AdminModelFactory modelFactory;
    private AgentInstanceCache agentInstanceCache;
    private CustomerWorkConfigPublisher runtimeConfigPublisher;
    private ModelConfigService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiModelConfig.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        modelReferenceAccess = mock(ModelReferenceAccess.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        modelFactory = mock(AdminModelFactory.class);
        agentInstanceCache = mock(AgentInstanceCache.class);
        runtimeConfigPublisher = mock(CustomerWorkConfigPublisher.class);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        service = new ModelConfigService(
            mapper,
            modelReferenceAccess,
            cryptoUtil,
            modelFactory,
            agentInstanceCache,
            runtimeConfigPublisher,
            tenantProperties,
            crossTenantAuthority);
        when(modelReferenceAccess.findReferences(any())).thenReturn(List.of());
    }

    @Test
    void get_shouldExposeDefaultBaselineButHideCredentialFromOrdinaryTenant() {
        when(mapper.selectById(1L)).thenReturn(model(1L, TenantContext.DEFAULT));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("acme")) {
            ModelVO vo = service.get(1L);

            assertEquals("********（系统共享配置）", vo.getApiKeyMasked());
        }
    }

    @Test
    void get_shouldReturnNotFoundForAnotherBusinessTenant() {
        when(mapper.selectById(2L)).thenReturn(model(2L, "tenant-b"));

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("tenant-a")) {
            BizException exception = assertThrows(BizException.class, () -> service.get(2L));

            assertEquals(ResultCode.RESOURCE_NOT_FOUND, exception.getResultCode());
        }
    }

    @Test
    void create_shouldRejectDefaultSharedRecordFromOrdinaryDefaultUser() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            BizException exception = assertThrows(BizException.class,
                () -> service.create(request("shared-model")));

            assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
            verify(mapper, never()).insert(any(AiModelConfig.class));
        }
    }

    @Test
    void update_shouldRejectDefaultSharedRecordFromOrdinaryDefaultUser() {
        when(mapper.selectById(1L)).thenReturn(model(1L, TenantContext.DEFAULT));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            BizException exception = assertThrows(BizException.class,
                () -> service.update(1L, request("shared-model")));

            assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
            verify(mapper, never()).updateById(any(AiModelConfig.class));
        }
    }

    @Test
    void update_shouldRequireControlPlaneUserToReturnToDefaultViewForSharedRecord() {
        when(mapper.selectById(1L)).thenReturn(model(1L, TenantContext.DEFAULT));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("acme")) {
            BizException exception = assertThrows(BizException.class,
                () -> service.update(1L, request("shared-model")));

            assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
            verify(mapper, never()).updateById(any(AiModelConfig.class));
        }
    }

    @Test
    void update_shouldAllowControlPlaneUserInDefaultView() {
        when(mapper.selectById(1L)).thenReturn(model(1L, "DEFAULT"));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            service.update(1L, request("shared-model"));

            verify(mapper).updateById(any(AiModelConfig.class));
        }
    }

    @Test
    void updateSharedModel_shouldEvictAndEnqueueEveryReferenceInOwningTenant() {
        AiModelConfig sharedModel = model(1L, TenantContext.DEFAULT);
        when(mapper.selectById(1L)).thenReturn(sharedModel);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(modelReferenceAccess.findReferences(sharedModel)).thenReturn(List.of(
            reference("tenant-a", 11L, "agent-a"),
            reference("tenant-b", 22L, "agent-b")));
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return null;
        }).when(agentInstanceCache).evict("agent-a");
        doAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return null;
        }).when(agentInstanceCache).evict("agent-b");
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return null;
        }).when(runtimeConfigPublisher).publishForAgentId(11L);
        doAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return null;
        }).when(runtimeConfigPublisher).publishForAgentId(22L);

        TenantContext.set(TenantContext.DEFAULT);
        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            service.update(1L, request("shared-model"));
        } finally {
            TenantContext.clear();
        }

        verify(agentInstanceCache).evict("agent-a");
        verify(agentInstanceCache).evict("agent-b");
        verify(runtimeConfigPublisher).publishForAgentId(11L);
        verify(runtimeConfigPublisher).publishForAgentId(22L);
    }

    @Test
    void deleteSharedModel_shouldRejectReferenceOwnedByBusinessTenant() {
        AiModelConfig sharedModel = model(1L, TenantContext.DEFAULT);
        when(mapper.selectById(1L)).thenReturn(sharedModel);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(modelReferenceAccess.findReferences(sharedModel))
            .thenReturn(List.of(reference("tenant-a", 11L, "agent-a")));

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            BizException exception = assertThrows(BizException.class, () -> service.delete(1L));

            assertEquals(ResultCode.RESOURCE_IN_USE, exception.getResultCode());
            verify(mapper, never()).deleteById(1L);
        }
    }

    @Test
    void create_shouldKeepOrdinaryBusinessTenantConfigurationPrivate() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        ArgumentCaptor<AiModelConfig> saved = ArgumentCaptor.forClass(AiModelConfig.class);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("acme")) {
            service.create(request("tenant-model"));

            verify(mapper).insert(saved.capture());
            assertEquals("acme", saved.getValue().getTenantId());
            assertTrue(saved.getValue().getApiKey().length() > 0);
        }
    }

    @Test
    void testConnectivity_shouldNotPersistResultForReadOnlyDefaultSharedRecord() throws Exception {
        when(mapper.selectById(1L)).thenReturn(model(1L, TenantContext.DEFAULT));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null));

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("acme")) {
            ModelTestResult result = service.testConnectivity(1L).get();

            assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
            verify(mapper, never()).updateById(any(AiModelConfig.class));
        }
    }

    private MockedStatic<TenantSession> effectiveTenant(String tenantId) {
        MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
        tenantSession.when(TenantSession::effectiveTenant).thenReturn(tenantId);
        return tenantSession;
    }

    private AiModelConfig model(Long id, String tenantId) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setTenantId(tenantId);
        model.setModelName("shared-model");
        model.setProvider("openai");
        model.setBaseUrl("https://api.openai.com/v1");
        model.setModel("gpt-4o-mini");
        model.setApiKey(cryptoUtil.encrypt("sk-secret-value"));
        model.setIsDefault(1);
        model.setStatus(1);
        return model;
    }

    private ModelSaveRequest request(String name) {
        return new ModelSaveRequest(
            name,
            "openai",
            "sk-updated",
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            false,
            1);
    }

    private ModelAgentReference reference(String tenantId, Long agentId, String agentCode) {
        ModelAgentReference reference = new ModelAgentReference();
        reference.setTenantId(tenantId);
        reference.setAgentId(agentId);
        reference.setAgentCode(agentCode);
        return reference;
    }
}
