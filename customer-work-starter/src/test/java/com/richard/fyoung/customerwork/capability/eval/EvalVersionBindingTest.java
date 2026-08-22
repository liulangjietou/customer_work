package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.infra.config.properties.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalVersionBindingTest {

    @Test
    void runtimeCandidateShouldMatchSameEffectiveModelPromptAgentAndTools() {
        CustomerWorkProperties properties = properties();
        String prompt = "你是企业客服";
        EvalVersionBinding actual = EvalVersionBinding.fromProperties(properties, EvalType.QUALITY,
            PromptVersion.fingerprintOf(prompt), "judge-v1", "rubric-v1")
            .withDataset(new EvalDatasetSnapshot(
                "dataset-v1", EvalType.QUALITY, "dataset-hash", 1, "[]", 1L));

        EvalVersionBinding candidate = EvalVersionBinding.fromRuntimeConfig(runtime(properties, prompt));

        assertTrue(actual.isComplete());
        assertTrue(actual.matchesCandidate(candidate));
    }

    @Test
    void credentialsShouldNotChangeVersionButModelIdentityShould() {
        CustomerWorkProperties first = properties();
        CustomerWorkProperties rotated = properties();
        rotated.getModel().setApiKey("rotated-secret");
        rotated.getMcp().getServers().get(0).setHeaders(Map.of("Authorization", "Bearer rotated"));

        EvalVersionBinding before = EvalVersionBinding.fromProperties(
            first, EvalType.INTENT, "prompt-v1", "ignored", "rubric-v1");
        EvalVersionBinding afterRotation = EvalVersionBinding.fromProperties(
            rotated, EvalType.INTENT, "prompt-v1", "ignored", "rubric-v1");
        rotated.getModel().setName("qwen-plus");
        EvalVersionBinding afterModelChange = EvalVersionBinding.fromProperties(
            rotated, EvalType.INTENT, "prompt-v1", "ignored", "rubric-v1");

        assertEquals(before.modelVersion(), afterRotation.modelVersion());
        assertEquals(before.toolVersion(), afterRotation.toolVersion());
        assertNotEquals(before.modelVersion(), afterModelChange.modelVersion());
        assertFalse(EvalVersionBinding.legacy("prompt-v1").isComplete());
    }

    @Test
    void routingFingerprint_shouldExcludeCipherButIncludePolicyAndRuleIdentity() {
        CustomerWorkRuntimeConfig runtime = runtime(properties(), "你是企业客服");
        runtime.setRoutingPolicy(routingPolicy("ROUTE_CIPHER_V1", "policy-hash-v1", 100));
        EvalVersionBinding before = EvalVersionBinding.fromRuntimeConfig(runtime);

        runtime.getRoutingPolicy().getDeployments().get(0).setApiKeyCipher("ROUTE_CIPHER_ROTATED");
        EvalVersionBinding afterRotation = EvalVersionBinding.fromRuntimeConfig(runtime);
        runtime.getRoutingPolicy().setPolicyContentHash("policy-hash-v2");
        EvalVersionBinding afterPolicyChange = EvalVersionBinding.fromRuntimeConfig(runtime);
        runtime.getRoutingPolicy().setPolicyContentHash("policy-hash-v1");
        runtime.getRoutingPolicy().getRules().get(0).setPriority(50);
        EvalVersionBinding afterRuleChange = EvalVersionBinding.fromRuntimeConfig(runtime);

        assertEquals(before.modelVersion(), afterRotation.modelVersion());
        assertEquals(before.agentVersion(), afterRotation.agentVersion());
        assertNotEquals(before.modelVersion(), afterPolicyChange.modelVersion());
        assertNotEquals(before.modelVersion(), afterRuleChange.modelVersion());
    }

    @Test
    void onlineExperimentFingerprint_shouldExcludeArmCipherButIncludeAssignmentIdentity() {
        CustomerWorkRuntimeConfig runtime = runtime(properties(), "你是企业客服");
        runtime.setOnlineExperiment(onlineExperiment("CONTROL_CIPHER", "TREATMENT_CIPHER"));
        EvalVersionBinding before = EvalVersionBinding.fromRuntimeConfig(runtime);

        runtime.getOnlineExperiment().getControl().setApiKeyCipher("CONTROL_CIPHER_ROTATED");
        runtime.getOnlineExperiment().getTreatment().setApiKeyCipher("TREATMENT_CIPHER_ROTATED");
        EvalVersionBinding afterRotation = EvalVersionBinding.fromRuntimeConfig(runtime);
        runtime.getOnlineExperiment().setRevision(8);
        EvalVersionBinding afterRevisionChange = EvalVersionBinding.fromRuntimeConfig(runtime);
        runtime.getOnlineExperiment().setRevision(7);
        runtime.getOnlineExperiment().setTreatmentBps(3500);
        EvalVersionBinding afterTrafficChange = EvalVersionBinding.fromRuntimeConfig(runtime);

        assertEquals(before.modelVersion(), afterRotation.modelVersion());
        assertEquals(before.agentVersion(), afterRotation.agentVersion());
        assertNotEquals(before.modelVersion(), afterRevisionChange.modelVersion());
        assertNotEquals(before.agentVersion(), afterRevisionChange.agentVersion());
        assertNotEquals(before.modelVersion(), afterTrafficChange.modelVersion());
        assertNotEquals(before.agentVersion(), afterTrafficChange.agentVersion());
    }

    private CustomerWorkProperties properties() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getModel().setProvider("dashscope");
        properties.getModel().setName("qwen-max");
        properties.getModel().setBaseUrl("https://model.example");
        properties.getModel().setApiKey("secret");
        properties.getAgent().setMaxIters(8);
        properties.getMcp().setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setName("crm");
        server.setUrl("https://mcp.example");
        server.setTransport("sse");
        server.setHeaders(Map.of("Authorization", "Bearer secret"));
        properties.getMcp().setServers(List.of(server));
        return properties;
    }

    private CustomerWorkRuntimeConfig runtime(CustomerWorkProperties properties, String prompt) {
        CustomerWorkRuntimeConfig runtime = new CustomerWorkRuntimeConfig();
        runtime.getModel().setProvider(properties.getModel().getProvider());
        runtime.getModel().setName(properties.getModel().getName());
        runtime.getModel().setBaseUrl(properties.getModel().getBaseUrl());
        runtime.getModel().setApiKeyCipher("cipher-does-not-affect-version");
        CustomerWorkRuntimeConfig.Agent agent = new CustomerWorkRuntimeConfig.Agent();
        agent.setMaxIters(properties.getAgent().getMaxIters());
        runtime.setAgent(agent);
        runtime.setSystemPrompt(prompt);
        CustomerWorkRuntimeConfig.McpServer server = new CustomerWorkRuntimeConfig.McpServer();
        server.setName("crm");
        server.setUrl("https://mcp.example");
        server.setTransport("sse");
        server.setHeaders(Map.of("Authorization", "Bearer cipher"));
        runtime.setMcpServers(List.of(server));
        return runtime;
    }

    private CustomerWorkRuntimeConfig.RoutingPolicy routingPolicy(String cipher,
                                                                   String policyHash,
                                                                   int priority) {
        CustomerWorkRuntimeConfig.RoutingDeployment deployment =
            new CustomerWorkRuntimeConfig.RoutingDeployment();
        deployment.setDeploymentId(10L);
        deployment.setProvider("dashscope");
        deployment.setName("qwen-max");
        deployment.setBaseUrl("https://route.example");
        deployment.setEndpointRevision(3);
        deployment.setApiKeyCipher(cipher);
        CustomerWorkRuntimeConfig.RoutingCondition condition =
            new CustomerWorkRuntimeConfig.RoutingCondition();
        condition.setAgentIds(List.of(7L));
        condition.setChannelCodes(List.of("webchat"));
        CustomerWorkRuntimeConfig.RoutingRule rule = new CustomerWorkRuntimeConfig.RoutingRule();
        rule.setRuleId(21L);
        rule.setPurpose("DEFAULT");
        rule.setDeploymentId(10L);
        rule.setPriority(priority);
        rule.setCondition(condition);
        CustomerWorkRuntimeConfig.RoutingPolicy policy = new CustomerWorkRuntimeConfig.RoutingPolicy();
        policy.setPolicyId(1L);
        policy.setVersionId(11L);
        policy.setVersionNo(1);
        policy.setPolicyContentHash(policyHash);
        policy.setAgentId(7L);
        policy.setChannelCode("webchat");
        policy.setDeployments(List.of(deployment));
        policy.setRules(List.of(rule));
        return policy;
    }

    private CustomerWorkRuntimeConfig.OnlineExperiment onlineExperiment(String controlCipher,
                                                                         String treatmentCipher) {
        CustomerWorkRuntimeConfig.ExperimentArm control = new CustomerWorkRuntimeConfig.ExperimentArm();
        control.setArm("CONTROL");
        control.setDeploymentId(10L);
        control.setProvider("dashscope");
        control.setName("qwen-max");
        control.setBaseUrl("https://control.example");
        control.setEndpointRevision(3);
        control.setApiKeyCipher(controlCipher);
        CustomerWorkRuntimeConfig.ExperimentArm treatment = new CustomerWorkRuntimeConfig.ExperimentArm();
        treatment.setArm("TREATMENT");
        treatment.setDeploymentId(20L);
        treatment.setProvider("openai");
        treatment.setName("gpt-4o");
        treatment.setBaseUrl("https://treatment.example");
        treatment.setEndpointRevision(4);
        treatment.setApiKeyCipher(treatmentCipher);
        CustomerWorkRuntimeConfig.OnlineExperiment experiment =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        experiment.setExperimentId(99L);
        experiment.setRevision(7);
        experiment.setAssignmentSalt("stable-salt");
        experiment.setTreatmentBps(2500);
        experiment.setControl(control);
        experiment.setTreatment(treatment);
        return experiment;
    }
}
