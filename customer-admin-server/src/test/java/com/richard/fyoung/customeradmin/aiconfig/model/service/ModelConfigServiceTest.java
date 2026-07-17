package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelConfigService} 单测：AppKey 加密落库/脱敏回显、默认模型互斥事务、连通性测试装配。
 * @author owlzhangfq@gmail.com
 */
class ModelConfigServiceTest {

    private AiModelConfigMapper mapper;
    private AdminModelFactory modelFactory;
    private ModelConfigService service;

    /**
     * MyBatis-Plus 的 LambdaUpdateWrapper/LambdaQueryWrapper 依赖实体的 TableInfo 缓存，
     * 该缓存平时由 Spring 容器启动扫描 Mapper 时自动注册；纯 Mockito 单测没有容器，需要手动注册一次，
     * 否则 clearOtherDefaults() 里的 LambdaUpdateWrapper 会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiModelConfig.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
            com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        modelFactory = mock(AdminModelFactory.class);
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper agentBackupModelMapper =
            mock(com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper.class);
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        // 16 字节测试密钥，满足 AES-128 长度要求
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher runtimeConfigPublisher =
            mock(com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.class);
        service = new ModelConfigService(mapper, agentMapper, agentBackupModelMapper, cryptoUtil, modelFactory,
            agentInstanceCache, runtimeConfigPublisher);
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
    void create_shouldClearOtherDefaults_whenSetAsDefault() {
        ModelSaveRequest request = new ModelSaveRequest(
            "gpt-4o", "openai", "sk-secret", "https://api.openai.com/v1", "gpt-4o-mini",
            true, 1);

        service.create(request);

        verify(mapper).update(eq(null), any(LambdaUpdateWrapper.class));
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
    void update_shouldKeepOldApiKey_whenRequestApiKeyBlank() {
        AiModelConfig existing = new AiModelConfig();
        existing.setId(1L);
        AesGcmCryptoUtil cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef");
        String originalCipher = cryptoUtil.encrypt("sk-original-secret");
        existing.setApiKey(originalCipher);
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
        assertTrue(vo.getApiKeyMasked().endsWith("wxyz"));
        assertTrue(vo.getIsDefault());
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
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(ModelTestResult.STATUS_SUCCESS, LocalDateTime.now(), null));

        CompletableFuture<ModelTestResult> future = service.testConnectivity(1L);
        ModelTestResult result = future.get();

        assertEquals(ModelTestResult.STATUS_SUCCESS, result.testStatus());
        verify(mapper).updateById(any(AiModelConfig.class));
    }

    @Test
    void delete_shouldRejectUnknownId() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> service.delete(999L));
    }
}
