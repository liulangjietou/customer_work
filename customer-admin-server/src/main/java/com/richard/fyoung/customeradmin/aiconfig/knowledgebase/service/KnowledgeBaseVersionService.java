package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeQualityStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVersionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** 知识库不可变版本唯一写入/读取入口。 */
@Service
public class KnowledgeBaseVersionService {

    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeBaseVersionMapper versionMapper;
    private final AiKnowledgeBaseVersionDocumentMapper versionDocumentMapper;

    public KnowledgeBaseVersionService(AiKnowledgeBaseMapper knowledgeBaseMapper,
                                       AiKnowledgeBaseVersionMapper versionMapper,
                                       AiKnowledgeBaseVersionDocumentMapper versionDocumentMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.versionMapper = versionMapper;
        this.versionDocumentMapper = versionDocumentMapper;
    }

    /** 配置变更创建下一版本，并复制上一版本的文档快照成员。 */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeBaseVersion createConfigurationVersion(Long knowledgeBaseId, String changeNote) {
        AiKnowledgeBase knowledgeBase = lockKnowledgeBase(knowledgeBaseId);
        AiKnowledgeBaseVersion previous = currentVersion(knowledgeBase);
        List<AiKnowledgeBaseVersionDocument> members = previous == null ? List.of()
            : versionDocumentMapper.selectList(new LambdaQueryWrapper<AiKnowledgeBaseVersionDocument>()
                .eq(AiKnowledgeBaseVersionDocument::getKnowledgeBaseVersionId, previous.getId()));
        return createVersion(knowledgeBase,
            previous == null ? null : previous.getCheckpoint(),
            previous == null ? BigDecimal.ONE : previous.getQualityScore(),
            previous == null ? KnowledgeQualityStatus.PASSED.name() : previous.getQualityStatus(),
            members, changeNote);
    }

    /** 文档同步成功后创建下一版本，成员来自当时全部未删除文档的 currentRevisionId。 */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeBaseVersion createDocumentSnapshotVersion(Long knowledgeBaseId,
                                                                 String checkpoint,
                                                                 BigDecimal qualityScore,
                                                                 String qualityStatus,
                                                                 List<AiKnowledgeDocument> activeDocuments,
                                                                 String changeNote) {
        AiKnowledgeBase knowledgeBase = lockKnowledgeBase(knowledgeBaseId);
        List<AiKnowledgeBaseVersionDocument> members = activeDocuments.stream()
            .filter(document -> document.getCurrentRevisionId() != null)
            .sorted(Comparator.comparing(AiKnowledgeDocument::getSourceId)
                .thenComparing(AiKnowledgeDocument::getExternalId))
            .map(this::member)
            .toList();
        return createVersion(knowledgeBase, checkpoint, qualityScore, qualityStatus, members, changeNote);
    }

