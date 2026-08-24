package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeAclRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSourceSyncServiceTest {

    private KnowledgeSourceService sourceService;
    private KnowledgeDocumentIndexer indexer;
    private KnowledgeSyncRunRecorder recorder;
    private KnowledgeSourceSyncCoordinator coordinator;
    private KnowledgeSourceSyncService service;
    private AiKnowledgeSource source;

    @BeforeEach
    void setUp() {
        sourceService = mock(KnowledgeSourceService.class);
        indexer = mock(KnowledgeDocumentIndexer.class);
        recorder = mock(KnowledgeSyncRunRecorder.class);
        coordinator = mock(KnowledgeSourceSyncCoordinator.class);
        service = new KnowledgeSourceSyncService(sourceService, indexer, recorder, coordinator);
        source = new AiKnowledgeSource();
        source.setId(8L);
        source.setKnowledgeBaseId(7L);
        source.setSourceType("PUSH");
        source.setStatus(StatusFlags.ENABLED);
        source.setDefaultAclJson("{\"mode\":\"PUBLIC\"}");
        when(sourceService.requireSource(7L, 8L)).thenReturn(source);
    }

    @Test
    void sync_shouldPrepareUpserts_andCommitExactlyOnce() {
        KnowledgeSyncRequest request = request("req-1");
        AiKnowledgeSyncRun started = run(11L);
        AiKnowledgeSyncRun completed = run(11L);
        completed.setStatus("SUCCEEDED");
        KnowledgeSyncRunVO expected = new KnowledgeSyncRunVO();
        KnowledgeAclRequest publicAcl = new KnowledgeAclRequest("PUBLIC", List.of(), List.of(), List.of());
        KnowledgeDocumentIndexer.PreparedDocument prepared = new KnowledgeDocumentIndexer.PreparedDocument(
            request.documents().get(0), "hash", new KnowledgeDocumentIndexer.NormalizedAcl(
            "PUBLIC", "", "[]", "[]"), List.of());
        when(recorder.start(source, request)).thenReturn(new KnowledgeSyncRunRecorder.StartResult(started, true));
        when(indexer.parseAcl(source.getDefaultAclJson())).thenReturn(publicAcl);
        when(indexer.prepare(request.documents().get(0), publicAcl)).thenReturn(prepared);
        when(coordinator.commit(eq(8L), eq(11L), eq(request), anyMap())).thenReturn(completed);
        when(recorder.toVo(completed)).thenReturn(expected);

        KnowledgeSyncRunVO actual = service.sync(7L, 8L, request);

        assertSame(expected, actual);
        verify(coordinator).commit(8L, 11L, request, Map.of("doc-1", prepared));
        verify(recorder, never()).fail(any(), any(), any());
    }

    @Test
    void duplicateRequest_shouldReturnPersistedRun_withoutEmbeddingOrCommit() {
        KnowledgeSyncRequest request = request("req-1");
        AiKnowledgeSyncRun existing = run(11L);
        KnowledgeSyncRunVO expected = new KnowledgeSyncRunVO();
        when(recorder.start(source, request)).thenReturn(
            new KnowledgeSyncRunRecorder.StartResult(existing, false));
        when(recorder.toVo(existing)).thenReturn(expected);

        assertSame(expected, service.sync(7L, 8L, request));
        verify(indexer, never()).prepare(any(), any());
        verify(coordinator, never()).commit(any(), any(), any(), anyMap());
    }

    @Test
    void qualityFailure_shouldPersistFailure_andNotLeakInternalException() {
        KnowledgeSyncRequest request = request("req-1");
        AiKnowledgeSyncRun started = run(11L);
        KnowledgeAclRequest publicAcl = new KnowledgeAclRequest("PUBLIC", List.of(), List.of(), List.of());
        KnowledgeDocumentIndexer.PreparedDocument prepared = new KnowledgeDocumentIndexer.PreparedDocument(
            request.documents().get(0), "hash", new KnowledgeDocumentIndexer.NormalizedAcl(
            "PUBLIC", "", "[]", "[]"), List.of());
        KnowledgeQualityGateException failure = new KnowledgeQualityGateException(
            2, 1, new BigDecimal("0.5000"), new BigDecimal("0.8000"));
        when(recorder.start(source, request)).thenReturn(new KnowledgeSyncRunRecorder.StartResult(started, true));
        when(indexer.parseAcl(source.getDefaultAclJson())).thenReturn(publicAcl);
        when(indexer.prepare(any(), any())).thenReturn(prepared);
        when(coordinator.commit(eq(8L), eq(11L), eq(request), anyMap())).thenThrow(failure);

        assertThrows(BizException.class, () -> service.sync(7L, 8L, request));

        verify(recorder).fail(11L, 8L, failure);
    }

    @Test
    void duplicateExternalIds_shouldFailBeforeCreatingRun() {
        KnowledgeDocumentChangeRequest document = request("req-1").documents().get(0);
        KnowledgeSyncRequest duplicated = new KnowledgeSyncRequest(
            "req-2", "cp-0", "cp-1", false, null, List.of(document, document));

        assertThrows(BizException.class, () -> service.sync(7L, 8L, duplicated));
        verify(recorder, never()).start(any(), any());
    }

    @Test
    void disabledSource_shouldFailBeforeEmbeddingOrCreatingRun() {
        source.setStatus(StatusFlags.DISABLED);

        assertThrows(BizException.class, () -> service.sync(7L, 8L, request("req-1")));

        verify(recorder, never()).start(any(), any());
        verify(indexer, never()).prepare(any(), any());
    }

    private KnowledgeSyncRequest request(String requestId) {
        KnowledgeDocumentChangeRequest document = new KnowledgeDocumentChangeRequest(
            "UPSERT", "doc-1", "v1", "标题", "https://example.test/doc-1",
            "正文", null, null);
        return new KnowledgeSyncRequest(requestId, "cp-0", "cp-1", false, 1, List.of(document));
    }

    private AiKnowledgeSyncRun run(Long id) {
        AiKnowledgeSyncRun run = new AiKnowledgeSyncRun();
        run.setId(id);
        return run;
    }
}
