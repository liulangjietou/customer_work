package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CustomerWorkConfigPublisher} 单测：DTO 组装正确性（密文原样携带 / 兜底取首个备模 / MCP 解析）、
 * 发布门禁（连通性探测不过则不发布）、发布开关关闭时全链路 no-op。不触达真实 Nacos。
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkConfigPublisherTest {

    private final AiChannelBindingMapper bindingMapper = mock(AiChannelBindingMapper.class);
    private final AiAgentMapper agentMapper = mock(AiAgentMapper.class);
    private final AiModelConfigMapper modelConfigMapper = mock(AiModelConfigMapper.class);
    private final AiAgentBackupModelMapper backupModelMapper = mock(AiAgentBackupModelMapper.class);
    private final AiAgentMcpMapper agentMcpMapper = mock(AiAgentMcpMapper.class);
    private final AiMcpMapper mcpMapper = mock(AiMcpMapper.class);
    private final AesGcmCryptoUtil cryptoUtil = mock(AesGcmCryptoUtil.class);
    private final AdminModelFactory modelFactory = mock(AdminModelFactory.class);

    private CustomerWorkConfigPublisher publisher(boolean enabled) {
        RuntimePublishProperties props = new RuntimePublishProperties();
        props.getNacos().setEnabled(enabled);
        return new CustomerWorkConfigPublisher(bindingMapper, agentMapper, modelConfigMapper,
            backupModelMapper, agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, props);
    }

    private AiAgent agent() {
        AiAgent agent = new AiAgent();
        agent.setId(1L);
        agent.setAgentCode("cs-bot");
        agent.setModelId(100L);
        agent.setSystemPrompt("你是客服");
        agent.setMaxIters(8);
        return agent;
    }

    private AiModelConfig model(Long id, String provider, String name, String cipher) {
        AiModelConfig m = new AiModelConfig();
        m.setId(id);
        m.setProvider(provider);
        m.setModel(name);
        m.setModelName(name + "-cfg");
        m.setBaseUrl("https://api." + provider + ".com");
        m.setApiKey(cipher);
        return m;
    }

    @Test
    void assembleBuildsPayloadWithCipherFallbackAndMcp() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "CIPHER_PRIMARY");

        // 备用模型：首个（sort_order=0）作为兜底
        AiAgentBackupModel backup = new AiAgentBackupModel();
        backup.setAgentId(1L);
        backup.setModelId(200L);
        backup.setSortOrder(0);
        when(backupModelMapper.selectList(any())).thenReturn(List.of(backup));
        when(modelConfigMapper.selectById(200L)).thenReturn(model(200L, "dashscope", "qwen-max", "CIPHER_FB"));

        // 绑定的 MCP：sse 型，config 带 url
        AiAgentMcp rel = new AiAgentMcp();
        rel.setAgentId(1L);
        rel.setMcpId(10L);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of(rel));
        AiMcp mcp = new AiMcp();
        mcp.setId(10L);
        mcp.setMcpName("orders");
        mcp.setMcpType("sse");
        mcp.setStatus(1);
        mcp.setConfig("{\"url\":\"https://mcp.example.com/sse\",\"headers\":{\"Authorization\":\"Bearer x\"}}");
        when(mcpMapper.selectBatchIds(any())).thenReturn(List.of(mcp));

        CustomerWorkRuntimeConfig cfg = publisher.assemble(agent(), primary);

        assertEquals("openai", cfg.getModel().getProvider());
        assertEquals("gpt-4o", cfg.getModel().getName());
        assertEquals("CIPHER_PRIMARY", cfg.getModel().getApiKeyCipher(), "主模型密文原样携带");
        assertEquals("你是客服", cfg.getSystemPrompt());
        assertEquals(8, cfg.getAgent().getMaxIters());

        assertNotNull(cfg.getFallback());
        assertTrue(cfg.getFallback().isEnabled());
        assertEquals("dashscope", cfg.getFallback().getProvider());
        assertEquals("qwen-max", cfg.getFallback().getName());
        assertEquals("CIPHER_FB", cfg.getFallback().getApiKeyCipher(), "兜底模型密文原样携带");

        assertEquals(1, cfg.getMcpServers().size());
        assertEquals("orders", cfg.getMcpServers().get(0).getName());
        assertEquals("https://mcp.example.com/sse", cfg.getMcpServers().get(0).getUrl());
        assertEquals("sse", cfg.getMcpServers().get(0).getTransport());
        assertEquals("Bearer x", cfg.getMcpServers().get(0).getHeaders().get("Authorization"));
    }

    @Test
    void assembleUnwrapsMcpServersWrapperConfig() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "CIPHER_PRIMARY");
        when(backupModelMapper.selectList(any())).thenReturn(List.of());

        // Claude Desktop / Cursor 风格：外层包一层 mcpServers（调试面板可用，发布链路曾解不出 url 被静默跳过）
        AiAgentMcp rel = new AiAgentMcp();
        rel.setAgentId(1L);
        rel.setMcpId(11L);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of(rel));
        AiMcp mcp = new AiMcp();
        mcp.setId(11L);
        mcp.setMcpName("amap");
        mcp.setMcpType("http");
        mcp.setStatus(1);
        mcp.setConfig("{\"mcpServers\":{\"amap-maps\":{\"url\":\"https://mcp.amap.com/mcp\","
            + "\"headers\":{\"Authorization\":\"Bearer y\"}}}}");
        when(mcpMapper.selectBatchIds(any())).thenReturn(List.of(mcp));

        CustomerWorkRuntimeConfig cfg = publisher.assemble(agent(), primary);

        assertEquals(1, cfg.getMcpServers().size(), "包装格式的 MCP 不应被静默跳过");
        assertEquals("amap", cfg.getMcpServers().get(0).getName(), "名称以 ai_mcp.mcp_name 为准，不读 wrapper 的 key");
        assertEquals("https://mcp.amap.com/mcp", cfg.getMcpServers().get(0).getUrl());
        assertEquals("streamable-http", cfg.getMcpServers().get(0).getTransport());
        assertEquals("Bearer y", cfg.getMcpServers().get(0).getHeaders().get("Authorization"));
    }

    @Test
    void multiTenantTaskUsesTenantScopedDataId() {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.getNacos().setEnabled(true);
        CustomerWorkConfigPublisher publisher = new CustomerWorkConfigPublisher(
            bindingMapper, agentMapper, modelConfigMapper, backupModelMapper, agentMcpMapper,
            mcpMapper, cryptoUtil, modelFactory, properties, true);
        AiChannelBinding binding = new AiChannelBinding();
        binding.setAgentId(1L);
        binding.setChannelCode("webchat");
        binding.setStatus(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectById(1L)).thenReturn(agent());
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "CIPHER");
        when(modelConfigMapper.selectById(100L)).thenReturn(primary);
        when(cryptoUtil.decrypt("CIPHER")).thenReturn("sk-plain");
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), "ok"));
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setTenantId("tenant-a");
        task.setCreatedAtMs(1L);

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);

        assertEquals("customer-work-runtime-config-tenant-tenant-a", prepared.dataId());
    }

    @Test
    void republishBlockedWhenConnectivityProbeFails() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        AiChannelBinding binding = new AiChannelBinding();
        binding.setChannelCode("default");
        binding.setAgentId(1L);
        binding.setStatus(1);
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        when(agentMapper.selectById(1L)).thenReturn(agent());
        when(modelConfigMapper.selectById(100L)).thenReturn(model(100L, "openai", "gpt-4o", "CIPHER"));
        when(cryptoUtil.decrypt("CIPHER")).thenReturn("sk-plain");
        when(modelFactory.testConnectivity(eq("openai"), anyString(), eq("sk-plain"), eq("gpt-4o")))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(), "401 unauthorized"));

        // 探测不过：抛异常，绝不进入 publishConfig（不触达 Nacos）
        assertThrows(IllegalStateException.class, () -> publisher.republishByChannel("default"));
        verify(modelFactory).testConnectivity(eq("openai"), anyString(), eq("sk-plain"), eq("gpt-4o"));
    }

    @Test
    void disabledPublisherIsNoOp() {
        CustomerWorkConfigPublisher publisher = publisher(false);
        publisher.publishForAgentId(1L);
        publisher.publishForModelId(100L);
        assertTrue(!publisher.isEnabled());
        verifyNoInteractions(bindingMapper, agentMapper, modelConfigMapper);
        verify(agentMcpMapper, never()).selectList(any());
    }

    @Test
    void publishDeferredUntilTransactionCommit() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        when(bindingMapper.selectList(any())).thenReturn(List.of());   // 无绑定，回调体走空循环即可

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishForAgentId(1L);
            // 事务提交前：只注册回调，不做任何 DB/Nacos 动作
            verify(bindingMapper, never()).selectList(any());
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(),
                "应恰好注册一个 afterCommit 回调");

            // 模拟事务提交：触发 afterCommit → 恰好发布一次
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
            verify(bindingMapper).selectList(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishSkippedOnTransactionRollback() {
        CustomerWorkConfigPublisher publisher = publisher(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishForAgentId(1L);
            // 模拟事务回滚：只走 afterCompletion(ROLLED_BACK)，afterCommit 不被触发 → 绝不发布
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(bindingMapper, never()).selectList(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishRunsImmediatelyWithoutTransaction() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        // 无事务上下文：降级为立即执行，不静默丢失
        publisher.publishForAgentId(1L);
        verify(bindingMapper).selectList(any());
    }
}
