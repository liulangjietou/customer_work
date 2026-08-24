package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.agent.RuntimeAgentAccessState;
import com.richard.fyoung.customerwork.core.model.experiment.OnlineExperimentRoutingModel;
import com.richard.fyoung.customerwork.core.model.routing.ModelRouteHint;
import com.richard.fyoung.customerwork.core.model.routing.PolicyRoutingModel;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeConfigApplier} 单测：成功提交（swap 新链 + 回写配置 + 覆盖提示词 + flush）与
 * 「全有或全无」——构建/校验失败绝不覆盖旧配置、不 swap、不 flush。
 * @author owlzhangfq@gmail.com
 */
class RuntimeConfigApplierTest {

    private CustomerWorkRuntimeConfig sampleDto() {
        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setBaseUrl("https://api.example.com/v1");
        dto.setSystemPrompt("你是新的客服提示词");
        CustomerWorkRuntimeConfig.Agent agent = new CustomerWorkRuntimeConfig.Agent();
        agent.setMaxIters(7);
        dto.setAgent(agent);
        CustomerWorkRuntimeConfig.McpServer mcp = new CustomerWorkRuntimeConfig.McpServer();
        mcp.setName("orders");
        mcp.setUrl("https://mcp.example.com/sse");
        mcp.setTransport("sse");
        dto.setMcpServers(List.of(mcp));
        return dto;
    }

