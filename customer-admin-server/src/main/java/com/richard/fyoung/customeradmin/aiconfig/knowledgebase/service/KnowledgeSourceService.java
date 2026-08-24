package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeFreshnessStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeQualityStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeSourceType;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeSyncStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentRevisionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSyncRunMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 文档源生命周期及其同步事实查询。 */
@Service
public class KnowledgeSourceService {

    private static final int DEFAULT_FRESHNESS_SLA_MINUTES = 1440;
    private static final BigDecimal DEFAULT_QUALITY_THRESHOLD = new BigDecimal("0.8000");
    private static final int MAX_SYNC_RUNS = 100;

    private final AiKnowledgeSourceMapper sourceMapper;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeSyncRunMapper runMapper;
    private final KnowledgeDocumentIndexer documentIndexer;
    private final KnowledgeSyncRunRecorder runRecorder;

    public KnowledgeSourceService(AiKnowledgeSourceMapper sourceMapper,
                                  AiKnowledgeBaseMapper knowledgeBaseMapper,
                                  AiKnowledgeDocumentMapper documentMapper,
                                  AiKnowledgeDocumentRevisionMapper revisionMapper,
                                  AiKnowledgeSyncRunMapper runMapper,
                                  KnowledgeDocumentIndexer documentIndexer,
                                  KnowledgeSyncRunRecorder runRecorder) {
        this.sourceMapper = sourceMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.runMapper = runMapper;
        this.documentIndexer = documentIndexer;
        this.runRecorder = runRecorder;
    }

