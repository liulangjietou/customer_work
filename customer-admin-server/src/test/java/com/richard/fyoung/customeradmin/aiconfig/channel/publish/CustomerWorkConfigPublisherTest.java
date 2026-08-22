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
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyRuntimeAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.experiment.service.ModelExperimentRuntimeAccess;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigContentHasher;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final ModelConfigAccess modelConfigAccess = mock(ModelConfigAccess.class);
    private final AiAgentBackupModelMapper backupModelMapper = mock(AiAgentBackupModelMapper.class);
    private final AiAgentMcpMapper agentMcpMapper = mock(AiAgentMcpMapper.class);
    private final AiMcpMapper mcpMapper = mock(AiMcpMapper.class);
    private final AesGcmCryptoUtil cryptoUtil = mock(AesGcmCryptoUtil.class);
    private final AdminModelFactory modelFactory = mock(AdminModelFactory.class);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private CustomerWorkConfigPublisher publisher(boolean enabled) {
        RuntimePublishProperties props = new RuntimePublishProperties();
        props.getNacos().setEnabled(enabled);
        return new CustomerWorkConfigPublisher(bindingMapper, agentMapper, modelConfigAccess,
            backupModelMapper, agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, props);
    }

    private CustomerWorkConfigPublisher governedPublisher(SecretRefService secretRefService,
                                                           ModelRoutingPolicyRuntimeAccess routingAccess) {
        RuntimePublishProperties props = new RuntimePublishProperties();
        props.getNacos().setEnabled(true);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingProvider = mock(ObjectProvider.class);
        ObjectProvider<ConfigVersionService> versionProvider = mock(ObjectProvider.class);
        ObjectProvider<com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService>
            taskProvider = mock(ObjectProvider.class);
        when(routingProvider.getIfAvailable()).thenReturn(routingAccess);
        return new CustomerWorkConfigPublisher(bindingMapper, agentMapper, modelConfigAccess,
            backupModelMapper, agentMcpMapper, mcpMapper, cryptoUtil, modelFactory,
            secretRefService, routingProvider, props, tenantProperties, versionProvider, taskProvider);
    }

    private CustomerWorkConfigPublisher experimentPublisher(SecretRefService secretRefService,
                                                             ModelExperimentRuntimeAccess experimentAccess) {
        RuntimePublishProperties props = new RuntimePublishProperties();
        props.getNacos().setEnabled(true);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingProvider = mock(ObjectProvider.class);
        ObjectProvider<ModelExperimentRuntimeAccess> experimentProvider = mock(ObjectProvider.class);
        ObjectProvider<ConfigVersionService> versionProvider = mock(ObjectProvider.class);
        ObjectProvider<com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService>
            taskProvider = mock(ObjectProvider.class);
        when(experimentProvider.getIfAvailable()).thenReturn(experimentAccess);
        return new CustomerWorkConfigPublisher(bindingMapper, agentMapper, modelConfigAccess,
            backupModelMapper, agentMcpMapper, mcpMapper, cryptoUtil, modelFactory,
            secretRefService, routingProvider, experimentProvider, props, tenantProperties,
            versionProvider, taskProvider);
    }

    private CustomerWorkConfigPublisher reliablePublisher(boolean enabled,
                                                           RuntimePublishTaskService taskService) {
        RuntimePublishProperties props = new RuntimePublishProperties();
        props.getNacos().setEnabled(enabled);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingProvider = mock(ObjectProvider.class);
        ObjectProvider<ConfigVersionService> versionProvider = mock(ObjectProvider.class);
        ObjectProvider<RuntimePublishTaskService> taskProvider = mock(ObjectProvider.class);
        when(taskProvider.getIfAvailable()).thenReturn(taskService);
        return new CustomerWorkConfigPublisher(bindingMapper, agentMapper, modelConfigAccess,
            backupModelMapper, agentMcpMapper, mcpMapper, cryptoUtil, modelFactory,
            mock(SecretRefService.class), routingProvider, props, tenantProperties,
            versionProvider, taskProvider);
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
        when(modelConfigAccess.findVisibleById(200L))
            .thenReturn(model(200L, "dashscope", "qwen-max", "CIPHER_FB"));

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
    void assemble_shouldPublishSchemaV2RoutingSnapshotUsingSecretRefWithoutPlaintext() throws Exception {
        SecretRefService secretRefService = mock(SecretRefService.class);
        ModelRoutingPolicyRuntimeAccess routingAccess = mock(ModelRoutingPolicyRuntimeAccess.class);
        CustomerWorkConfigPublisher publisher = governedPublisher(secretRefService, routingAccess);
        AiAgent routedAgent = agent();
        routedAgent.setModelRoutePolicyId(77L);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "LEGACY_CIPHER");
        primary.setTenantId("tenant-a");
        primary.setSecretRefId(501L);
        when(secretRefService.resolveCipherText(501L, "tenant-a", "LEGACY_CIPHER"))
            .thenReturn("PRIMARY_CURRENT_CIPHER");
        CustomerWorkRuntimeConfig.RoutingPolicy routing = routingPolicySnapshot();
        when(routingAccess.requireActive(77L, 1L, "webchat")).thenReturn(routing);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());

        CustomerWorkRuntimeConfig payload = publisher.assemble(routedAgent, primary, "webchat");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);

        assertEquals(2, payload.getSchemaVersion());
        assertEquals("PRIMARY_CURRENT_CIPHER", payload.getModel().getApiKeyCipher());
        assertEquals(77L, payload.getRoutingPolicy().getPolicyId());
        assertEquals("ROUTE_CURRENT_CIPHER",
            payload.getRoutingPolicy().getDeployments().get(0).getApiKeyCipher());
        assertEquals("ROUTE_CURRENT_CIPHER", payload.getFallback().getApiKeyCipher());
        assertTrue(!json.contains("sk-plain") && !json.contains("LEGACY_CIPHER"));
        verify(secretRefService, never()).resolvePlaintext(any(AiModelConfig.class));
    }

    @Test
    void assemble_shouldIncludeRunningExperimentSnapshotWithoutPlaintextSecrets() throws Exception {
        SecretRefService secretRefService = mock(SecretRefService.class);
        ModelExperimentRuntimeAccess experimentAccess = mock(ModelExperimentRuntimeAccess.class);
        CustomerWorkConfigPublisher publisher = experimentPublisher(secretRefService, experimentAccess);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "LEGACY_PRIMARY_CIPHER");
        primary.setTenantId("tenant-a");
        primary.setSecretRefId(501L);
        when(secretRefService.resolveCipherText(501L, "tenant-a", "LEGACY_PRIMARY_CIPHER"))
            .thenReturn("PRIMARY_CURRENT_CIPHER");
        when(experimentAccess.runningForAgent(1L)).thenReturn(experimentSnapshot());
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());

        CustomerWorkRuntimeConfig payload = publisher.assemble(agent(), primary, "webchat");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);

        assertNotNull(payload.getOnlineExperiment());
        assertEquals(70L, payload.getOnlineExperiment().getExperimentId());
        assertEquals("CONTROL_CURRENT_CIPHER",
            payload.getOnlineExperiment().getControl().getApiKeyCipher());
        assertEquals("TREATMENT_CURRENT_CIPHER",
            payload.getOnlineExperiment().getTreatment().getApiKeyCipher());
        assertTrue(!json.contains("sk-control-plain") && !json.contains("sk-treatment-plain"));
        assertTrue(!json.contains("LEGACY_PRIMARY_CIPHER"));
        verify(secretRefService, never()).resolvePlaintext(any(AiModelConfig.class));
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
    void assemble_shouldRejectLegacyMcpUrlCredentialsInsteadOfPublishingOrDroppingTool() {
        CustomerWorkConfigPublisher publisher = publisher(true);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "CIPHER_PRIMARY");
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        AiAgentMcp rel = new AiAgentMcp();
        rel.setAgentId(1L);
        rel.setMcpId(12L);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of(rel));
        AiMcp mcp = new AiMcp();
        mcp.setId(12L);
        mcp.setMcpName("legacy-secret-url");
        mcp.setMcpType("http");
        mcp.setStatus(1);
        mcp.setConfig("{\"url\":\"https://mcp.example.com/mcp?api_key=legacy-secret\"}");
        when(mcpMapper.selectBatchIds(any())).thenReturn(List.of(mcp));

        assertThrows(IllegalStateException.class, () -> publisher.assemble(agent(), primary));
    }

    @Test
    void multiTenantTaskUsesTenantScopedDataId() {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.getNacos().setEnabled(true);
        properties.getNacos().setGroup("CURRENT_GROUP");
        CustomerWorkConfigPublisher publisher = new CustomerWorkConfigPublisher(
            bindingMapper, agentMapper, modelConfigAccess, backupModelMapper, agentMcpMapper,
            mcpMapper, cryptoUtil, modelFactory, properties, true);
        AiChannelBinding binding = new AiChannelBinding();
        binding.setAgentId(1L);
        binding.setChannelCode("webchat");
        binding.setStatus(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectById(1L)).thenReturn(agent());
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "CIPHER");
        when(modelConfigAccess.findVisibleById(100L)).thenReturn(primary);
        when(cryptoUtil.decrypt("CIPHER")).thenReturn("sk-plain");
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), "ok"));
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setTenantId("tenant-a");
        task.setGroupName("FROZEN_GROUP");
        task.setCreatedAtMs(1L);

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);

        assertEquals("customer-work-runtime-config-tenant-tenant-a", prepared.dataId());
        assertEquals("FROZEN_GROUP", prepared.groupName());
    }

    @Test
    void reliableTask_shouldNotFreezeUnorderedBindingAsGlobalRouteHint() {
        SecretRefService secretRefService = mock(SecretRefService.class);
        ModelRoutingPolicyRuntimeAccess routingAccess = mock(ModelRoutingPolicyRuntimeAccess.class);
        CustomerWorkConfigPublisher publisher = governedPublisher(secretRefService, routingAccess);
        AiAgent routedAgent = agent();
        routedAgent.setModelRoutePolicyId(77L);
        AiChannelBinding app = new AiChannelBinding();
        app.setAgentId(1L);
        app.setChannelCode("app");
        app.setStatus(1);
        AiChannelBinding web = new AiChannelBinding();
        web.setAgentId(1L);
        web.setChannelCode("webchat");
        web.setStatus(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(web, app));
        when(agentMapper.selectById(1L)).thenReturn(routedAgent);
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "PRIMARY_CIPHER");
        when(modelConfigAccess.findVisibleById(100L)).thenReturn(primary);
        when(secretRefService.resolvePlaintext(primary)).thenReturn("sk-plain");
        when(secretRefService.resolveCipherText(null, null, "PRIMARY_CIPHER"))
            .thenReturn("PRIMARY_CIPHER");
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), "ok"));
        CustomerWorkRuntimeConfig.RoutingPolicy routing = routingPolicySnapshot();
        routing.setChannelCode(null);
        when(routingAccess.requireActive(77L, 1L, null)).thenReturn(routing);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setTenantId("default");
        task.setDataId("frozen-runtime-data-id");
        task.setCreatedAtMs(1L);

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);

        assertNull(prepared.channelCode());
        assertEquals("frozen-runtime-data-id", prepared.dataId());
        verify(routingAccess).requireActive(77L, 1L, null);
    }

    @Test
    void activationTask_shouldPublishItsImmutableExperimentIdentity() {
        SecretRefService secretRefService = mock(SecretRefService.class);
        ModelExperimentRuntimeAccess experimentAccess = mock(ModelExperimentRuntimeAccess.class);
        CustomerWorkConfigPublisher publisher = experimentPublisher(secretRefService, experimentAccess);
        RuntimePublishTask task = preparedExperimentTask(ModelExperimentPublishAction.ACTIVATE);
        stubPreparedExperimentTaskBase(secretRefService);
        when(experimentAccess.requireRunning(1L, 70L)).thenReturn(experimentSnapshot());

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);

        assertTrue(prepared.json().contains("\"experimentId\":70"));
        verify(experimentAccess).requireRunning(1L, 70L);
        verify(experimentAccess, never()).runningForAgent(any());
    }

    @Test
    void deactivationTask_shouldStripExperimentAndBypassConnectivityProbe() {
        SecretRefService secretRefService = mock(SecretRefService.class);
        ModelExperimentRuntimeAccess experimentAccess = mock(ModelExperimentRuntimeAccess.class);
        CustomerWorkConfigPublisher publisher = experimentPublisher(secretRefService, experimentAccess);
        RuntimePublishTask task = preparedExperimentTask(ModelExperimentPublishAction.DEACTIVATE);
        stubPreparedExperimentTaskBase(secretRefService);

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);

        assertTrue(!prepared.json().contains("\"experimentId\""));
        verify(modelFactory, never()).testConnectivity(anyString(), anyString(), anyString(), anyString());
        verify(secretRefService, never()).resolvePlaintext(any(AiModelConfig.class));
        verifyNoInteractions(experimentAccess);
    }

    @Test
    void grayDataId_shouldCanonicalizeDefaultAliasOnly() {
        CustomerWorkConfigPublisher publisher = publisher(true);

        assertEquals("customer-work-runtime-config-tenant-default", publisher.grayDataId(" DEFAULT "));
        assertEquals("customer-work-runtime-config-tenant-AcMe", publisher.grayDataId("AcMe"),
            "业务租户外部 dataId 必须保留存量编码大小写");
        assertThrows(IllegalArgumentException.class, () -> publisher.grayDataId("__platform__"));
    }

    @Test
    void safeRollbackTask_shouldPatchBehaviorOnlyAndKeepCurrentAssets() throws Exception {
        CustomerWorkConfigPublisher publisher = publisher(true);
        AiChannelBinding binding = new AiChannelBinding();
        binding.setAgentId(1L);
        binding.setChannelCode("webchat");
        binding.setStatus(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectById(1L)).thenReturn(agent());
        AiModelConfig primary = model(100L, "openai", "gpt-current", "CURRENT_CIPHER");
        when(modelConfigAccess.findVisibleById(100L)).thenReturn(primary);
        when(cryptoUtil.decrypt("CURRENT_CIPHER")).thenReturn("sk-current");
        when(modelFactory.testConnectivity("openai", primary.getBaseUrl(), "sk-current", "gpt-current"))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), "ok"));
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setTenantId("tenant-a");
        task.setPublishIntent(RuntimePublishIntent.SAFE_ROLLBACK.name());
        task.setRollbackPatchJson("{\"systemPrompt\":\"historical prompt\",\"maxIters\":3}");
        task.setCreatedAtMs(1L);

        CustomerWorkConfigPublisher.PreparedRuntimeConfig prepared = publisher.prepareTask(task);
        ObjectMapper objectMapper = new ObjectMapper();
        CustomerWorkRuntimeConfig payload = objectMapper
            .readValue(prepared.json(), CustomerWorkRuntimeConfig.class);

        assertEquals("historical prompt", payload.getSystemPrompt());
        assertEquals(3, payload.getAgent().getMaxIters());
        assertEquals("gpt-current", payload.getModel().getName());
        assertEquals("CURRENT_CIPHER", payload.getModel().getApiKeyCipher());
        assertEquals(prepared.contentHash(), payload.getContentHash());
        assertEquals(prepared.contentHash(), RuntimeConfigContentHasher.compute(payload, objectMapper),
            "Admin 发布摘要必须能由 Starter 共用算法原样复算");
        assertTrue(!prepared.json().contains("old.example") && !prepared.json().contains("OLD_SECRET"));
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
        when(modelConfigAccess.findVisibleById(100L))
            .thenReturn(model(100L, "openai", "gpt-4o", "CIPHER"));
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
        assertNull(publisher.publishForAgentId(1L));
        assertTrue(!publisher.isEnabled());
        verifyNoInteractions(bindingMapper, agentMapper, modelConfigAccess);
        verify(agentMcpMapper, never()).selectList(any());
    }

    @Test
    void reliablePublish_shouldReturnTaskId_andSkipWhenBindingMissing() {
        RuntimePublishTaskService taskService = mock(RuntimePublishTaskService.class);
        CustomerWorkConfigPublisher publisher = reliablePublisher(true, taskService);
        when(bindingMapper.selectCount(any())).thenReturn(1L);
        when(taskService.enqueueAgent(1L)).thenReturn("task-agent-1");

        assertEquals("task-agent-1", publisher.publishForAgentId(1L));

        when(bindingMapper.selectCount(any())).thenReturn(0L);
        assertNull(publisher.publishForAgentId(2L));
        verify(taskService, never()).enqueueAgent(2L);
    }

    @Test
    void experimentPublish_shouldPersistImmutableIntentInReliableTask() {
        RuntimePublishTaskService taskService = mock(RuntimePublishTaskService.class);
        CustomerWorkConfigPublisher publisher = reliablePublisher(true, taskService);
        when(bindingMapper.selectCount(any())).thenReturn(1L);
        when(taskService.enqueueExperiment(1L, 70L, ModelExperimentPublishAction.ACTIVATE))
            .thenReturn("task-exp-activate");

        assertEquals("task-exp-activate", publisher.publishExperiment(
            1L, 70L, ModelExperimentPublishAction.ACTIVATE));
        verify(taskService).enqueueExperiment(1L, 70L, ModelExperimentPublishAction.ACTIVATE);
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
    void deferredPublish_shouldRestoreCapturedOwningTenant() {
        RuntimePublishProperties properties = new RuntimePublishProperties();
        properties.getNacos().setEnabled(true);
        CustomerWorkConfigPublisher publisher = new CustomerWorkConfigPublisher(
            bindingMapper, agentMapper, modelConfigAccess, backupModelMapper, agentMcpMapper,
            mcpMapper, cryptoUtil, modelFactory, properties, true);
        when(bindingMapper.selectList(any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return List.of();
        });

        TenantContext.set("tenant-a");
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishForAgentId(1L);
            TenantContext.set("tenant-b");

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            assertEquals("tenant-b", TenantContext.get(), "回调结束后必须恢复触发线程原租户");
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

    private CustomerWorkRuntimeConfig.RoutingPolicy routingPolicySnapshot() {
        CustomerWorkRuntimeConfig.RoutingDeployment deployment =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        deployment.setDeploymentId(200L);
        deployment.setProvider("dashscope");
        deployment.setName("qwen-max");
        deployment.setBaseUrl("https://model.example");
        deployment.setEndpointRevision(3);
        deployment.setApiKeyCipher("ROUTE_CURRENT_CIPHER");
        CustomerWorkRuntimeConfig.RoutingRule rule = new CustomerWorkRuntimeConfig.RoutingRule();
        rule.setRuleId(301L);
        rule.setPurpose("FALLBACK");
        rule.setDeploymentId(200L);
        rule.setPriority(100);
        CustomerWorkRuntimeConfig.RoutingPolicy policy = new CustomerWorkRuntimeConfig.RoutingPolicy();
        policy.setPolicyId(77L);
        policy.setVersionId(88L);
        policy.setVersionNo(4);
        policy.setPolicyContentHash("route-hash");
        policy.setAgentId(1L);
        policy.setChannelCode("webchat");
        policy.setDeployments(List.of(deployment));
        policy.setRules(List.of(rule));
        return policy;
    }

    private RuntimePublishTask preparedExperimentTask(ModelExperimentPublishAction action) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setTargetId(1L);
        task.setTenantId("tenant-a");
        task.setExperimentId(70L);
        task.setExperimentPublishAction(action.name());
        task.setCreatedAtMs(1L);
        return task;
    }

    private void stubPreparedExperimentTaskBase(SecretRefService secretRefService) {
        AiChannelBinding binding = new AiChannelBinding();
        binding.setAgentId(1L);
        binding.setChannelCode("webchat");
        binding.setStatus(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(agentMapper.selectById(1L)).thenReturn(agent());
        AiModelConfig primary = model(100L, "openai", "gpt-4o", "LEGACY_PRIMARY_CIPHER");
        primary.setTenantId("tenant-a");
        primary.setSecretRefId(501L);
        when(modelConfigAccess.findVisibleById(100L)).thenReturn(primary);
        when(secretRefService.resolveCipherText(501L, "tenant-a", "LEGACY_PRIMARY_CIPHER"))
            .thenReturn("PRIMARY_CURRENT_CIPHER");
        when(secretRefService.resolvePlaintext(primary)).thenReturn("sk-current");
        when(modelFactory.testConnectivity("openai", primary.getBaseUrl(), "sk-current", "gpt-4o"))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), "ok"));
        when(backupModelMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
    }

    private CustomerWorkRuntimeConfig.OnlineExperiment experimentSnapshot() {
        CustomerWorkRuntimeConfig.ExperimentArm control = new CustomerWorkRuntimeConfig.ExperimentArm();
        control.setArm("CONTROL");
        control.setDeploymentId(11L);
        control.setProvider("openai");
        control.setName("control-model");
        control.setEndpointRevision(3);
        control.setApiKeyCipher("CONTROL_CURRENT_CIPHER");
        CustomerWorkRuntimeConfig.ExperimentArm treatment = new CustomerWorkRuntimeConfig.ExperimentArm();
        treatment.setArm("TREATMENT");
        treatment.setDeploymentId(12L);
        treatment.setProvider("dashscope");
        treatment.setName("treatment-model");
        treatment.setEndpointRevision(5);
        treatment.setApiKeyCipher("TREATMENT_CURRENT_CIPHER");
        CustomerWorkRuntimeConfig.OnlineExperiment experiment =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        experiment.setExperimentId(70L);
        experiment.setRevision(4);
        experiment.setAssignmentSalt("salt-123");
        experiment.setTreatmentBps(2500);
        experiment.setExpiresAtEpochMs(System.currentTimeMillis() + 60000);
        experiment.setControl(control);
        experiment.setTreatment(treatment);
        return experiment;
    }
}