    @Test
    void revocation_shouldCloseRuntimeWithoutReadingOrReplacingModelConfiguration() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        RuntimeAgentAccessState accessState = new RuntimeAgentAccessState();
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            new NacosPromptService(properties), customerServiceService, endpointPolicy(), accessState);
        CustomerWorkRuntimeConfig tombstone = new CustomerWorkRuntimeConfig();
        tombstone.setSchemaVersion(3);
        tombstone.setActive(Boolean.FALSE);
        tombstone.setTargetCode("cs-bot");
        tombstone.setRevision("revision-2");
        tombstone.setContentHash("a".repeat(64));

        assertTrue(applier.apply(tombstone, null, null));

        assertFalse(accessState.isActive());
        assertSame(initial, mutableModel.current());
        verify(modelConfig, never()).buildChain(any(), any(), any(), any());
        verify(customerServiceService).flushHotAgents();
    }

    @Test
    void applyCommitsAllOnSuccess() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getModel().getTieredRouting().setEnabled(true);
        properties.getModel().getTieredRouting().setProvider("openai");
        properties.getModel().getTieredRouting().setName("gpt-4o-mini");
        properties.getModel().getTieredRouting().setApiKey("sk-economy");
        properties.getModel().getTieredRouting().setBaseUrl("https://economy.example.com/v1");
        properties.getModel().getTieredRouting().setMaxMessagesForEconomy(6);
        properties.getModel().getTieredRouting().setMaxUserTextLengthForEconomy(80);
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model newChain = mock(Model.class);
        when(newChain.getModelName()).thenReturn("new-chain");
        when(modelConfig.buildChain(any(), any(), any(), any())).thenReturn(newChain);

        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);

        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css, endpointPolicy());

        boolean ok = applier.apply(sampleDto(), "sk-plain", null);

        assertTrue(ok);
        assertSame(newChain, mutableModel.current(), "模型链应热替换为新链");
        assertEquals("openai", properties.getModel().getProvider());
        assertEquals("gpt-4o", properties.getModel().getName());
        assertEquals("sk-plain", properties.getModel().getApiKey());
        assertTrue(properties.getModel().getTieredRouting().isEnabled());
        assertEquals("openai", properties.getModel().getTieredRouting().getProvider());
        assertEquals("gpt-4o-mini", properties.getModel().getTieredRouting().getName());
        assertEquals("sk-economy", properties.getModel().getTieredRouting().getApiKey());
        assertEquals("https://economy.example.com/v1",
            properties.getModel().getTieredRouting().getBaseUrl());
        assertEquals(6, properties.getModel().getTieredRouting().getMaxMessagesForEconomy());
        assertEquals(80, properties.getModel().getTieredRouting().getMaxUserTextLengthForEconomy());
        assertEquals(7, properties.getAgent().getMaxIters());
        assertEquals("你是新的客服提示词", prompt.currentPrompt().orElse(""));
        assertTrue(properties.getMcp().isEnabled());
        assertEquals(1, properties.getMcp().getServers().size());
        verify(css).flushHotAgents();
    }

    @Test
    void buildFailureKeepsOldConfig() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        when(modelConfig.buildChain(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("build boom"));

        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);

        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css, endpointPolicy());

        boolean ok = applier.apply(sampleDto(), "sk-plain", null);

        assertFalse(ok);
        assertSame(initial, mutableModel.current(), "构建失败不应替换模型链");
        assertEquals("dashscope", properties.getModel().getProvider(), "配置应保留旧值");
        verify(css, never()).flushHotAgents();
    }

    @Test
    void nullBuildResult_shouldFailBeforeMutatingSharedConfiguration() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        String oldProvider = properties.getModel().getProvider();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            prompt, customerServiceService, endpointPolicy());

        assertFalse(applier.apply(sampleDto(), "sk-plain", null));

        assertEquals(oldProvider, properties.getModel().getProvider());
        assertFalse(properties.getMcp().isEnabled());
        assertTrue(prompt.currentPrompt().isEmpty());
        assertSame(initial, mutableModel.current());
        verify(customerServiceService, never()).flushHotAgents();
    }

    @Test
    void invalidMcpSnapshot_shouldFailBeforeMutatingSharedConfiguration() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        String oldProvider = properties.getModel().getProvider();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model newChain = mock(Model.class);
        when(modelConfig.buildChain(any(), any(), any(), any())).thenReturn(newChain);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            prompt, customerServiceService, endpointPolicy());
        CustomerWorkRuntimeConfig dto = sampleDto();
        List<CustomerWorkRuntimeConfig.McpServer> invalid = new ArrayList<>();
        invalid.add(null);
        dto.setMcpServers(invalid);

        assertFalse(applier.apply(dto, "new-secret", null));

        assertEquals(oldProvider, properties.getModel().getProvider());
        assertFalse(properties.getMcp().isEnabled());
        assertTrue(prompt.currentPrompt().isEmpty());
        assertSame(initial, mutableModel.current());
        verify(customerServiceService, never()).flushHotAgents();
    }

    @Test
    void validationFailureShortCircuits() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("old-chain");
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService css = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier =
            new RuntimeConfigApplier(properties, modelConfig, mutableModel, prompt, css, endpointPolicy());

        CustomerWorkRuntimeConfig bad = new CustomerWorkRuntimeConfig();
        bad.getModel().setProvider("");   // provider 空白，校验失败
        bad.getModel().setName("");

        assertFalse(applier.apply(bad, null, null));
        verify(modelConfig, never()).buildChain(any(), any(), any(), any());
        verify(css, never()).flushHotAgents();
    }

    @Test
    void untrustedRuntimeEndpoint_shouldRejectBeforeAnyModelBuild() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        ModelEndpointPolicy privateAddressPolicy = new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 0, 0, 8})});
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            new NacosPromptService(properties), customerServiceService, privateAddressPolicy);
        CustomerWorkRuntimeConfig dto = sampleDto();
        dto.getModel().setBaseUrl("https://attacker.example/v1");

        assertFalse(applier.apply(dto, "real-secret", null));

        verify(modelConfig, never()).buildChain(any(), any(), any(), any());
        verify(modelConfig, never()).buildByProvider(any(), any(), any(), any(), any());
        verify(customerServiceService, never()).flushHotAgents();
        assertSame(initial, mutableModel.current());
        assertEquals("dashscope", properties.getModel().getProvider());
    }

    @Test
    void completeSnapshotNull_shouldResetPromptAndMaxItersWhileMissingAgentKeepsCurrentValue() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getAgent().setMaxIters(10);
        ModelConfig modelConfig = mock(ModelConfig.class);
        when(modelConfig.buildChain(any(), any(), any(), any())).thenReturn(mock(Model.class));
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(mock(Model.class));
        NacosPromptService prompt = new NacosPromptService(properties);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(
            properties, modelConfig, mutableModel, prompt, customerServiceService, endpointPolicy());

        assertTrue(applier.apply(sampleDto(), "sk-plain", null));
        assertEquals(7, properties.getAgent().getMaxIters());
        assertEquals("你是新的客服提示词", prompt.currentPrompt().orElseThrow());

        CustomerWorkRuntimeConfig missingAgent = sampleDto();
        missingAgent.setAgent(null);
        missingAgent.setSystemPrompt("still overridden");
        assertTrue(applier.apply(missingAgent, "sk-plain", null));
        assertEquals(7, properties.getAgent().getMaxIters(), "Agent section 缺失才表示保持当前热值");

        CustomerWorkRuntimeConfig reset = sampleDto();
        reset.setAgent(new CustomerWorkRuntimeConfig.Agent());
        reset.setSystemPrompt(null);
        assertTrue(applier.apply(reset, "sk-plain", null));
        assertEquals(10, properties.getAgent().getMaxIters(), "显式 null 必须回到进程启动基线");
        assertTrue(prompt.currentPrompt().isEmpty(), "显式 null 必须清除旧运行时提示词覆盖");
    }

    @Test
    void schemaV2_shouldBuildAndAtomicallySwapPolicyRoutingChain() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model standard = mock(Model.class);
        Model economy = mock(Model.class);
        Model fallback = mock(Model.class);
        when(standard.getContextWindowSize()).thenReturn(8192);
        when(economy.getContextWindowSize()).thenReturn(4096);
        when(fallback.getContextWindowSize()).thenReturn(4096);
        when(modelConfig.buildByProvider(any(), any(), any(), any(), any()))
            .thenReturn(standard, economy, fallback);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            new NacosPromptService(properties), mock(CustomerServiceService.class), endpointPolicy());

        CustomerWorkRuntimeConfig dto = sampleDto();
        dto.setSchemaVersion(2);
        CustomerWorkRuntimeConfig.RoutingPolicy policy = new CustomerWorkRuntimeConfig.RoutingPolicy();
        policy.setPolicyId(10L);
        policy.setVersionId(11L);
        policy.setVersionNo(2);
        policy.setPolicyContentHash("rule-hash");
        policy.setAgentId(7L);
        policy.setChannelCode("web");
        policy.setDeployments(List.of(deployment(1L, "standard"), deployment(2L, "economy"),
            deployment(3L, "fallback")));
        CustomerWorkRuntimeConfig.RoutingCondition shortInput = new CustomerWorkRuntimeConfig.RoutingCondition();
        shortInput.setMaxInputTokens(100);
        policy.setRules(List.of(
            rule(20L, "ECONOMY", 2L, 10, shortInput),
            rule(21L, "DEFAULT", 1L, 100, new CustomerWorkRuntimeConfig.RoutingCondition()),
            rule(22L, "FALLBACK", 3L, 1000, new CustomerWorkRuntimeConfig.RoutingCondition())));
        dto.setRoutingPolicy(policy);

        boolean applied = applier.apply(dto, "primary", "fallback",
            Map.of(1L, "key-1", 2L, "key-2", 3L, "key-3"));

        assertTrue(applied);
        PolicyRoutingModel routed = (PolicyRoutingModel) mutableModel.current();
        assertEquals(2L, routed.selectDeployment(
            new ModelRouteHint(7L, "web", 50, false, false, "LOW")));
    }

    @Test
    void schemaV2_shouldBuildBothExperimentArmsBeforeAtomicSwap() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model baseline = mock(Model.class);
        Model control = mock(Model.class);
        Model treatment = mock(Model.class);
        when(baseline.getContextWindowSize()).thenReturn(8192);
        when(control.getContextWindowSize()).thenReturn(8192);
        when(treatment.getContextWindowSize()).thenReturn(4096);
        when(modelConfig.buildChain(any(), any(), any(), any())).thenReturn(baseline);
        when(modelConfig.buildByProvider(any(), any(), any(), any(), any()))
            .thenReturn(control, treatment);
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            new NacosPromptService(properties), customerServiceService, endpointPolicy());
        CustomerWorkRuntimeConfig dto = sampleDto();
        dto.setSchemaVersion(2);
        dto.setOnlineExperiment(experiment());

        boolean applied = applier.apply(dto, "primary", null, Map.of(),
            Map.of(11L, "control-key", 12L, "treatment-key"));

        assertTrue(applied);
        OnlineExperimentRoutingModel experimentModel =
            (OnlineExperimentRoutingModel) mutableModel.current();
        assertEquals(7L, experimentModel.spec().experimentId());
        assertEquals(3, experimentModel.spec().revision());
        verify(modelConfig).buildByProvider(eq("openai"), eq("control-model"), eq("control-key"),
            eq("https://control.example.com"), any());
        verify(modelConfig).buildByProvider(eq("dashscope"), eq("treatment-model"), eq("treatment-key"),
            eq("https://treatment.example.com"), any());
        verify(customerServiceService).flushHotAgents();
    }

    @Test
    void experimentArmBuildFailure_shouldKeepOldChainAndConfiguration() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        String oldProvider = properties.getModel().getProvider();
        ModelConfig modelConfig = mock(ModelConfig.class);
        Model baseline = mock(Model.class);
        Model control = mock(Model.class);
        when(modelConfig.buildChain(any(), any(), any(), any())).thenReturn(baseline);
        when(modelConfig.buildByProvider(any(), any(), any(), any(), any()))
            .thenReturn(control)
            .thenThrow(new IllegalStateException("treatment build failed"));
        Model initial = mock(Model.class);
        MutableDelegatingModel mutableModel = new MutableDelegatingModel(initial);
        CustomerServiceService customerServiceService = mock(CustomerServiceService.class);
        NacosPromptService promptService = new NacosPromptService(properties);
        RuntimeConfigApplier applier = new RuntimeConfigApplier(properties, modelConfig, mutableModel,
            promptService, customerServiceService, endpointPolicy());
        CustomerWorkRuntimeConfig dto = sampleDto();
        dto.setSchemaVersion(2);
        dto.setOnlineExperiment(experiment());

        boolean applied = applier.apply(dto, "primary", null, Map.of(),
            Map.of(11L, "control-key", 12L, "treatment-key"));

        assertFalse(applied);
        assertSame(initial, mutableModel.current());
        assertEquals(oldProvider, properties.getModel().getProvider());
        assertTrue(promptService.currentPrompt().isEmpty());
        verify(customerServiceService, never()).flushHotAgents();
    }

    private CustomerWorkRuntimeConfig.RoutingDeployment deployment(Long id, String name) {
        CustomerWorkRuntimeConfig.RoutingDeployment deployment =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        deployment.setDeploymentId(id);
        deployment.setProvider("openai");
        deployment.setName(name);
        deployment.setBaseUrl("https://api.example.com");
        deployment.setApiKeyCipher("cipher-" + id);
        return deployment;
    }

    private CustomerWorkRuntimeConfig.OnlineExperiment experiment() {
        CustomerWorkRuntimeConfig.ExperimentArm control = new CustomerWorkRuntimeConfig.ExperimentArm();
        control.setArm("CONTROL");
        control.setDeploymentId(11L);
        control.setProvider("openai");
        control.setName("control-model");
        control.setBaseUrl("https://control.example.com");
        control.setApiKeyCipher("control-cipher");
        CustomerWorkRuntimeConfig.ExperimentArm treatment = new CustomerWorkRuntimeConfig.ExperimentArm();
        treatment.setArm("TREATMENT");
        treatment.setDeploymentId(12L);
        treatment.setProvider("dashscope");
        treatment.setName("treatment-model");
        treatment.setBaseUrl("https://treatment.example.com");
        treatment.setApiKeyCipher("treatment-cipher");
        CustomerWorkRuntimeConfig.OnlineExperiment experiment =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        experiment.setExperimentId(7L);
        experiment.setRevision(3);
        experiment.setAssignmentSalt("salt-123");
        experiment.setTreatmentBps(5000);
        experiment.setExpiresAtEpochMs(System.currentTimeMillis() + 60000);
        experiment.setControl(control);
        experiment.setTreatment(treatment);
        return experiment;
    }

    private CustomerWorkRuntimeConfig.RoutingRule rule(Long id, String purpose, Long deploymentId,
                                                        int priority,
                                                        CustomerWorkRuntimeConfig.RoutingCondition condition) {
        CustomerWorkRuntimeConfig.RoutingRule rule = new CustomerWorkRuntimeConfig.RoutingRule();
        rule.setRuleId(id);
        rule.setPurpose(purpose);
        rule.setDeploymentId(deploymentId);
        rule.setPriority(priority);
        rule.setCondition(condition);
        return rule;
    }

    private ModelEndpointPolicy endpointPolicy() {
        return new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})});
    }
}
