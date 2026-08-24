package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** checkpoint CAS、文档 lineage、删除同步与 KB 版本提交的单一事务边界。 */
@Component
public class KnowledgeSourceSyncCoordinator {

    private final AiKnowledgeSourceMapper sourceMapper;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeDocumentChunkMapper chunkMapper;
    private final AiKnowledgeSyncRunMapper runMapper;
    private final KnowledgeBaseVersionService versionService;

    public KnowledgeSourceSyncCoordinator(AiKnowledgeSourceMapper sourceMapper,
                                          AiKnowledgeBaseMapper knowledgeBaseMapper,
                                          AiKnowledgeDocumentMapper documentMapper,
                                          AiKnowledgeDocumentRevisionMapper revisionMapper,
                                          AiKnowledgeDocumentChunkMapper chunkMapper,
                                          AiKnowledgeSyncRunMapper runMapper,
                                          KnowledgeBaseVersionService versionService) {
        this.sourceMapper = sourceMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.chunkMapper = chunkMapper;
        this.runMapper = runMapper;
        this.versionService = versionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeSyncRun commit(Long sourceId,
                                     Long runId,
                                     KnowledgeSyncRequest request,
                                     Map<String, KnowledgeDocumentIndexer.PreparedDocument> prepared) {
        AiKnowledgeSource source = lockSource(sourceId);
        if (!Integer.valueOf(StatusFlags.ENABLED).equals(source.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "文档源已停用: " + sourceId);
        }
        assertCheckpoint(source, request.expectedCheckpoint());
        lockKnowledgeBase(source.getKnowledgeBaseId());

        List<AiKnowledgeDocument> currentDocuments = documentMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeDocument>().eq(AiKnowledgeDocument::getSourceId, sourceId));
        Map<String, AiKnowledgeDocument> byExternalId = currentDocuments.stream()
            .collect(Collectors.toMap(AiKnowledgeDocument::getExternalId, Function.identity()));
        Map<Long, AiKnowledgeDocumentRevision> currentRevisions = currentRevisionMap(currentDocuments);
        Counters counters = new Counters();
        Set<String> presentIds = new HashSet<>();

        for (KnowledgeDocumentChangeRequest change : request.documents()) {
            KnowledgeDocumentOperation operation = operation(change.operation());
            if (operation == KnowledgeDocumentOperation.UPSERT) {
                presentIds.add(change.externalId());
                AiKnowledgeDocument document = byExternalId.get(change.externalId());
                KnowledgeDocumentIndexer.PreparedDocument next = prepared.get(change.externalId());
                if (next == null) {
                    throw new IllegalStateException("prepared UPSERT missing: " + change.externalId());
                }
                if (unchanged(document, currentRevision(currentRevisions, document), next)) {
                    counters.unchanged++;
                    continue;
                }
                AiKnowledgeDocument saved = upsert(source, document, next);
                byExternalId.put(change.externalId(), saved);
                counters.upserted++;
            } else {
                AiKnowledgeDocument document = byExternalId.get(change.externalId());
                if (delete(source, document, currentRevision(currentRevisions, document), change)) {
                    counters.deleted++;
                } else {
                    counters.unchanged++;
                }
            }
        }

        if (Boolean.TRUE.equals(request.fullSnapshot())) {
            for (AiKnowledgeDocument document : currentDocuments) {
                if (document.getDeleted() == 0 && !presentIds.contains(document.getExternalId())) {
                    if (delete(source, document, currentRevisions.get(document.getCurrentRevisionId()), null)) {
                        counters.deleted++;
                    }
                }
            }
        }

        List<AiKnowledgeDocument> activeForSource = activeDocuments(source.getKnowledgeBaseId(), sourceId);
        int duplicateContentCount = duplicateContentCount(activeForSource);
        BigDecimal qualityScore = qualityScore(activeForSource.size(), duplicateContentCount,
            request.expectedDocumentCount());
        BigDecimal threshold = source.getQualityThreshold() == null
            ? new BigDecimal("0.8000") : source.getQualityThreshold();
        if (qualityScore.compareTo(threshold) < 0) {
            throw new KnowledgeQualityGateException(activeForSource.size(), duplicateContentCount,
                qualityScore, threshold);
        }

        List<AiKnowledgeDocument> activeForKnowledgeBase = activeDocuments(source.getKnowledgeBaseId(), null);
        AiKnowledgeBaseVersion version = versionService.createDocumentSnapshotVersion(
            source.getKnowledgeBaseId(), request.checkpoint().trim(), qualityScore,
            KnowledgeQualityStatus.PASSED.name(), activeForKnowledgeBase,
            "文档源 " + source.getSourceCode() + " 同步 " + request.requestId());
        LocalDateTime now = LocalDateTime.now();
        source.setCurrentCheckpoint(request.checkpoint().trim());
        source.setLastSyncAt(now);
        source.setLastSuccessfulSyncAt(now);
        source.setLastSyncStatus(KnowledgeSyncStatus.SUCCEEDED.name());
        source.setLastSyncError(null);
        source.setActiveDocumentCount(activeForSource.size());
        source.setQualityScore(qualityScore);
        source.setQualityStatus(KnowledgeQualityStatus.PASSED.name());
        source.setRevision((source.getRevision() == null ? 0 : source.getRevision()) + 1);
        sourceMapper.updateById(source);

        AiKnowledgeSyncRun run = requireProcessingRun(runId, sourceId);
        run.setStatus(KnowledgeSyncStatus.SUCCEEDED.name());
        run.setUpsertedCount(counters.upserted);
        run.setDeletedCount(counters.deleted);
        run.setUnchangedCount(counters.unchanged);
        run.setActiveDocumentCount(activeForSource.size());
        run.setDuplicateContentCount(duplicateContentCount);
        run.setQualityScore(qualityScore);
        run.setQualityStatus(KnowledgeQualityStatus.PASSED.name());
        run.setKnowledgeBaseVersionId(version.getId());
        run.setSnapshotHash(version.getSnapshotHash());
        run.setFinishedAt(now);
        runMapper.updateById(run);
        return run;
    }

