package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentOperation;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeQualityStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeSyncStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSyncRunMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeSourceSyncCoordinatorTest {

    private AiKnowledgeSourceMapper sourceMapper;
    private AiKnowledgeBaseMapper knowledgeBaseMapper;
    private AiKnowledgeDocumentMapper documentMapper;
    private AiKnowledgeDocumentRevisionMapper revisionMapper;
    private AiKnowledgeDocumentChunkMapper chunkMapper;
    private AiKnowledgeSyncRunMapper runMapper;
    private KnowledgeBaseVersionService versionService;
    private KnowledgeSourceSyncCoordinator coordinator;
    private AiKnowledgeSource source;
    private AiKnowledgeSyncRun run;

    @BeforeAll
    static void initTableInfo() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeDocument.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeSyncRun.class);
    }

    @BeforeEach
    void setUp() {
        sourceMapper = mock(AiKnowledgeSourceMapper.class);
        knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
        documentMapper = mock(AiKnowledgeDocumentMapper.class);
        revisionMapper = mock(AiKnowledgeDocumentRevisionMapper.class);
        chunkMapper = mock(AiKnowledgeDocumentChunkMapper.class);
        runMapper = mock(AiKnowledgeSyncRunMapper.class);
        versionService = mock(KnowledgeBaseVersionService.class);
        coordinator = new KnowledgeSourceSyncCoordinator(sourceMapper, knowledgeBaseMapper,
            documentMapper, revisionMapper, chunkMapper, runMapper, versionService);

        source = new AiKnowledgeSource();
        source.setId(8L);
        source.setKnowledgeBaseId(7L);
        source.setSourceCode("source-a");
        source.setStatus(StatusFlags.ENABLED);
        source.setCurrentCheckpoint("cp-0");
        source.setQualityThreshold(new BigDecimal("0.8000"));
        source.setRevision(3);
        AiKnowledgeBase knowledgeBase = new AiKnowledgeBase();
        knowledgeBase.setId(7L);
        run = new AiKnowledgeSyncRun();
        run.setId(11L);
        run.setSourceId(8L);
        run.setStatus(KnowledgeSyncStatus.PROCESSING.name());

        when(sourceMapper.selectOne(any(QueryWrapper.class))).thenReturn(source);
        when(knowledgeBaseMapper.selectOne(any(QueryWrapper.class))).thenReturn(knowledgeBase);
        when(runMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(run);
    }

    @Test
    void commit_shouldFreezeUpsertLineageChunksVersionAndCheckpoint() {
        LocalDateTime sourceUpdatedAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        KnowledgeDocumentChangeRequest change = new KnowledgeDocumentChangeRequest(
            "UPSERT", "doc-1", "v1", "标题", "https://example.test/doc-1",
            "  正文  ", sourceUpdatedAt, null);
        KnowledgeSyncRequest request = new KnowledgeSyncRequest(
            "req-1", "cp-0", " cp-1 ", false, 1, List.of(change));
        KnowledgeDocumentIndexer.PreparedDocument prepared =
            new KnowledgeDocumentIndexer.PreparedDocument(change, "hash-1",
                new KnowledgeDocumentIndexer.NormalizedAcl(
                    "RESTRICTED", "USER", "[\"user-1\"]", "[\"H5\"]"),
                List.of(new KnowledgeDocumentIndexer.PreparedChunk(
                    0, "正文", "[0.1,0.2]", 2)));
        AiKnowledgeDocument active = document(100L, 200L, "doc-1", "hash-1");
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(), List.of(active), List.of(active));
        when(documentMapper.insert(any(AiKnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.<AiKnowledgeDocument>getArgument(0).setId(100L);
            return 1;
        });
        when(revisionMapper.insert(any(AiKnowledgeDocumentRevision.class))).thenAnswer(invocation -> {
            invocation.<AiKnowledgeDocumentRevision>getArgument(0).setId(200L);
            return 1;
        });
        AiKnowledgeBaseVersion version = version(500L, "snapshot-1");
        when(versionService.createDocumentSnapshotVersion(eq(7L), eq("cp-1"),
            any(BigDecimal.class), eq(KnowledgeQualityStatus.PASSED.name()), anyList(), anyString()))
            .thenReturn(version);

        AiKnowledgeSyncRun result = coordinator.commit(
            8L, 11L, request, Map.of("doc-1", prepared));

        assertSame(run, result);
        ArgumentCaptor<AiKnowledgeDocumentRevision> revisionCaptor =
            ArgumentCaptor.forClass(AiKnowledgeDocumentRevision.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        AiKnowledgeDocumentRevision revision = revisionCaptor.getValue();
        assertEquals(KnowledgeDocumentOperation.UPSERT.name(), revision.getOperation());
        assertNull(revision.getParentRevisionId());
        assertEquals("正文", revision.getContent());
        assertEquals("hash-1", revision.getContentHash());
        assertEquals("RESTRICTED", revision.getAclMode());
        assertEquals("USER", revision.getAllowedSubjectTypes());
        assertEquals("[\"user-1\"]", revision.getAllowedSubjectIds());
        assertEquals("[\"H5\"]", revision.getAllowedChannels());

        ArgumentCaptor<AiKnowledgeDocumentChunk> chunkCaptor =
            ArgumentCaptor.forClass(AiKnowledgeDocumentChunk.class);
        verify(chunkMapper).insert(chunkCaptor.capture());
        assertEquals(200L, chunkCaptor.getValue().getDocumentRevisionId());
        assertEquals("[0.1,0.2]", chunkCaptor.getValue().getEmbedding());
        assertEquals(2, chunkCaptor.getValue().getDimensions());
        verify(versionService).createDocumentSnapshotVersion(7L, "cp-1",
            new BigDecimal("1.0000"), KnowledgeQualityStatus.PASSED.name(), List.of(active),
            "文档源 source-a 同步 req-1");
        assertEquals("cp-1", source.getCurrentCheckpoint());
        assertEquals(4, source.getRevision());
        assertEquals(1, run.getUpsertedCount());
        assertEquals(0, run.getDeletedCount());
        assertEquals(500L, run.getKnowledgeBaseVersionId());
        assertEquals("snapshot-1", run.getSnapshotHash());
    }

    @Test
    void fullSnapshot_shouldWriteTombstoneForOmittedDocument() {
        AiKnowledgeDocument current = document(100L, 200L, "doc-1", "hash-1");
        current.setSourceVersion("v1");
        current.setSourceUpdatedAt(LocalDateTime.of(2026, 8, 23, 9, 0));
        AiKnowledgeDocumentRevision currentRevision = new AiKnowledgeDocumentRevision();
        currentRevision.setId(200L);
        currentRevision.setTitle("旧标题");
        currentRevision.setSourceUri("https://example.test/doc-1");
        currentRevision.setAclMode("PUBLIC");
        currentRevision.setAllowedSubjectTypes("");
        currentRevision.setAllowedSubjectIds("[]");
        currentRevision.setAllowedChannels("[]");
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(current), List.of(), List.of());
        when(revisionMapper.selectBatchIds(List.of(200L))).thenReturn(List.of(currentRevision));
        when(revisionMapper.insert(any(AiKnowledgeDocumentRevision.class))).thenAnswer(invocation -> {
            invocation.<AiKnowledgeDocumentRevision>getArgument(0).setId(201L);
            return 1;
        });
        when(versionService.createDocumentSnapshotVersion(eq(7L), eq("cp-1"),
            any(BigDecimal.class), eq(KnowledgeQualityStatus.PASSED.name()), anyList(), anyString()))
            .thenReturn(version(501L, "snapshot-empty"));
        KnowledgeSyncRequest request = new KnowledgeSyncRequest(
            "req-full", "cp-0", "cp-1", true, 0, List.of());

        coordinator.commit(8L, 11L, request, Map.of());

        ArgumentCaptor<AiKnowledgeDocumentRevision> revisionCaptor =
            ArgumentCaptor.forClass(AiKnowledgeDocumentRevision.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        AiKnowledgeDocumentRevision tombstone = revisionCaptor.getValue();
        assertEquals(KnowledgeDocumentOperation.DELETE.name(), tombstone.getOperation());
        assertEquals(200L, tombstone.getParentRevisionId());
        assertEquals("旧标题", tombstone.getTitle());
        assertNull(tombstone.getContent());
        assertEquals(201L, current.getCurrentRevisionId());
        assertEquals(1, current.getDeleted());
        assertEquals(1, run.getDeletedCount());
        assertEquals(0, run.getActiveDocumentCount());
    }

    @Test
    void checkpointConflict_shouldFailBeforeLockingKnowledgeBaseOrMutatingDocuments() {
        KnowledgeSyncRequest request = new KnowledgeSyncRequest(
            "req-stale", "stale", "cp-1", false, 0, List.of());

        assertThrows(BizException.class,
            () -> coordinator.commit(8L, 11L, request, Map.of()));

        verify(knowledgeBaseMapper, never()).selectOne(any(QueryWrapper.class));
        verifyNoInteractions(documentMapper, revisionMapper, chunkMapper, runMapper, versionService);
    }

    @Test
    void qualityGate_shouldRejectIncompleteSnapshotWithoutAdvancingCheckpoint() {
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(), List.of());
        KnowledgeSyncRequest request = new KnowledgeSyncRequest(
            "req-low-quality", "cp-0", "cp-1", false, 2, List.of());

        KnowledgeQualityGateException failure = assertThrows(KnowledgeQualityGateException.class,
            () -> coordinator.commit(8L, 11L, request, Map.of()));

        assertEquals(0, failure.getActiveDocumentCount());
        assertEquals(new BigDecimal("0.0000"), failure.getQualityScore());
        assertEquals("cp-0", source.getCurrentCheckpoint());
        verify(versionService, never()).createDocumentSnapshotVersion(
            any(), any(), any(), any(), anyList(), any());
        verify(sourceMapper, never()).updateById(any(AiKnowledgeSource.class));
        verify(runMapper, never()).updateById(any(AiKnowledgeSyncRun.class));
        assertNull(run.getKnowledgeBaseVersionId());
    }

    private AiKnowledgeDocument document(Long id, Long revisionId,
                                         String externalId, String contentHash) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId(id);
        document.setKnowledgeBaseId(7L);
        document.setSourceId(8L);
        document.setExternalId(externalId);
        document.setCurrentRevisionId(revisionId);
        document.setContentHash(contentHash);
        document.setDeleted(0);
        return document;
    }

    private AiKnowledgeBaseVersion version(Long id, String snapshotHash) {
        AiKnowledgeBaseVersion version = new AiKnowledgeBaseVersion();
        version.setId(id);
        version.setSnapshotHash(snapshotHash);
        return version;
    }
}
