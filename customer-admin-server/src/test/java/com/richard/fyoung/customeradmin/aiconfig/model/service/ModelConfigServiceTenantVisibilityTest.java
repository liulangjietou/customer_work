package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretRotationRequest;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    private ModelAssetService modelAssetService;
    private SecretRefService secretRefService;
    private ModelHealthService modelHealthService;
    private ModelImpactService modelImpactService;
    private ModelCertificationService modelCertificationService;
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
        modelAssetService = mock(ModelAssetService.class);
        secretRefService = mock(SecretRefService.class);
        modelHealthService = mock(ModelHealthService.class);
        modelImpactService = mock(ModelImpactService.class);
        modelCertificationService = mock(ModelCertificationService.class);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        AiModelAsset asset = new AiModelAsset();
        asset.setId(10L);
        when(modelAssetService.resolveOrCreate(anyString(), anyString(), any(), eq(true))).thenReturn(asset);
        when(secretRefService.createLocal(anyString(), anyString(), anyString(), isNull()))
            .thenAnswer(invocation -> new SecretRefService.SecretWriteResult(
                20L, 1, cryptoUtil.encrypt(invocation.getArgument(2)), null));
        when(secretRefService.rotateOrCreate(any(AiModelConfig.class), anyString(), isNull()))
            .thenAnswer(invocation -> new SecretRefService.SecretWriteResult(
                20L, 2, cryptoUtil.encrypt(invocation.getArgument(1)), null));
        when(modelHealthService.findSnapshots(any())).thenReturn(Map.of());
        service = new ModelConfigService(
            mapper,
            modelReferenceAccess,
            modelAssetService,
            secretRefService,
            modelHealthService,
            modelImpactService,
            modelCertificationService,
            agentInstanceCache,
            runtimeConfigPublisher,
            tenantProperties,
            crossTenantAuthority,
            new ModelEndpointPolicy(List::of,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
        when(modelReferenceAccess.findReferences(any())).thenReturn(List.of());
    }

    @Test
    void get_shouldExposeDefaultBaselineButHideCredentialFromOrdinaryTenant() {
        when(mapper.selectById(1L)).thenReturn(model(1L, TenantContext.DEFAULT));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("acme")) {
            ModelVO vo = service.get(1L);

            assertEquals("********", vo.getApiKeyMasked());
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
        }).when(agentInstanceCache).invalidate("agent-a");
        doAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return null;
        }).when(agentInstanceCache).invalidate("agent-b");
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

        verify(agentInstanceCache).invalidate("agent-a");
        verify(agentInstanceCache).invalidate("agent-b");
        verify(runtimeConfigPublisher).publishForAgentId(11L);
        verify(runtimeConfigPublisher).publishForAgentId(22L);
    }

    @Test
    void deleteSharedModel_shouldRejectReferenceOwnedByBusinessTenant() {
        AiModelConfig sharedModel = model(1L, TenantContext.DEFAULT);
        when(mapper.selectById(1L)).thenReturn(sharedModel);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        doThrow(new BizException(ResultCode.RESOURCE_IN_USE, "模型部署存在生效引用"))
            .when(modelImpactService).requireAllowed(sharedModel, null, "DELETE");

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant(TenantContext.DEFAULT)) {
            BizException exception = assertThrows(BizException.class, () -> service.delete(1L));

            assertEquals(ResultCode.RESOURCE_IN_USE, exception.getResultCode());
            verify(mapper, never()).deleteById(1L);
        }
    }

    @Test
    void rotateCredential_shouldPreflightAsRotationAndRepublishExperimentAgent() {
        AiModelConfig deployment = model(1L, "tenant-a");
        deployment.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        when(mapper.selectById(1L)).thenReturn(deployment);
        when(modelReferenceAccess.findReferences(deployment))
            .thenReturn(List.of(reference("tenant-a", 11L, "experiment-agent")));
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return null;
        }).when(runtimeConfigPublisher).publishForAgentId(11L);

        TenantContext.set("tenant-a");
        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("tenant-a")) {
            service.rotateCredential(1L, new SecretRotationRequest("sk-rotated", null));
        } finally {
            TenantContext.clear();
        }

        verify(modelImpactService).requireAllowed(deployment, "tenant-a", "ROTATE");
        verify(agentInstanceCache).invalidate("experiment-agent");
        verify(runtimeConfigPublisher).publishForAgentId(11L);
    }

    @Test
    void rotateCredential_shouldStillPreflightWhenDeploymentRowIsInactive() {
        AiModelConfig deployment = model(1L, "tenant-a");
        deployment.setStatus(0);
        deployment.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
        when(mapper.selectById(1L)).thenReturn(deployment);
        doThrow(new BizException(ResultCode.RESOURCE_IN_USE, "在线实验撤流尚未确认"))
            .when(modelImpactService).requireAllowed(deployment, "tenant-a", "ROTATE");

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("tenant-a")) {
            BizException exception = assertThrows(BizException.class,
                () -> service.rotateCredential(1L, new SecretRotationRequest("sk-rotated", null)));

            assertEquals(ResultCode.RESOURCE_IN_USE, exception.getResultCode());
        }

        verify(secretRefService, never()).rotateOrCreate(any(), anyString(), any());
        verify(mapper, never()).updateById(any(AiModelConfig.class));
    }

    @Test
    void impact_shouldPreserveRotateActionForImpactAnalysis() {
        AiModelConfig deployment = model(1L, "tenant-a");
        when(mapper.selectById(1L)).thenReturn(deployment);

        try (MockedStatic<TenantSession> tenantSession = effectiveTenant("tenant-a")) {
            service.impact(1L, "rotate");
        }

        verify(modelImpactService).query(deployment, "tenant-a", "ROTATE");
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
        when(modelHealthService.probe(eq(1L), any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null)));

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
