package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentAccessPolicy;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentOperation;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgePreviewStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeVersionDocumentVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 编排版本成员、当前文档与两次 ACL 核验，防止历史公开修订绕过当前撤回或收紧的权限。 */
@Service
@Transactional(readOnly = true)
public class KnowledgeVersionPreviewService {

    private static final int DOCUMENTS_PER_PAGE = 20;
    private static final int DOCUMENT_ACTIVE = 0;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeBaseVersionMapper versionMapper;
    private final AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeSourceMapper sourceMapper;
    private final KnowledgeDocumentAccessPolicy accessPolicy;

    public KnowledgeVersionPreviewService(AiKnowledgeBaseMapper knowledgeBaseMapper,
                                          AiKnowledgeBaseVersionMapper versionMapper,
                                          AiKnowledgeBaseVersionDocumentMapper memberMapper,
                                          AiKnowledgeDocumentRevisionMapper revisionMapper,
                                          AiKnowledgeDocumentMapper documentMapper,
                                          AiKnowledgeSourceMapper sourceMapper,
                                          ObjectMapper objectMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.versionMapper = versionMapper;
        this.memberMapper = memberMapper;
        this.revisionMapper = revisionMapper;
        this.documentMapper = documentMapper;
        this.sourceMapper = sourceMapper;
        this.accessPolicy = new KnowledgeDocumentAccessPolicy(objectMapper);
    }

    /** 固定每页 20 条；不可读成员只呈现状态，不借列表接口泄露文档信息。 */
    public PageResult<KnowledgeVersionDocumentVO> documents(Long knowledgeBaseId, Long versionId,
                                                          int pageNum,
                                                          AgentInvocationIdentity identity) {
        AiKnowledgeBaseVersion version = requireVersion(knowledgeBaseId, versionId, identity);
        Page<AiKnowledgeBaseVersionDocument> members = memberMapper.selectPage(
            new Page<>(pageNum, DOCUMENTS_PER_PAGE), memberQuery(version).orderByAsc(
                AiKnowledgeBaseVersionDocument::getId));
        Page<KnowledgeVersionDocumentVO> page = new Page<>(members.getCurrent(), members.getSize(),
            members.getTotal());
        PreviewData data = load(members.getRecords());
        page.setRecords(members.getRecords().stream().map(member -> {
            try {
                return metadata(version, resolve(version, member, identity, data));
            } catch (BizException e) {
                KnowledgePreviewStatus status;
                if (e.getResultCode() == ResultCode.FORBIDDEN) {
                    status = KnowledgePreviewStatus.FORBIDDEN;
                } else if (e.getResultCode() == ResultCode.RESOURCE_NOT_FOUND) {
                    status = KnowledgePreviewStatus.UNAVAILABLE;
                } else {
                    throw e;
                }
                return new KnowledgeVersionDocumentVO(knowledgeBaseId, versionId,
                    version.getVersionNo(), member.getDocumentRevisionId(), status, null, null,
                    null, null, null, null, false, null, null);
            }
        }).toList());
        return PageResult.of(page);
    }

    /** 每次打开都重新授权，版本、成员和文档的关系不能由浏览器提供的标识替代。 */
    public KnowledgeDocumentPreviewVO preview(Long knowledgeBaseId, Long versionId, Long revisionId,
                                             AgentInvocationIdentity identity) {
        AiKnowledgeBaseVersion version = requireVersion(knowledgeBaseId, versionId, identity);
        AiKnowledgeBaseVersionDocument member = memberMapper.selectOne(memberQuery(version)
            .eq(AiKnowledgeBaseVersionDocument::getDocumentRevisionId, revisionId));
        if (member == null || !Objects.equals(member.getDocumentRevisionId(), revisionId)) {
            throw unavailable();
        }
        ResolvedDocument resolved = resolve(version, member, identity, load(List.of(member)));
        return new KnowledgeDocumentPreviewVO(metadata(version, resolved),
            resolved.revision().getContent());
    }

