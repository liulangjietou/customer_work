package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentOperation;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeSourceType;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeAclRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** PUSH 文档源同步用例：输入校验、幂等运行编排与事务提交。 */
@Service
public class KnowledgeSourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceSyncService.class);
    private static final int MAX_DOCUMENTS_PER_SYNC = 1000;
    private static final String SYNC_FAILURE_CODE = "KB-SOURCE-SYNC-FAILED";

    private final KnowledgeSourceService sourceService;
    private final KnowledgeDocumentIndexer documentIndexer;
    private final KnowledgeSyncRunRecorder runRecorder;
    private final KnowledgeSourceSyncCoordinator coordinator;

    public KnowledgeSourceSyncService(KnowledgeSourceService sourceService,
                                      KnowledgeDocumentIndexer documentIndexer,
                                      KnowledgeSyncRunRecorder runRecorder,
                                      KnowledgeSourceSyncCoordinator coordinator) {
        this.sourceService = sourceService;
        this.documentIndexer = documentIndexer;
        this.runRecorder = runRecorder;
        this.coordinator = coordinator;
    }

    public KnowledgeSyncRunVO sync(Long knowledgeBaseId, Long sourceId, KnowledgeSyncRequest request) {
        validateRequest(request);
        AiKnowledgeSource source = sourceService.requireSource(knowledgeBaseId, sourceId);
        if (!KnowledgeSourceType.PUSH.name().equals(source.getSourceType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "该文档源不支持 PUSH 同步");
        }
        if (!Integer.valueOf(StatusFlags.ENABLED).equals(source.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "文档源已停用: " + sourceId);
        }
        KnowledgeSyncRunRecorder.StartResult start = runRecorder.start(source, request);
        if (!start.created()) {
            return runRecorder.toVo(start.run());
        }
        try {
            KnowledgeAclRequest defaultAcl = documentIndexer.parseAcl(source.getDefaultAclJson());
            Map<String, KnowledgeDocumentIndexer.PreparedDocument> prepared = prepare(request, defaultAcl);
            AiKnowledgeSyncRun completed = coordinator.commit(sourceId, start.run().getId(), request, prepared);
            return runRecorder.toVo(completed);
        } catch (Exception e) {
            runRecorder.fail(start.run().getId(), sourceId, e);
            log.error("knowledge source sync failed, errorCode={}, sourceId={}, requestId={}",
                SYNC_FAILURE_CODE, sourceId, request.requestId(), e);
            if (e instanceof KnowledgeQualityGateException) {
                throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
            }
            throw e;
        }
    }

    private Map<String, KnowledgeDocumentIndexer.PreparedDocument> prepare(
        KnowledgeSyncRequest request, KnowledgeAclRequest defaultAcl) {
        Map<String, KnowledgeDocumentIndexer.PreparedDocument> prepared = new HashMap<>();
        for (KnowledgeDocumentChangeRequest change : request.documents()) {
            if (operation(change) == KnowledgeDocumentOperation.UPSERT) {
                prepared.put(change.externalId(), documentIndexer.prepare(change, defaultAcl));
            }
        }
        return Map.copyOf(prepared);
    }

    private void validateRequest(KnowledgeSyncRequest request) {
        if (request.documents().size() > MAX_DOCUMENTS_PER_SYNC) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "单次同步文档数量不能超过 " + MAX_DOCUMENTS_PER_SYNC);
        }
        if (normalize(request.checkpoint()).equals(normalize(request.expectedCheckpoint()))) {
            throw new BizException(ResultCode.PARAM_INVALID, "checkpoint 必须推进，不能与 expectedCheckpoint 相同");
        }
        Set<String> externalIds = new HashSet<>();
        for (KnowledgeDocumentChangeRequest change : request.documents()) {
            operation(change);
            String externalId = change.externalId().trim();
            if (!externalIds.add(externalId)) {
                throw new BizException(ResultCode.PARAM_INVALID,
                    "同一同步批次 externalId 不能重复: " + externalId);
            }
        }
        if (Boolean.TRUE.equals(request.fullSnapshot())
            && request.documents().stream().anyMatch(change -> operation(change) == KnowledgeDocumentOperation.DELETE)) {
            throw new BizException(ResultCode.PARAM_INVALID, "全量快照只接受 UPSERT，缺失文档会自动删除");
        }
    }

    private KnowledgeDocumentOperation operation(KnowledgeDocumentChangeRequest change) {
        try {
            return KnowledgeDocumentOperation.valueOf(change.operation().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "operation 仅支持 UPSERT/DELETE");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