    private AiKnowledgeDocument upsert(AiKnowledgeSource source,
                                       AiKnowledgeDocument existing,
                                       KnowledgeDocumentIndexer.PreparedDocument prepared) {
        KnowledgeDocumentChangeRequest change = prepared.change();
        AiKnowledgeDocument document = existing;
        if (document == null) {
            document = new AiKnowledgeDocument();
            document.setKnowledgeBaseId(source.getKnowledgeBaseId());
            document.setSourceId(source.getId());
            document.setExternalId(change.externalId());
            document.setDeleted(0);
            documentMapper.insert(document);
        }

        AiKnowledgeDocumentRevision revision = new AiKnowledgeDocumentRevision();
        revision.setDocumentId(document.getId());
        revision.setSourceId(source.getId());
        revision.setParentRevisionId(document.getCurrentRevisionId());
        revision.setOperation(KnowledgeDocumentOperation.UPSERT.name());
        revision.setSourceVersion(change.sourceVersion());
        revision.setTitle(change.title());
        revision.setSourceUri(change.sourceUri());
        revision.setContent(change.content().strip());
        revision.setContentHash(prepared.contentHash());
        revision.setAclMode(prepared.acl().mode());
        revision.setAllowedSubjectTypes(prepared.acl().allowedSubjectTypes());
        revision.setAllowedSubjectIds(prepared.acl().allowedSubjectIds());
        revision.setAllowedChannels(prepared.acl().allowedChannels());
        revision.setSourceUpdatedAt(change.sourceUpdatedAt());
        revisionMapper.insert(revision);

        for (KnowledgeDocumentIndexer.PreparedChunk preparedChunk : prepared.chunks()) {
            AiKnowledgeDocumentChunk chunk = new AiKnowledgeDocumentChunk();
            chunk.setDocumentRevisionId(revision.getId());
            chunk.setChunkIndex(preparedChunk.index());
            chunk.setContent(preparedChunk.content());
            chunk.setEmbedding(preparedChunk.embedding());
            chunk.setDimensions(preparedChunk.dimensions());
            chunkMapper.insert(chunk);
        }

        document.setCurrentRevisionId(revision.getId());
        document.setSourceVersion(change.sourceVersion());
        document.setContentHash(prepared.contentHash());
        document.setDeleted(0);
        document.setSourceUpdatedAt(change.sourceUpdatedAt());
        documentMapper.updateById(document);
        return document;
    }

    private boolean delete(AiKnowledgeSource source,
                           AiKnowledgeDocument document,
                           AiKnowledgeDocumentRevision current,
                           KnowledgeDocumentChangeRequest change) {
        if (document == null || Integer.valueOf(1).equals(document.getDeleted())) {
            return false;
        }
        AiKnowledgeDocumentRevision tombstone = new AiKnowledgeDocumentRevision();
        tombstone.setDocumentId(document.getId());
        tombstone.setSourceId(source.getId());
        tombstone.setParentRevisionId(document.getCurrentRevisionId());
        tombstone.setOperation(KnowledgeDocumentOperation.DELETE.name());
        tombstone.setSourceVersion(change == null ? document.getSourceVersion() : change.sourceVersion());
        tombstone.setTitle(current == null ? null : current.getTitle());
        tombstone.setSourceUri(current == null ? null : current.getSourceUri());
        tombstone.setContentHash(document.getContentHash());
        tombstone.setAclMode(current == null ? null : current.getAclMode());
        tombstone.setAllowedSubjectTypes(current == null ? null : current.getAllowedSubjectTypes());
        tombstone.setAllowedSubjectIds(current == null ? null : current.getAllowedSubjectIds());
        tombstone.setAllowedChannels(current == null ? null : current.getAllowedChannels());
        tombstone.setSourceUpdatedAt(change == null ? document.getSourceUpdatedAt() : change.sourceUpdatedAt());
        revisionMapper.insert(tombstone);
        document.setCurrentRevisionId(tombstone.getId());
        document.setDeleted(1);
        document.setSourceVersion(tombstone.getSourceVersion());
        document.setSourceUpdatedAt(tombstone.getSourceUpdatedAt());
        documentMapper.updateById(document);
        return true;
    }

