package com.richard.fyoung.customeradmin.ops.service;

import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.ID;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.evaluation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationCommand;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationReceipt;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class KnowledgeCandidatePublicationServiceTest {
    private final KnowledgeCandidateBindingService bindings = mock(KnowledgeCandidateBindingService.class);
    private final KnowledgeCandidateEvaluationService evaluations = mock(KnowledgeCandidateEvaluationService.class);
    private final KnowledgeCandidateStore candidates = mock(KnowledgeCandidateStore.class);
    private final OpsGatewayProvider provider = mock(OpsGatewayProvider.class);
    private final KnowledgePublicationStore publications = mock(KnowledgePublicationStore.class);
    private final KnowledgeCandidatePublicationService service = new KnowledgeCandidatePublicationService(
        bindings, evaluations, candidates, provider, new ObjectMapper());

    @BeforeEach
    void setup() {
        TenantContext.set("tenant-a");
        var gateway = mock(OpsGateway.class);
        when(provider.get()).thenReturn(gateway);
        when(gateway.knowledgePublication()).thenReturn(publications);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void actualProofIsCheckedBeforeReservingAndNoFaqIsWrittenDuringEnqueue() {
        var binding = binding("tenant-a");
        var evaluation = evaluation(binding);
        when(evaluations.require(binding, "run")).thenReturn(evaluation);
        when(evaluations.failures(binding, evaluation)).thenReturn(List.of());
        service.reserve(binding, HASH, "run");
        var ordered = inOrder(bindings, evaluations, candidates);
        ordered.verify(bindings).requireCurrent(binding, HASH);
        ordered.verify(evaluations).require(binding, "run");
        ordered.verify(evaluations).failures(binding, evaluation);
        ordered.verify(candidates).reservePublication("tenant-a", ID, 2);
        verifyNoInteractions(publications);
    }

    @Test
    void failedOrMissingEvaluationCannotReserveCandidate() {
        var binding = binding("tenant-a");
        var evaluation = evaluation(binding);
        when(evaluations.require(binding, "run")).thenReturn(evaluation);
        when(evaluations.failures(binding, evaluation)).thenReturn(List.of("目标未实际召回"));
        assertThrows(BizException.class, () -> service.reserve(binding, HASH, "run"));
        when(evaluations.require(binding, "run")).thenThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND));
        assertThrows(BizException.class, () -> service.reserve(binding, HASH, "run"));
        verifyNoInteractions(candidates, publications);
    }

    @Test
    void committedReceiptSurvivesRestartAndLaterModelOrSourceDrift() {
        var binding = binding("tenant-a");
        var receipt = new KnowledgePublicationReceipt("task", "fingerprint", 812, 1000);
        when(publications.find(any())).thenReturn(Optional.of(receipt));
        doThrow(new BizException(ResultCode.CONFIG_EDIT_CONFLICT)).when(bindings).requireCurrent(any(), any());
        assertEquals(receipt, service.publish(binding, HASH, "run", "task", 42));
        verifyNoInteractions(bindings, evaluations, candidates);
        verify(publications, never()).publish(any());
    }

    @Test
    void newPublicationUsesFrozenContentAndExactAuditIdentifiers() {
        var binding = binding("tenant-a");
        var evaluation = evaluation(binding);
        when(publications.find(any())).thenReturn(Optional.empty());
        when(evaluations.require(binding, "run")).thenReturn(evaluation);
        when(evaluations.failures(binding, evaluation)).thenReturn(List.of());
        service.publish(binding, HASH, "run", "task", 42);
        var capture = ArgumentCaptor.forClass(KnowledgePublicationCommand.class);
        verify(publications).publish(capture.capture());
        var command = capture.getValue();
        assertEquals("候选开票规则", command.content());
        assertEquals(binding.fingerprint(), command.artifactFingerprint());
        assertEquals("[]", command.baselineCorpusJson());
        assertEquals(ID, command.candidateId()); assertEquals(2, command.candidateRevision());
        assertEquals(HASH, command.questionHash()); assertEquals(3, command.sourceReviewRevision());
        assertEquals("run", command.evaluationRunId()); assertEquals(42, command.requestedBy());
        verifyNoInteractions(candidates);
    }

    @Test
    void unknownReceiptReadFailureCannotBeTreatedAsMissingAndRepeatedWrite() {
        when(publications.find(any())).thenThrow(new DataAccessResourceFailureException("receipt database unavailable"));
        assertThrows(DataAccessResourceFailureException.class,
            () -> service.publish(binding("tenant-a"), HASH, "run", "task", 42));
        verify(publications, never()).publish(any());
        verifyNoInteractions(bindings, evaluations, candidates);
    }

    @Test
    void foreignTenantCannotReadReceiptOrPublish() {
        assertThrows(BizException.class, () -> service.publish(binding("Tenant-A"), HASH, "run", "task", 42));
        verifyNoInteractions(provider, publications, bindings, candidates);
    }
}
