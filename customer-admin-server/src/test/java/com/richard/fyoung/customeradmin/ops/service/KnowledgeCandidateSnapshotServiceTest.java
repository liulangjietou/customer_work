package com.richard.fyoung.customeradmin.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeTrialSnapshot;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class KnowledgeCandidateSnapshotServiceTest {
    private final KnowledgeCandidateService candidates = mock(KnowledgeCandidateService.class);
    private final OpsGatewayProvider gateway = mock(OpsGatewayProvider.class);
    private final KnowledgeMapper mapper = mock(KnowledgeMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeCandidateSnapshotService service = new KnowledgeCandidateSnapshotService(candidates, gateway, json);
    private KnowledgeDO published;

    @BeforeEach
    void setUp() {
        TenantContext.set("TenantA");
        var ops = mock(OpsGateway.class);
        when(gateway.get()).thenReturn(ops); when(ops.knowledgeMapper()).thenReturn(mapper);
        when(candidates.requireCurrentVersion("candidate-1", 2)).thenReturn(new KnowledgeCandidate(
            "candidate-1", "question-hash", 2, KnowledgeCandidate.DRAFT, 3,
            "新标题", "新正文", "新关键词", "candidate-content-hash", 42, 100));
        published = new KnowledgeDO(); published.setId(25L); published.setKeyword("关键词");
        published.setTitle("旧标题"); published.setContent("旧正文"); published.setSource("正式来源");
        when(mapper.snapshotForTenant("TenantA")).thenReturn(List.of(published));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void freezesBothKnowledgeSourcesAndRoundTripsWithoutMutableReferences() throws Exception {
        var snapshot = service.freeze("candidate-1", 2);
        var corpus = json.readTree(snapshot.corpusJson());
        assertEquals(2, corpus.size());
        assertEquals(1, json.readTree(snapshot.baselineCorpusJson()).size());
        assertEquals("旧正文", json.readTree(snapshot.baselineCorpusJson()).get(0).path("content").asText());
        assertEquals(26, corpus.get(1).path("id").asLong());
        assertEquals("新正文", corpus.get(1).path("content").asText());
        assertEquals("knowledge-candidate/candidate-1/v2", corpus.get(1).path("source").asText());
        assertEquals(3, snapshot.sourceReviewRevision());
        var restored = json.readValue(json.writeValueAsString(snapshot), KnowledgeTrialSnapshot.class);
        assertEquals(snapshot.fingerprint(), restored.fingerprint());
        published.setContent("外部对象已修改");
        assertEquals("旧正文", json.readTree(snapshot.corpusJson()).get(0).path("content").asText());
        verify(mapper).snapshotForTenant("TenantA"); verifyNoMoreInteractions(mapper);
    }

    @Test
    void acceptsUnchangedInputsAndRejectsFormalKnowledgeDrift() {
        var snapshot = service.freeze("candidate-1", 2);
        assertDoesNotThrow(() -> service.requireUnchanged(snapshot));
        published.setContent("已发布的新规则");
        assertNotEquals(snapshot.fingerprint(), service.freeze("candidate-1", 2).fingerprint());
        assertThrows(BizException.class, () -> service.requireUnchanged(snapshot));
    }

    @Test
    void recheckingAnotherTenantCannotReadOrDiscloseItsCorpus() {
        var snapshot = service.freeze("candidate-1", 2);
        clearInvocations(candidates, mapper);
        TenantContext.set("tenanta");
        assertThrows(BizException.class, () -> service.requireUnchanged(snapshot));
        verifyNoInteractions(candidates, mapper);
    }

    @Test
    void changedCandidateOrReviewIsRejectedBeforeLoadingTheCorpus() {
        when(candidates.requireCurrentVersion("candidate-1", 2)).thenThrow(new BizException(ResultCode.CONFIG_EDIT_CONFLICT));
        assertThrows(BizException.class, () -> service.freeze("candidate-1", 2));
        verifyNoInteractions(mapper);
    }
}
