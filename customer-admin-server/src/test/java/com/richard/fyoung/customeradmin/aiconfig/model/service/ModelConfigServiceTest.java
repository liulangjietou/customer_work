package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * {@link ModelConfigService} 单测：AppKey 加密落库/脱敏回显、默认模型互斥事务、连通性测试装配。
 * @author owlzhangfq@gmail.com
 */
class ModelConfigServiceTest {

    private AiModelConfigMapper mapper;
    private ModelHealthService modelHealthService;
    private ModelConfigService service;

    /**
     * MyBatis-Plus 的 LambdaUpdateWrapper/LambdaQueryWrapper 依赖实体的 TableInfo 缓存，
     * 该缓存平时由 Spring 容器启动扫描 Mapper 时自动注册；纯 Mockito 单测没有容器，需要手动注册一次，
     * 否则 clearOtherDefaults() 里的 LambdaUpdateWrapper 会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiModelConfig.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        ModelAssetService modelAssetService = mock(ModelAssetService.class);
        SecretRefService secretRefService = mock(SecretRefService.class);
        modelHealthService = mock(ModelHealthService.class);
        ModelImpactService modelImpactService = mock(ModelImpactService.class);
        ModelCertificationService modelCertificationService = mock(ModelCertificationService.class);
        ModelReferenceAccess modelReferenceAccess = mock(ModelReferenceAccess.class);
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        // 16 字节测试密钥，满足 AES-128 长度要求
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher runtimeConfigPublisher =
            mock(com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.class);
        // 多租户默认关闭：本类既有用例覆盖的是加解密与默认模型互斥，与租户可见性无关，
        // 关闭后行为与引入两级可见性之前完全一致
        com.richard.fyoung.customeradmin.tenant.AdminTenantProperties tenantProperties =
            new com.richard.fyoung.customeradmin.tenant.AdminTenantProperties();
        tenantProperties.setEnabled(false);
        AiModelAsset asset = new AiModelAsset();
        asset.setId(10L);
        when(modelAssetService.resolveOrCreate(anyString(), anyString(), any(), eq(false))).thenReturn(asset);
        when(secretRefService.createLocal(anyString(), anyString(), anyString(), isNull()))
            .thenAnswer(invocation -> new SecretRefService.SecretWriteResult(
                20L, 1, cryptoUtil.encrypt(invocation.getArgument(2)), null));
        when(modelHealthService.findSnapshots(any())).thenReturn(Map.of());
        service = new ModelConfigService(mapper, modelReferenceAccess, modelAssetService, secretRefService,
            modelHealthService, modelImpactService,
            modelCertificationService,
            agentInstanceCache, runtimeConfigPublisher, tenantProperties,
            mock(com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority.class), endpointPolicy());
    }

    @Test
    void create_shouldEncryptApiKey_notStorePlainText() {
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "sk-plain-secret-1234", "https://api.openai.com/v1", "gpt-4o-mini",
            false, 1);

        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        service.create(request);

        verify(mapper).insert(captor.capture());
        AiModelConfig saved = captor.getValue();
        assertNotEquals("sk-plain-secret-1234", saved.getApiKey());
        assertTrue(saved.getApiKey().length() > 0);
    }

    @Test
    void create_shouldRejectMissingApiKey() {
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", null, "https://api.openai.com/v1", "gpt-4o-mini",
            false, 1);

        assertThrows(RuntimeException.class, () -> service.create(request));
    }

    @Test
    void create_shouldKeepCurrentDefaultUntilCandidateIsActivated() {
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "sk-secret", "https://api.openai.com/v1", "gpt-4o-mini",
            true, 1);

        service.create(request);

        verify(mapper, never()).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    void create_shouldNotTouchOtherDefaults_whenNotDefault() {
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "sk-secret", "https://api.openai.com/v1", "gpt-4o-mini",
            false, 1);

        service.create(request);

        verify(mapper, times(0)).update(any(), any());
    }

    @Test
    void update_shouldSwitchDefaultOnlyWhenCertifiedCandidateBecomesActive() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        existing.setTenantId(TenantContext.DEFAULT);
        existing.setAssetId(10L);
        existing.setProvider("openai");
        existing.setBaseUrl("https://api.openai.com/v1");
        existing.setModel("gpt-4o-mini");
        existing.setLifecycleStatus("DRAFT");
        existing.setStatus(0);
        when(mapper.selectById(1L)).thenReturn(existing);
        ModelSaveRequest request = new ModelSaveRequest(
            10L, null, null, null, null, null, null, null, null,
            null, null, null, null, "gpt-4o", null, "openai", "", null,
            "https://api.openai.com/v1", null, null, "gpt-4o-mini", true, 1, "ACTIVE");

        service.update(1L, request);

        verify(mapper).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    void update_shouldKeepOldApiKey_whenRequestApiKeyBlank() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        existing.setTenantId(TenantContext.DEFAULT);
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        String originalCipher = cryptoUtil.encrypt("sk-original-secret");
        existing.setApiKey(originalCipher);
        existing.setBaseUrl("https://api.openai.com/v1");
        when(mapper.selectById(1L)).thenReturn(existing);

        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "", "https://api.openai.com/v1", "gpt-4o-mini",
            false, 1);

        service.update(1L, request);

        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getApiKey());
    }

    @Test
    void update_shouldRequireNewCredential_whenBaseUrlChangesWithExistingCredential() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        existing.setTenantId(TenantContext.DEFAULT);
        existing.setApiKey("legacy-cipher");
        existing.setBaseUrl("https://api.openai.com/v1");
        when(mapper.selectById(1L)).thenReturn(existing);
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "", "https://new-api.example.com/v1", "gpt-4o-mini",
            false, 1);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, request));

        assertEquals(ResultCode.PARAM_MISSING, exception.getResultCode());
        verify(mapper, never()).updateById(any(AiModelConfig.class));
    }

    @Test
    void update_shouldAllowBaseUrlChangeWithoutCredential_whenModelHasNoExistingCredential() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        existing.setTenantId(TenantContext.DEFAULT);
        existing.setProvider("openai");
        existing.setBaseUrl("https://old-api.example.com/v1");
        existing.setModel("local-model");
        existing.setStatus(0);
        when(mapper.selectById(1L)).thenReturn(existing);
        ModelSaveRequest request = new ModelSaveRequest(
            "local", "openai", "", "https://new-api.example.com/v1", "local-model",
            false, 0);

        service.update(1L, request);

        verify(mapper).updateById(any(AiModelConfig.class));
    }

    @Test
    void create_shouldRejectPrivateEndpointBeforePersistingCredential() {
        ModelSaveRequest request = new ModelSaveRequest(
            "internal", "openai", "sk-secret", "http://10.0.0.8/v1", "local-model",
            false, 0);

        BizException exception = assertThrows(BizException.class, () -> service.create(request));

        assertEquals(ResultCode.MODEL_ENDPOINT_FORBIDDEN, exception.getResultCode());
        verify(mapper, never()).insert(any(AiModelConfig.class));
    }

    @Test
    void get_shouldReturnMaskedApiKey_notCiphertextOrPlainText() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        String plainKey = "sk-abcd1234wxyz";
        String cipher = cryptoUtil.encrypt(plainKey);
        existing.setApiKey(cipher);
        existing.setIsDefault(1);
        when(mapper.selectById(1L)).thenReturn(existing);

        ModelVO vo = service.get(1L);

        assertNotEquals(plainKey, vo.getApiKeyMasked());
        assertNotEquals(cipher, vo.getApiKeyMasked());
        assertEquals("********", vo.getApiKeyMasked());
        assertTrue(vo.getIsDefault());
    }

    @Test
    void get_shouldRedactLegacyEndpointCredentials() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        existing.setBaseUrl("https://user:secret@models.example.com/v1?api_key=legacy-token");
        when(mapper.selectById(1L)).thenReturn(existing);

        ModelVO vo = service.get(1L);

        assertEquals("__MODEL_ENDPOINT_REDACTED__", vo.getBaseUrl());

        existing.setBaseUrl("https://[invalid-model-endpoint");
        assertEquals("__MODEL_ENDPOINT_REDACTED__", service.get(1L).getBaseUrl());

        existing.setBaseUrl("https://models.example.com/v1");
        assertEquals("https://models.example.com/v1", service.get(1L).getBaseUrl());
    }

    @Test
    void testConnectivity_shouldPersistResult_andReturnItAsynchronously() throws Exception {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        existing.setApiKey(cryptoUtil.encrypt("sk-test"));
        existing.setProvider("openai");
        existing.setBaseUrl("https://api.openai.com/v1");
        existing.setModel("gpt-4o-mini");
        when(mapper.selectById(1L)).thenReturn(existing);
        when(modelHealthService.probe(eq(1L), any()))
            .thenReturn(CompletableFuture.completedFuture(
                new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null)));

        CompletableFuture<ModelTestResult> future = service.testConnectivity(1L);
        ModelTestResult result = future.get();

        assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
        verify(modelHealthService).probe(eq(1L), any());
    }

    @Test
    void delete_shouldRejectUnknownId() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> service.delete(999L));
    }

    private ModelEndpointPolicy endpointPolicy() {
        return new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByName(host.matches("[0-9.]+") ? host : "8.8.8.8")});
    }
}
