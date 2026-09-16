package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicy;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyRuntimeAccess;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeTrialModelService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 通过真正的主备与策略 Model 验证草稿装配，不能只检查构造了某个类型。 */
class AgentDraftTrialModelsTest {
    private final KnowledgeTrialModelService deployments = mock(KnowledgeTrialModelService.class);
    private final ModelRoutingPolicyRuntimeAccess policies = mock(ModelRoutingPolicyRuntimeAccess.class);
    private final AiModelRoutePolicyMapper mapper = mock(AiModelRoutePolicyMapper.class);
    private final AdminModelFailoverProperties failover = new AdminModelFailoverProperties();
    private final AgentDraftTrialModels models = new AgentDraftTrialModels(deployments, policies, mapper, failover);

    @BeforeEach
    void setup() { TenantContext.set("TenantA"); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void freezingPreservesTheSelectedPrimaryAndBackupOrder() {
        for (long id : List.of(1L, 3L, 2L)) when(deployments.freeze(id)).thenReturn(frozen(id));
        var result = models.freeze(configuration(List.of(3L, 2L), null), 77L);
        assertEquals(List.of(1L, 3L, 2L), result.models().stream().map(FrozenKnowledgeModel::deploymentId).toList());
        verifyNoInteractions(policies, mapper);
    }

    @Test
    void everyTrialGetsItsOwnCircuitMemoryAndFallsBackInTheSelectedOrder() {
        failover.setFailureThreshold(1);
        var calls = new AtomicInteger();
        stubDeployment(1, "primary", Flux.defer(() -> {
            calls.incrementAndGet(); return Flux.error(new IllegalStateException("unavailable"));
        }));
        stubDeployment(2, "backup", Flux.just(response("backup")));
        var draft = draft(List.of(frozen(1), frozen(2)), null);
        var first = models.build(draft);
        assertEquals("backup", answer(first, "hello"));
        assertEquals("backup", answer(first, "hello"));
        assertEquals(1, calls.get());
        assertEquals("backup", answer(models.build(draft), "hello"));
        assertEquals(2, calls.get());
    }

    @Test
    void aPartialPrimaryAnswerCannotBeSplicedWithTheBackupAnswer() {
        stubDeployment(1, "primary", Flux.concat(Flux.just(response("partial")),
            Flux.error(new IllegalStateException("mid-stream"))));
        var backup = model("backup", Flux.just(response("backup")));
        when(deployments.build(frozen(2))).thenReturn(backup);
        var built = models.build(draft(List.of(frozen(1), frozen(2)), null));
        assertThrows(RuntimeException.class, () -> answer(built, "hello"));
        verify(backup, never()).stream(anyList(), anyList(), any());
    }

    @Test
    void routedDraftUsesTheFrozenAdminChannelAndNeverPersistsRuntimeCredentials() throws Exception {
        var runtime = new CustomerWorkRuntimeConfig.RoutingPolicy();
        runtime.setPolicyId(9L); runtime.setVersionId(90L); runtime.setVersionNo(3);
        runtime.setPolicyContentHash("policy-hash"); runtime.setAgentId(77L); runtime.setChannelCode("admin");
        var economical = rule(1L, "ECONOMY", 1);
        var condition = new CustomerWorkRuntimeConfig.RoutingCondition();
        condition.setAgentIds(List.of(77L)); condition.setChannelCodes(List.of("admin")); condition.setMaxInputTokens(100);
        economical.setCondition(condition);
        runtime.setRules(List.of(economical, rule(2L, "DEFAULT", 100), rule(3L, "FALLBACK", 1000)));
        var deployment = new CustomerWorkRuntimeConfig.RoutingDeployment(); deployment.setDeploymentId(1L);
        deployment.setApiKeyCipher("do-not-persist-test-key"); runtime.setDeployments(List.of(deployment));
        when(mapper.selectOne(any())).thenReturn(new AiModelRoutePolicy());
        when(policies.requireActive(9L, 77L, "admin")).thenReturn(runtime);
        for (long id : List.of(1L, 2L, 3L)) {
            when(deployments.freeze(id)).thenReturn(frozen(id));
            stubDeployment(id, "model-" + id, Flux.just(response("model-" + id)));
        }
        var selection = models.freeze(configuration(List.of(), 9L), 77L);
        var draft = draft(selection.models(), selection.routing());
        String serialized = new ObjectMapper().writeValueAsString(draft);
        assertFalse(serialized.contains("do-not-persist-test-key"));
        assertFalse(serialized.contains("apiKey"));
        var built = models.build(draft);
        assertEquals("model-1", answer(built, "hello"));
        assertEquals("model-2", answer(built, "x".repeat(2000)));
        verify(policies).requireActive(9L, 77L, "admin");
    }

    @Test
    void unavailablePolicyCannotReachRuntimeCredentialsOrDeploymentResolution() {
        assertThrows(BizException.class, () -> models.freeze(configuration(List.of(), 9L), 77L));
        verifyNoInteractions(policies, deployments);
    }

    @Test
    void frozenEndpointDriftFailsBeforeAnotherModelCanExecute() {
        when(deployments.build(frozen(1))).thenThrow(new BizException(ResultCode.CONFIG_EDIT_CONFLICT));
        assertThrows(BizException.class, () -> models.build(draft(List.of(frozen(1), frozen(2)), null)));
        verify(deployments, never()).build(frozen(2));
    }

    private FrozenKnowledgeModel frozen(long id) { return new FrozenKnowledgeModel(id, 1, "openai", "https://models.example", "model-" + id, 8192); }

    private AgentSaveRequest configuration(List<Long> backups, Long policy) {
        return new AgentSaveRequest("试用", "trial", 1L, backups, List.of(), List.of(), List.of(), "规则", List.of("chat"),
            null, 1, List.of(), null, null, null, null, null, List.of(), policy);
    }

    private FrozenAgentDraft draft(List<FrozenKnowledgeModel> selected, com.richard.fyoung.customerwork.core.model.routing.PolicyRouteSpec routing) {
        return new FrozenAgentDraft(77L, 1L, configuration(List.of(2L), null), selected, routing, List.of(), List.of(), List.of(), List.of());
    }

    private CustomerWorkRuntimeConfig.RoutingRule rule(long deployment, String purpose, int priority) {
        var rule = new CustomerWorkRuntimeConfig.RoutingRule(); rule.setRuleId(deployment); rule.setDeploymentId(deployment);
        rule.setPurpose(purpose); rule.setPriority(priority); return rule;
    }

    private void stubDeployment(long id, String name, Flux<ChatResponse> responses) {
        var model = model(name, responses);
        when(deployments.build(frozen(id))).thenReturn(model);
    }

    private Model model(String name, Flux<ChatResponse> responses) {
        var model = mock(Model.class); when(model.getModelName()).thenReturn(name); when(model.getContextWindowSize()).thenReturn(8192);
        when(model.stream(anyList(), anyList(), any())).thenReturn(responses); return model;
    }

    private ChatResponse response(String name) { return new ChatResponse(name, List.of(TextBlock.builder().text(name).build()), null, null, "stop"); }

    private String answer(Model model, String prompt) {
        var message = Msg.builder().name("user").role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build();
        return model.stream(List.of(message), List.of(), GenerateOptions.builder().build()).collectList().block().get(0).getId();
    }
}