    private boolean unchanged(AiKnowledgeDocument document,
                              AiKnowledgeDocumentRevision current,
                              KnowledgeDocumentIndexer.PreparedDocument next) {
        if (document == null || Integer.valueOf(1).equals(document.getDeleted()) || current == null) {
            return false;
        }
        KnowledgeDocumentChangeRequest change = next.change();
        return Objects.equals(document.getContentHash(), next.contentHash())
            && Objects.equals(document.getSourceVersion(), change.sourceVersion())
            && Objects.equals(current.getTitle(), change.title())
            && Objects.equals(current.getSourceUri(), change.sourceUri())
            && Objects.equals(current.getAclMode(), next.acl().mode())
            && Objects.equals(current.getAllowedSubjectTypes(), next.acl().allowedSubjectTypes())
            && Objects.equals(current.getAllowedSubjectIds(), next.acl().allowedSubjectIds())
            && Objects.equals(current.getAllowedChannels(), next.acl().allowedChannels());
    }

    private Map<Long, AiKnowledgeDocumentRevision> currentRevisionMap(List<AiKnowledgeDocument> documents) {
        List<Long> ids = documents.stream().map(AiKnowledgeDocument::getCurrentRevisionId)
            .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return revisionMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(AiKnowledgeDocumentRevision::getId, Function.identity()));
    }

    private AiKnowledgeDocumentRevision currentRevision(
        Map<Long, AiKnowledgeDocumentRevision> revisions,
        AiKnowledgeDocument document) {
        return document == null ? null : revisions.get(document.getCurrentRevisionId());
    }

    private List<AiKnowledgeDocument> activeDocuments(Long knowledgeBaseId, Long sourceId) {
        LambdaQueryWrapper<AiKnowledgeDocument> query = new LambdaQueryWrapper<AiKnowledgeDocument>()
            .eq(AiKnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
            .eq(AiKnowledgeDocument::getDeleted, 0)
            .orderByAsc(AiKnowledgeDocument::getSourceId, AiKnowledgeDocument::getExternalId);
        if (sourceId != null) {
            query.eq(AiKnowledgeDocument::getSourceId, sourceId);
        }
        return documentMapper.selectList(query);
    }

    private int duplicateContentCount(List<AiKnowledgeDocument> documents) {
        Map<String, Integer> counts = new HashMap<>();
        for (AiKnowledgeDocument document : documents) {
            counts.merge(document.getContentHash(), 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    private BigDecimal qualityScore(int activeCount, int duplicateCount, Integer expectedCount) {
        BigDecimal uniqueness = activeCount == 0
            ? BigDecimal.ONE
            : BigDecimal.valueOf(activeCount - duplicateCount)
                .divide(BigDecimal.valueOf(activeCount), 4, RoundingMode.HALF_UP);
        BigDecimal coverage = expectedCount == null || expectedCount <= 0
            ? BigDecimal.ONE
            : BigDecimal.valueOf(activeCount)
                .divide(BigDecimal.valueOf(expectedCount), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        return uniqueness.min(coverage).setScale(4, RoundingMode.HALF_UP);
    }

    private KnowledgeDocumentOperation operation(String raw) {
        try {
            return KnowledgeDocumentOperation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "operation 仅支持 UPSERT/DELETE");
        }
    }

    private AiKnowledgeSource lockSource(Long sourceId) {
        AiKnowledgeSource source = sourceMapper.selectOne(new QueryWrapper<AiKnowledgeSource>()
            .eq("id", sourceId).eq("deleted", 0).last("FOR UPDATE"));
        if (source == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "文档源不存在: " + sourceId);
        }
        return source;
    }

    private void lockKnowledgeBase(Long knowledgeBaseId) {
        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new QueryWrapper<AiKnowledgeBase>()
            .eq("id", knowledgeBaseId).last("FOR UPDATE"));
        if (knowledgeBase == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
    }

    private AiKnowledgeSyncRun requireProcessingRun(Long runId, Long sourceId) {
        AiKnowledgeSyncRun run = runMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeSyncRun>()
            .eq(AiKnowledgeSyncRun::getId, runId)
            .eq(AiKnowledgeSyncRun::getSourceId, sourceId));
        if (run == null || !KnowledgeSyncStatus.PROCESSING.name().equals(run.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "同步运行状态已变化: " + runId);
        }
        return run;
    }

    private void assertCheckpoint(AiKnowledgeSource source, String expectedCheckpoint) {
        String expected = normalize(expectedCheckpoint);
        String current = normalize(source.getCurrentCheckpoint());
        if (!Objects.equals(current, expected)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "checkpoint 冲突，当前值=" + current + "，请求期望=" + expected);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class Counters {
        private int upserted;
        private int deleted;
        private int unchanged;
    }
}