    private AiKnowledgeBaseVersion requireVersion(Long knowledgeBaseId, Long versionId,
                                                  AgentInvocationIdentity identity) {
        String tenant = TenantContext.require();
        if (identity == null || !TenantContext.sameTenant(tenant, identity.tenantId())
            || identity.subjectType() != QuotaSubjectType.ADMIN_USER) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new QueryWrapper<AiKnowledgeBase>()
            .eq("id", knowledgeBaseId).eq("tenant_id", tenant));
        if (knowledgeBase == null) {
            throw unavailable();
        }
        AiKnowledgeBaseVersion version = versionMapper.selectById(versionId);
        if (version == null || !TenantContext.sameTenant(tenant, version.getTenantId())
            || !Objects.equals(version.getKnowledgeBaseId(), knowledgeBaseId)) {
            throw unavailable();
        }
        return version;
    }

    private LambdaQueryWrapper<AiKnowledgeBaseVersionDocument> memberQuery(
        AiKnowledgeBaseVersion version) {
        return new LambdaQueryWrapper<AiKnowledgeBaseVersionDocument>()
            .eq(AiKnowledgeBaseVersionDocument::getTenantId, version.getTenantId())
            .eq(AiKnowledgeBaseVersionDocument::getKnowledgeBaseVersionId, version.getId());
    }

    private ResolvedDocument resolve(AiKnowledgeBaseVersion version,
                                     AiKnowledgeBaseVersionDocument member,
                                     AgentInvocationIdentity identity, PreviewData data) {
        String tenant = version.getTenantId();
        if (!TenantContext.sameTenant(tenant, member.getTenantId())
            || !Objects.equals(version.getId(), member.getKnowledgeBaseVersionId())) {
            throw unavailable();
        }
        AiKnowledgeDocumentRevision revision = data.revisions().get(member.getDocumentRevisionId());
        if (revision == null || !TenantContext.sameTenant(tenant, revision.getTenantId())
            || !Objects.equals(revision.getSourceId(), member.getSourceId())
            || !KnowledgeDocumentOperation.UPSERT.name().equals(revision.getOperation())) {
            throw unavailable();
        }
        AiKnowledgeDocument document = data.documents().get(revision.getDocumentId());
        if (document == null || !TenantContext.sameTenant(tenant, document.getTenantId())
            || !Objects.equals(document.getKnowledgeBaseId(), version.getKnowledgeBaseId())
            || !Objects.equals(document.getSourceId(), member.getSourceId())
            || !Objects.equals(document.getExternalId(), member.getExternalId())
            || !Integer.valueOf(DOCUMENT_ACTIVE).equals(document.getDeleted())) {
            throw unavailable();
        }
        AiKnowledgeSource source = data.sources().get(member.getSourceId());
        if (source == null || !TenantContext.sameTenant(tenant, source.getTenantId())
            || !Objects.equals(source.getKnowledgeBaseId(), version.getKnowledgeBaseId())
            || !Integer.valueOf(StatusFlags.ENABLED).equals(source.getStatus())) {
            throw unavailable();
        }
        boolean current = Objects.equals(document.getCurrentRevisionId(), revision.getId());
        AiKnowledgeDocumentRevision currentRevision = current ? revision
            : data.revisions().get(document.getCurrentRevisionId());
        if (currentRevision == null || !TenantContext.sameTenant(tenant, currentRevision.getTenantId())
            || !Objects.equals(currentRevision.getDocumentId(), document.getId())
            || !Objects.equals(currentRevision.getSourceId(), source.getId())
            || !KnowledgeDocumentOperation.UPSERT.name().equals(currentRevision.getOperation())) {
            throw unavailable();
        }
        if (!accessPolicy.allowed(revision, identity) || !accessPolicy.allowed(currentRevision, identity)) {
            throw new BizException(ResultCode.FORBIDDEN, "当前账号无权预览此文档原文");
        }
        if (revision.getContent() == null) {
            throw unavailable();
        }
        return new ResolvedDocument(revision, document, source, current);
    }

    /** 分页成员批量加载关联记录，20 条文档也只需固定次数查询。 */
    private PreviewData load(List<AiKnowledgeBaseVersionDocument> members) {
        if (members.isEmpty()) {
            return new PreviewData(Map.of(), Map.of(), Map.of());
        }
        List<Long> revisionIds = members.stream()
            .map(AiKnowledgeBaseVersionDocument::getDocumentRevisionId).distinct().toList();
        Map<Long, AiKnowledgeDocumentRevision> revisions = new HashMap<>(revisionMapper.selectBatchIds(revisionIds)
            .stream().collect(Collectors.toMap(AiKnowledgeDocumentRevision::getId, Function.identity())));
        List<Long> documentIds = revisions.values().stream()
            .map(AiKnowledgeDocumentRevision::getDocumentId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeDocument> documents = documentIds.isEmpty() ? Map.of()
            : documentMapper.selectBatchIds(documentIds).stream()
                .collect(Collectors.toMap(AiKnowledgeDocument::getId, Function.identity()));
        List<Long> currentIds = documents.values().stream().map(AiKnowledgeDocument::getCurrentRevisionId)
            .filter(Objects::nonNull).filter(id -> !revisions.containsKey(id)).distinct().toList();
        if (!currentIds.isEmpty()) {
            revisionMapper.selectBatchIds(currentIds).forEach(revision ->
                revisions.put(revision.getId(), revision));
        }
        List<Long> sourceIds = members.stream().map(AiKnowledgeBaseVersionDocument::getSourceId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeSource> sources = sourceIds.isEmpty() ? Map.of()
            : sourceMapper.selectBatchIds(sourceIds).stream()
                .collect(Collectors.toMap(AiKnowledgeSource::getId, Function.identity()));
        return new PreviewData(revisions, documents, sources);
    }

    private KnowledgeVersionDocumentVO metadata(AiKnowledgeBaseVersion version,
                                                 ResolvedDocument resolved) {
        AiKnowledgeDocumentRevision revision = resolved.revision();
        return new KnowledgeVersionDocumentVO(version.getKnowledgeBaseId(), version.getId(),
            version.getVersionNo(), revision.getId(), KnowledgePreviewStatus.AVAILABLE,
            revision.getTitle(), resolved.document().getExternalId(), resolved.source().getSourceName(),
            revision.getSourceVersion(), revision.getSourceUri(), revision.getContentHash(),
            resolved.current(), revision.getSourceUpdatedAt(), revision.getCreateTime());
    }

    private BizException unavailable() {
        return new BizException(ResultCode.RESOURCE_NOT_FOUND, "该版本来源已不可用，请重新核对版本记录");
    }

    private record PreviewData(Map<Long, AiKnowledgeDocumentRevision> revisions,
                               Map<Long, AiKnowledgeDocument> documents,
                               Map<Long, AiKnowledgeSource> sources) {
    }

    private record ResolvedDocument(AiKnowledgeDocumentRevision revision, AiKnowledgeDocument document,
                                    AiKnowledgeSource source, boolean current) {
    }
}
