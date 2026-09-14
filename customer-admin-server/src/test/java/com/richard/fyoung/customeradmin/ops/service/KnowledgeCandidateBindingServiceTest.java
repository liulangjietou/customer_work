package com.richard.fyoung.customeradmin.ops.service;

import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.ID;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.change;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateBindingStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeCandidateBindingServiceTest {
    private final KnowledgeCandidateService candidates = mock(KnowledgeCandidateService.class);
    private final KnowledgeCandidateSnapshotService snapshots = mock(KnowledgeCandidateSnapshotService.class);
    private final KnowledgeTrialModelService models = mock(KnowledgeTrialModelService.class);
    private final EvalDatasetAdminService datasets = mock(EvalDatasetAdminService.class);
    private final AiAgentMapper agents = mock(AiAgentMapper.class);
    private final KnowledgeCandidateBindingStore store = mock(KnowledgeCandidateBindingStore.class);
    private final KnowledgeCandidateBindingService service = new KnowledgeCandidateBindingService(
        candidates, snapshots, models, datasets, agents, store);
    private KnowledgeCandidateBinding expected;
    private AiAgent agent;

    @BeforeEach
    void setUp() {
        TenantContext.set("TenantA");
        expected = binding("TenantA");
        agent = new AiAgent(); agent.setId(7L); agent.setTenantId("TenantA"); agent.setStatus(1);
        agent.setAgentCode("assistant"); agent.setSystemPrompt("冻结提示词"); agent.setMaxIters(3);
        when(agents.selectById(7L)).thenReturn(agent);
        when(candidates.requireCurrentVersion(ID, 2)).thenReturn(new KnowledgeCandidate(
            ID, HASH, 2, KnowledgeCandidate.DRAFT, 3, "标题", "内容", "关键词", "content-hash", 42, 100));
        when(snapshots.freeze(ID, 2)).thenReturn(expected.knowledge());
        when(models.freeze(11L)).thenReturn(expected.model()); when(models.freeze(12L)).thenReturn(expected.judge());
        when(datasets.requireApprovedQualityCase("release-1", "target")).thenReturn(expected.dataset());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void freezesAllInputsWithoutPersistingOrResolvingSecretsDuringPreparation() {
        var value = service.prepare(1, HASH, request());
        assertEquals(expected, value);
        assertDoesNotThrow(() -> service.requireCurrent(value, HASH));
        verifyNoInteractions(store);
        service.save(value, 42);
        verify(store).save(value, 42);
    }

    @Test
    void cannotAttachAnotherQuestionOrAnotherTenantAgent() {
        assertThrows(BizException.class, () -> service.prepare(1, "b".repeat(64), request()));
        verifyNoInteractions(agents, snapshots, models, datasets, store);
        agent.setTenantId("tenanta");
        assertThrows(BizException.class, () -> service.prepare(1, HASH, request()));
        verifyNoInteractions(snapshots, models, datasets, store);
    }

    @Test
    void rejectsDisabledAgentOrUndefinedPromptBeforeTakingKnowledgeSnapshot() {
        agent.setStatus(0);
        assertThrows(BizException.class, () -> service.prepare(1, HASH, request()));
        agent.setStatus(1); agent.setSystemPrompt(" ");
        assertThrows(BizException.class, () -> service.prepare(1, HASH, request()));
        verifyNoInteractions(snapshots, models, datasets, store);
    }

    @Test
    void inputOrAlgorithmDriftRequiresRebindingButHistoricalFingerprintRemainsReadable() throws Exception {
        var mapper = new ObjectMapper();
        var old = change(expected, "rubricVersion", "historical-rubric");
        assertEquals(old, mapper.readValue(mapper.writeValueAsString(old), KnowledgeCandidateBinding.class));
        assertNotEquals(old.fingerprint(), expected.fingerprint());
        assertThrows(BizException.class, () -> service.requireCurrent(old, HASH));
        var oldTool = change(expected, "toolVersion", "historical-tool");
        assertThrows(BizException.class, () -> service.requireCurrent(oldTool, HASH));
        agent.setSystemPrompt("新提示词");
        assertThrows(BizException.class, () -> service.requireCurrent(expected, HASH));
    }

    @Test
    void foreignBindingCannotTriggerReadsOrWrites() {
        clearInvocations(candidates, agents, snapshots, models, datasets, store);
        TenantContext.set("tenanta");
        assertThrows(BizException.class, () -> service.requireCurrent(expected, HASH));
        assertThrows(BizException.class, () -> service.save(expected, 42));
        verifyNoInteractions(candidates, agents, snapshots, models, datasets, store);
    }

    private KnowledgeCandidateBindRequest request() {
        return new KnowledgeCandidateBindRequest(ID, 2, 7L, 11L, 12L, "release-1", "target");
    }
}
