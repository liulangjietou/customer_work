package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeQualityStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeSyncStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSyncRunMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 在独立事务中持久化同步开始/失败事实，避免业务事务回滚时丢掉失败证据。 */
@Component
public class KnowledgeSyncRunRecorder {

    private static final int ERROR_MESSAGE_MAX_CHARS = 1000;

    private final AiKnowledgeSyncRunMapper runMapper;
    private final AiKnowledgeSourceMapper sourceMapper;

    public KnowledgeSyncRunRecorder(AiKnowledgeSyncRunMapper runMapper,
                                    AiKnowledgeSourceMapper sourceMapper) {
        this.runMapper = runMapper;
        this.sourceMapper = sourceMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public StartResult start(AiKnowledgeSource source, KnowledgeSyncRequest request) {
        String requestHash = requestHash(request);
        AiKnowledgeSyncRun existing = find(source.getId(), request.requestId());
        if (existing != null) {
            assertSameRequest(existing, requestHash);
            return new StartResult(existing, false);
        }
        AiKnowledgeSyncRun run = new AiKnowledgeSyncRun();
        run.setKnowledgeBaseId(source.getKnowledgeBaseId());
        run.setSourceId(source.getId());
        run.setRequestId(request.requestId().trim());
        run.setRequestHash(requestHash);
        run.setSyncMode(Boolean.TRUE.equals(request.fullSnapshot()) ? "FULL" : "INCREMENTAL");
        run.setCheckpointBefore(source.getCurrentCheckpoint());
        run.setCheckpointAfter(request.checkpoint().trim());
        run.setStatus(KnowledgeSyncStatus.PROCESSING.name());
        run.setReceivedCount(request.documents().size());
        run.setStartedAt(LocalDateTime.now());
        try {
            runMapper.insert(run);
            return new StartResult(run, true);
        } catch (DuplicateKeyException e) {
            existing = find(source.getId(), request.requestId());
            if (existing == null) {
                throw e;
            }
            assertSameRequest(existing, requestHash);
            return new StartResult(existing, false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(Long runId, Long sourceId, Throwable error) {
        String status = error instanceof KnowledgeQualityGateException
            ? KnowledgeSyncStatus.QUALITY_FAILED.name() : KnowledgeSyncStatus.FAILED.name();
        String message = truncate(error.getMessage());
        LambdaUpdateWrapper<AiKnowledgeSyncRun> runUpdate = new LambdaUpdateWrapper<AiKnowledgeSyncRun>()
            .eq(AiKnowledgeSyncRun::getId, runId)
            .eq(AiKnowledgeSyncRun::getStatus, KnowledgeSyncStatus.PROCESSING.name())
            .set(AiKnowledgeSyncRun::getStatus, status)
            .set(AiKnowledgeSyncRun::getErrorMessage, message)
            .set(AiKnowledgeSyncRun::getFinishedAt, LocalDateTime.now());
        if (error instanceof KnowledgeQualityGateException quality) {
            runUpdate.set(AiKnowledgeSyncRun::getActiveDocumentCount, quality.getActiveDocumentCount())
                .set(AiKnowledgeSyncRun::getDuplicateContentCount, quality.getDuplicateContentCount())
                .set(AiKnowledgeSyncRun::getQualityScore, quality.getQualityScore())
                .set(AiKnowledgeSyncRun::getQualityStatus, KnowledgeQualityStatus.FAILED.name());
        }
        runMapper.update(null, runUpdate);

        sourceMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeSource>()
            .eq(AiKnowledgeSource::getId, sourceId)
            .set(AiKnowledgeSource::getLastSyncAt, LocalDateTime.now())
            .set(AiKnowledgeSource::getLastSyncStatus, status)
            .set(AiKnowledgeSource::getLastSyncError, message));
    }

    public KnowledgeSyncRunVO toVo(AiKnowledgeSyncRun run) {
        KnowledgeSyncRunVO vo = new KnowledgeSyncRunVO();
        vo.setId(run.getId());
        vo.setSourceId(run.getSourceId());
        vo.setRequestId(run.getRequestId());
        vo.setSyncMode(run.getSyncMode());
        vo.setCheckpointBefore(run.getCheckpointBefore());
        vo.setCheckpointAfter(run.getCheckpointAfter());
        vo.setStatus(run.getStatus());
        vo.setReceivedCount(run.getReceivedCount());
        vo.setUpsertedCount(run.getUpsertedCount());
        vo.setDeletedCount(run.getDeletedCount());
        vo.setUnchangedCount(run.getUnchangedCount());
        vo.setActiveDocumentCount(run.getActiveDocumentCount());
        vo.setDuplicateContentCount(run.getDuplicateContentCount());
        vo.setQualityScore(run.getQualityScore());
        vo.setQualityStatus(run.getQualityStatus());
        vo.setKnowledgeBaseVersionId(run.getKnowledgeBaseVersionId());
        vo.setSnapshotHash(run.getSnapshotHash());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setStartedAt(run.getStartedAt());
        vo.setFinishedAt(run.getFinishedAt());
        return vo;
    }

    private AiKnowledgeSyncRun find(Long sourceId, String requestId) {
        return runMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeSyncRun>()
            .eq(AiKnowledgeSyncRun::getSourceId, sourceId)
            .eq(AiKnowledgeSyncRun::getRequestId, requestId.trim()));
    }

    private void assertSameRequest(AiKnowledgeSyncRun existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "相同 requestId 不能提交不同的同步内容");
        }
    }

    private String requestHash(KnowledgeSyncRequest request) {
        return EvalFingerprint.of("knowledge-sync-request-v1", request.expectedCheckpoint(),
            request.checkpoint(), request.fullSnapshot(), request.expectedDocumentCount(), request.documents());
    }

    private String truncate(String raw) {
        String value = raw == null ? "unknown sync failure" : raw;
        return value.length() <= ERROR_MESSAGE_MAX_CHARS
            ? value : value.substring(0, ERROR_MESSAGE_MAX_CHARS);
    }

    public record StartResult(AiKnowledgeSyncRun run, boolean created) {
    }
}