    public List<KnowledgeSourceVO> list(Long knowledgeBaseId) {
        requireKnowledgeBase(knowledgeBaseId);
        return sourceMapper.selectList(new LambdaQueryWrapper<AiKnowledgeSource>()
                .eq(AiKnowledgeSource::getKnowledgeBaseId, knowledgeBaseId)
                .orderByAsc(AiKnowledgeSource::getId))
            .stream().map(this::toVo).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long knowledgeBaseId, KnowledgeSourceSaveRequest request) {
        requireKnowledgeBase(knowledgeBaseId);
        AiKnowledgeSource source = new AiKnowledgeSource();
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setSourceCode(normalizeCode(request.sourceCode()));
        source.setSourceName(request.sourceName().trim());
        source.setSourceType(sourceType(request.sourceType()).name());
        source.setStatus(normalizeStatus(request.status()));
        source.setFreshnessSlaMinutes(defaultIfNull(request.freshnessSlaMinutes(),
            DEFAULT_FRESHNESS_SLA_MINUTES));
        source.setQualityThreshold(defaultIfNull(request.qualityThreshold(),
            DEFAULT_QUALITY_THRESHOLD));
        source.setDefaultAclJson(documentIndexer.writeAcl(request.defaultAcl()));
        source.setActiveDocumentCount(0);
        source.setQualityStatus(KnowledgeQualityStatus.UNKNOWN.name());
        source.setRevision(1);
        try {
            sourceMapper.insert(source);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE,
                "同一知识库内 sourceCode 已存在: " + source.getSourceCode());
        }
        return source.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long knowledgeBaseId, Long sourceId, KnowledgeSourceSaveRequest request) {
        AiKnowledgeSource source = requireSource(knowledgeBaseId, sourceId);
        String requestedCode = normalizeCode(request.sourceCode());
        if (!source.getSourceCode().equals(requestedCode)) {
            throw new BizException(ResultCode.PARAM_INVALID, "sourceCode 是文档稳定身份，创建后不可修改");
        }
        KnowledgeSourceType requestedType = sourceType(request.sourceType());
        if (!source.getSourceType().equals(requestedType.name())) {
            throw new BizException(ResultCode.PARAM_INVALID, "sourceType 创建后不可修改");
        }
        source.setSourceName(request.sourceName().trim());
        source.setStatus(normalizeStatus(request.status()));
        source.setFreshnessSlaMinutes(defaultIfNull(request.freshnessSlaMinutes(),
            DEFAULT_FRESHNESS_SLA_MINUTES));
        source.setQualityThreshold(defaultIfNull(request.qualityThreshold(),
            DEFAULT_QUALITY_THRESHOLD));
        source.setDefaultAclJson(documentIndexer.writeAcl(request.defaultAcl()));
        source.setRevision(defaultIfNull(source.getRevision(), 0) + 1);
        sourceMapper.updateById(source);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long knowledgeBaseId, Long sourceId) {
        AiKnowledgeSource source = requireSource(knowledgeBaseId, sourceId);
        if (defaultIfNull(source.getActiveDocumentCount(), 0) > 0) {
            throw new BizException(ResultCode.RESOURCE_IN_USE,
                "文档源仍有有效文档，请先提交全量空快照删除文档");
        }
        sourceMapper.deleteById(sourceId);
    }

    public List<KnowledgeSyncRunVO> runs(Long knowledgeBaseId, Long sourceId) {
        requireSource(knowledgeBaseId, sourceId);
        return runMapper.selectList(new LambdaQueryWrapper<AiKnowledgeSyncRun>()
                .eq(AiKnowledgeSyncRun::getSourceId, sourceId)
                .orderByDesc(AiKnowledgeSyncRun::getId)
                .last("LIMIT " + MAX_SYNC_RUNS))
            .stream().map(runRecorder::toVo).toList();
    }

    public List<KnowledgeDocumentRevisionVO> lineage(Long knowledgeBaseId, Long sourceId,
                                                     String externalId) {
        requireSource(knowledgeBaseId, sourceId);
        AiKnowledgeDocument document = documentMapper.selectOne(
            new LambdaQueryWrapper<AiKnowledgeDocument>()
                .eq(AiKnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(AiKnowledgeDocument::getSourceId, sourceId)
                .eq(AiKnowledgeDocument::getExternalId, externalId));
        if (document == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "文档不存在: " + externalId);
        }
        return revisionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentRevision>()
                .eq(AiKnowledgeDocumentRevision::getDocumentId, document.getId())
                .orderByDesc(AiKnowledgeDocumentRevision::getId))
            .stream().map(revision -> toLineage(document.getExternalId(), revision)).toList();
    }

    public AiKnowledgeSource requireSource(Long knowledgeBaseId, Long sourceId) {
        AiKnowledgeSource source = sourceMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeSource>()
            .eq(AiKnowledgeSource::getId, sourceId)
            .eq(AiKnowledgeSource::getKnowledgeBaseId, knowledgeBaseId));
        if (source == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "文档源不存在: " + sourceId);
        }
        return source;
    }

    private KnowledgeSourceVO toVo(AiKnowledgeSource source) {
        KnowledgeSourceVO vo = new KnowledgeSourceVO();
        vo.setId(source.getId());
        vo.setKnowledgeBaseId(source.getKnowledgeBaseId());
        vo.setSourceCode(source.getSourceCode());
        vo.setSourceName(source.getSourceName());
        vo.setSourceType(source.getSourceType());
        vo.setStatus(source.getStatus());
        vo.setFreshnessSlaMinutes(source.getFreshnessSlaMinutes());
        vo.setQualityThreshold(source.getQualityThreshold());
        vo.setDefaultAcl(documentIndexer.parseAcl(source.getDefaultAclJson()));
        vo.setCurrentCheckpoint(source.getCurrentCheckpoint());
        vo.setLastSyncAt(source.getLastSyncAt());
        vo.setLastSuccessfulSyncAt(source.getLastSuccessfulSyncAt());
        vo.setLastSyncStatus(source.getLastSyncStatus());
        vo.setLastSyncError(source.getLastSyncError());
        vo.setActiveDocumentCount(defaultIfNull(source.getActiveDocumentCount(), 0));
        vo.setQualityScore(source.getQualityScore());
        vo.setQualityStatus(defaultIfNull(source.getQualityStatus(),
            KnowledgeQualityStatus.UNKNOWN.name()));
        LocalDateTime deadline = source.getLastSuccessfulSyncAt() == null ? null
            : source.getLastSuccessfulSyncAt().plusMinutes(source.getFreshnessSlaMinutes());
        vo.setFreshnessDeadline(deadline);
        vo.setFreshnessStatus(freshnessStatus(source, deadline).name());
        vo.setRevision(source.getRevision());
        vo.setCreateTime(source.getCreateTime());
        vo.setUpdateTime(source.getUpdateTime());
        return vo;
    }

    private KnowledgeFreshnessStatus freshnessStatus(AiKnowledgeSource source, LocalDateTime deadline) {
        if (source.getLastSuccessfulSyncAt() == null) {
            return KnowledgeFreshnessStatus.NEVER_SYNCED;
        }
        if (KnowledgeSyncStatus.FAILED.name().equals(source.getLastSyncStatus())
            || KnowledgeSyncStatus.QUALITY_FAILED.name().equals(source.getLastSyncStatus())) {
            return KnowledgeFreshnessStatus.FAILED;
        }
        return LocalDateTime.now().isAfter(deadline)
            ? KnowledgeFreshnessStatus.STALE : KnowledgeFreshnessStatus.FRESH;
    }

    private KnowledgeDocumentRevisionVO toLineage(String externalId,
                                                   AiKnowledgeDocumentRevision revision) {
        KnowledgeDocumentRevisionVO vo = new KnowledgeDocumentRevisionVO();
        vo.setId(revision.getId());
        vo.setParentRevisionId(revision.getParentRevisionId());
        vo.setExternalId(externalId);
        vo.setOperation(revision.getOperation());
        vo.setSourceVersion(revision.getSourceVersion());
        vo.setTitle(revision.getTitle());
        vo.setSourceUri(revision.getSourceUri());
        vo.setContentHash(revision.getContentHash());
        vo.setAclMode(revision.getAclMode());
        vo.setAllowedSubjectTypes(revision.getAllowedSubjectTypes());
        vo.setAllowedSubjectIds(revision.getAllowedSubjectIds());
        vo.setAllowedChannels(revision.getAllowedChannels());
        vo.setSourceUpdatedAt(revision.getSourceUpdatedAt());
        vo.setCreateTime(revision.getCreateTime());
        return vo;
    }

    private void requireKnowledgeBase(Long knowledgeBaseId) {
        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
    }

    private KnowledgeSourceType sourceType(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT)
            : KnowledgeSourceType.PUSH.name();
        try {
            return KnowledgeSourceType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "sourceType 目前仅支持 PUSH");
        }
    }

    private String normalizeCode(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private int normalizeStatus(Integer status) {
        int value = defaultIfNull(status, StatusFlags.ENABLED);
        if (value != StatusFlags.ENABLED && value != StatusFlags.DISABLED) {
            throw new BizException(ResultCode.PARAM_INVALID, "status 仅支持 0/1");
        }
        return value;
    }

    private <T> T defaultIfNull(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }
}
