package com.richard.fyoung.customeradmin.aiconfig.agent.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 发布就绪检查的含义边界：当前版本、门禁、投递与实例确认各自保留事实。 */
class AgentPublicationCheckServiceTest {
    private final AiAgentMapper agents = mock(AiAgentMapper.class);
    private final AiChannelBindingMapper bindings = mock(AiChannelBindingMapper.class);
    private final RuntimePublishTaskMapper tasks = mock(RuntimePublishTaskMapper.class);
    private final RuntimeConfigAckMapper acks = mock(RuntimeConfigAckMapper.class);
    private final CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentPublicationCheckService service = new AgentPublicationCheckService(
        agents, bindings, tasks, acks, publisher, json);
    private final AiAgent agent = new AiAgent();
    private final RuntimePublishTask task = new RuntimePublishTask();
    private final EvalVersionBinding version = version("prompt-a");

    @BeforeEach
    void setup() throws Exception {
        TenantContext.set("Tenant-A");
        agent.setId(7L); agent.setTenantId("Tenant-A"); agent.setAgentName("售后客服");
        agent.setStatus(1); agent.setRuntimeRevision(5L);
        when(agents.selectOne(any())).thenReturn(agent);
        var channel = new AiChannelBinding(); channel.setChannelCode("web"); channel.setStatus(1);
        when(bindings.selectList(any())).thenReturn(List.of(channel));
        when(publisher.isEnabled()).thenReturn(true);
        when(publisher.previewVersionBinding(7L)).thenReturn(version);
        task.setId("task"); task.setTargetId(7L); task.setTenantId("Tenant-A"); task.setPublishIntent("NORMAL");
        task.setRevision("revision-a"); task.setContentHash("hash-a"); task.setStatus("APPLIED");
        task.setGateStatus("PASSED"); task.setCandidateVersionsJson(json.writeValueAsString(version));
        task.setAckTargetsJson("[\"instance-a\",\"instance-b\"]");
        task.setGateEvalRunIdsJson("[\"eval-1\"]");
        when(tasks.selectOne(any())).thenReturn(task);
        when(acks.selectList(any())).thenReturn(List.of(ack("instance-a", "APPLIED"), ack("instance-b", "APPLIED")));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void onlyTheCurrentCandidateWithAllFrozenTargetsCanBeConfirmed() {
        var result = service.check(7L);
        assertEquals(5L, result.runtimeRevision());
        assertTrue(result.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.CandidateMatch.MATCH, result.latestPublication().currentMatch());
        assertEquals(List.of("eval-1"), result.latestPublication().evalRunIds());
        verify(publisher, never()).publishForAgentId(any());
        verify(publisher, never()).prepareTask(any());
    }

    @Test
    void publishedDeliveryDoesNotMeanTheInstancesHaveAppliedIt() {
        task.setStatus("PUBLISHED");
        when(acks.selectList(any())).thenReturn(List.of());
        var result = service.check(7L);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.Confirmation.WAITING, result.latestPublication().confirmation());
    }

    @Test
    void changingTheCurrentPromptPreservesOldSuccessAsHistoricalEvidence() {
        when(publisher.previewVersionBinding(7L)).thenReturn(version("prompt-b"));
        var result = service.check(7L);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals("APPLIED", result.latestPublication().status());
        assertEquals("PASSED", result.latestPublication().gateStatus());
        assertEquals(AgentPublicationCheck.CandidateMatch.CHANGED, result.latestPublication().currentMatch());
    }

    @Test
    void noGateRequirementIsPreservedRatherThanRewrittenAsEvaluationPassed() {
        task.setGateStatus("NOT_REQUIRED"); task.setGateEvalRunIdsJson("[]");
        var result = service.check(7L);
        assertTrue(result.currentRuntimeConfirmed());
        assertEquals("NOT_REQUIRED", result.latestPublication().gateStatus());
        assertTrue(result.latestPublication().evalRunIds().isEmpty());
        assertNull(result.latestPublication().gateDecision());
    }

    @Test
    void blankHistoricalGateIsNotInventedAsNotRequired() {
        task.setGateStatus(null);
        assertNull(service.check(7L).latestPublication().gateStatus());
    }

    @Test
    void unknownOrEmptyTargetsCannotProveCompleteRuntimeApplication() {
        task.setAckTargetsJson(null);
        var legacy = service.check(7L);
        assertFalse(legacy.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.Confirmation.LEGACY_UNVERIFIED, legacy.latestPublication().confirmation());
        task.setAckTargetsJson("[]");
        var empty = service.check(7L);
        assertFalse(empty.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.Confirmation.NO_TARGETS, empty.latestPublication().confirmation());
        assertTrue(empty.latestPublication().acknowledgements().isEmpty());
    }

    @Test
    void unexpectedInstancesCannotReplaceTheFrozenMissingTarget() {
        when(acks.selectList(any())).thenReturn(List.of(ack("instance-a", "APPLIED"), ack("unexpected", "APPLIED")));
        var result = service.check(7L);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.Confirmation.PARTIAL, result.latestPublication().confirmation());
        assertEquals(1, result.latestPublication().acknowledgements().size());
    }

    @Test
    void rejectedTargetCannotBeHiddenByTheOtherAppliedTarget() {
        when(acks.selectList(any())).thenReturn(List.of(ack("instance-a", "APPLIED"), ack("instance-b", "REJECTED")));
        var result = service.check(7L);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.Confirmation.REJECTED, result.latestPublication().confirmation());
    }

    @Test
    void missingCandidateSnapshotCannotBeMatchedEvenIfTheTaskClaimsApplied() {
        task.setCandidateVersionsJson(null);
        var result = service.check(7L);
        assertFalse(result.currentRuntimeConfirmed());
        assertEquals(AgentPublicationCheck.CandidateMatch.NOT_PREPARED, result.latestPublication().currentMatch());
    }

    @Test
    void unavailableCurrentModelStillAllowsReadingHistoricalPublicationWithoutLeakingItsException() throws Exception {
        when(publisher.previewVersionBinding(7L)).thenThrow(new IllegalStateException("secret-test-only"));
        var result = service.check(7L);
        assertEquals(AgentPublicationCheck.CandidateStatus.UNAVAILABLE, result.candidateStatus());
        assertFalse(result.currentRuntimeConfirmed());
        assertNotNull(result.latestPublication());
        assertFalse(json.writeValueAsString(result).contains("secret-test-only"));
    }

    @Test
    void disabledPublishingDoesNotTryToConstructOrProbeTheCandidate() {
        when(publisher.isEnabled()).thenReturn(false);
        var result = service.check(7L);
        assertEquals(AgentPublicationCheck.CandidateStatus.PUBLISHING_DISABLED, result.candidateStatus());
        assertFalse(result.currentRuntimeConfirmed());
        verify(publisher, never()).previewVersionBinding(any());
    }

    @Test
    void unauthorizedAgentStopsBeforeReadingItsBindingsOrPublication() {
        when(agents.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.check(7L));
        verifyNoInteractions(bindings, tasks, acks, publisher);
    }

    private static EvalVersionBinding version(String prompt) {
        return new EvalVersionBinding("", "", "model-a", prompt, "agent-a", "", "tools-a", "", "");
    }

    private RuntimeConfigAckEntity ack(String instance, String status) {
        var ack = new RuntimeConfigAckEntity(); ack.setInstanceId(instance); ack.setStatus(status);
        ack.setTenantId("Tenant-A"); ack.setRevision("revision-a"); ack.setContentHash("hash-a");
        return ack;
    }
}