    public AiKnowledgeBaseVersion requireVersion(Long knowledgeBaseId, Long versionId) {
        AiKnowledgeBaseVersion version = versionMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeBaseVersion>()
            .eq(AiKnowledgeBaseVersion::getId, versionId)
            .eq(AiKnowledgeBaseVersion::getKnowledgeBaseId, knowledgeBaseId));
        if (version == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "知识库版本不存在: knowledgeBaseId=" + knowledgeBaseId + ", versionId=" + versionId);
        }
        return version;
    }

    public List<KnowledgeBaseVersionVO> versions(Long knowledgeBaseId) {
        if (knowledgeBaseMapper.selectById(knowledgeBaseId) == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
        return versionMapper.selectList(new LambdaQueryWrapper<AiKnowledgeBaseVersion>()
                .eq(AiKnowledgeBaseVersion::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(AiKnowledgeBaseVersion::getVersionNo))
            .stream().map(this::toVo).toList();
    }

    private AiKnowledgeBaseVersion createVersion(AiKnowledgeBase knowledgeBase,
                                                 String checkpoint,
                                                 BigDecimal qualityScore,
                                                 String qualityStatus,
                                                 List<AiKnowledgeBaseVersionDocument> sourceMembers,
                                                 String changeNote) {
        int versionNo = knowledgeBase.getLatestVersionNo() == null
            ? 1 : knowledgeBase.getLatestVersionNo() + 1;
        String memberManifest = sourceMembers.stream()
            .sorted(Comparator.comparing(AiKnowledgeBaseVersionDocument::getSourceId)
                .thenComparing(AiKnowledgeBaseVersionDocument::getExternalId))
            .map(member -> member.getSourceId() + "|" + member.getExternalId() + "|"
                + member.getDocumentRevisionId())
            .reduce("", (left, right) -> left + right + "\n");

        AiKnowledgeBaseVersion version = new AiKnowledgeBaseVersion();
        version.setKnowledgeBaseId(knowledgeBase.getId());
        version.setVersionNo(versionNo);
        version.setBaseUrl(knowledgeBase.getBaseUrl());
        version.setAppId(knowledgeBase.getAppId());
        version.setApiKey(knowledgeBase.getApiKey());
        version.setContentType(knowledgeBase.getContentType());
        version.setExtraHeaders(knowledgeBase.getExtraHeaders());
        version.setTopN(knowledgeBase.getTopN());
        version.setScoreThreshold(knowledgeBase.getScoreThreshold());
        version.setCheckpoint(checkpoint);
        version.setDocumentCount(sourceMembers.size());
        version.setQualityScore(qualityScore == null ? BigDecimal.ONE : qualityScore);
        version.setQualityStatus(qualityStatus == null ? KnowledgeQualityStatus.PASSED.name() : qualityStatus);
        version.setSnapshotHash(EvalFingerprint.of("knowledge-base-version-v1",
            knowledgeBase.getId(), versionNo, knowledgeBase.getBaseUrl(), knowledgeBase.getAppId(),
            knowledgeBase.getApiKey(), knowledgeBase.getContentType(), knowledgeBase.getExtraHeaders(),
            knowledgeBase.getTopN(), knowledgeBase.getScoreThreshold(), checkpoint, memberManifest));
        version.setChangeNote(changeNote);
        versionMapper.insert(version);

        for (AiKnowledgeBaseVersionDocument source : sourceMembers) {
            AiKnowledgeBaseVersionDocument frozen = new AiKnowledgeBaseVersionDocument();
            frozen.setKnowledgeBaseVersionId(version.getId());
            frozen.setDocumentRevisionId(source.getDocumentRevisionId());
            frozen.setSourceId(source.getSourceId());
            frozen.setExternalId(source.getExternalId());
            versionDocumentMapper.insert(frozen);
        }
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<AiKnowledgeBase>()
            .eq(AiKnowledgeBase::getId, knowledgeBase.getId())
            .set(AiKnowledgeBase::getCurrentVersionId, version.getId())
            .set(AiKnowledgeBase::getLatestVersionNo, versionNo));
        return version;
    }

    private AiKnowledgeBaseVersionDocument member(AiKnowledgeDocument document) {
        AiKnowledgeBaseVersionDocument member = new AiKnowledgeBaseVersionDocument();
        member.setDocumentRevisionId(document.getCurrentRevisionId());
        member.setSourceId(document.getSourceId());
        member.setExternalId(document.getExternalId());
        return member;
    }

    private AiKnowledgeBase lockKnowledgeBase(Long knowledgeBaseId) {
        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new QueryWrapper<AiKnowledgeBase>()
            .eq("id", knowledgeBaseId).last("FOR UPDATE"));
        if (knowledgeBase == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
        return knowledgeBase;
    }

    private AiKnowledgeBaseVersion currentVersion(AiKnowledgeBase knowledgeBase) {
        return knowledgeBase.getCurrentVersionId() == null ? null
            : versionMapper.selectById(knowledgeBase.getCurrentVersionId());
    }

    private KnowledgeBaseVersionVO toVo(AiKnowledgeBaseVersion version) {
        KnowledgeBaseVersionVO vo = new KnowledgeBaseVersionVO();
        vo.setId(version.getId());
        vo.setVersionNo(version.getVersionNo());
        vo.setCheckpoint(version.getCheckpoint());
        vo.setSnapshotHash(version.getSnapshotHash());
        vo.setDocumentCount(version.getDocumentCount());
        vo.setQualityScore(version.getQualityScore());
        vo.setQualityStatus(version.getQualityStatus());
        vo.setChangeNote(version.getChangeNote());
        vo.setCreateTime(version.getCreateTime());
        return vo;
    }
}
