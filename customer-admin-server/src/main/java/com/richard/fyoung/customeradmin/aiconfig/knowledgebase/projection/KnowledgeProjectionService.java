package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeAclMode;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeDocumentOperation;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.data.knowledge.KnowledgeProjectionStatus;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorCodec;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin 向客服库单向投影不可变版本；starter 不反向读取 Admin 数据库。
 *
 * <p>先持有 Admin KB 行锁并封锁目标版本，在同一锁内核对历史与当前权限、保留分片主键写入、
 * 删除多余分片，最后恢复该版本 READY。任何中途失败都保留 BLOCKED，不能暴露半完成集合。</p>
 */
@Service
public class KnowledgeProjectionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeProjectionService.class);
    private static final String PRIVATE_ACL = "PRIVATE";
    private static final int DOCUMENT_ACTIVE = 0;
    private final KnowledgeProjectionGatewayProvider gatewayProvider;
    private final KnowledgeProjectionAccessGuard accessGuard;
    private final AiKnowledgeBaseVersionMapper versionMapper;
    private final AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeDocumentChunkMapper chunkMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeSourceMapper sourceMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeProjectionService(KnowledgeProjectionGatewayProvider gatewayProvider,
                                      KnowledgeProjectionAccessGuard accessGuard,
                                      AiKnowledgeBaseVersionMapper versionMapper,
                                      AiKnowledgeBaseVersionDocumentMapper memberMapper,
                                      AiKnowledgeDocumentRevisionMapper revisionMapper,
                                      AiKnowledgeDocumentChunkMapper chunkMapper,
                                      AiKnowledgeDocumentMapper documentMapper,
                                      AiKnowledgeSourceMapper sourceMapper,
                                      ObjectMapper objectMapper) {
        this.gatewayProvider = gatewayProvider;
        this.accessGuard = accessGuard;
        this.versionMapper = versionMapper;
        this.memberMapper = memberMapper;
        this.revisionMapper = revisionMapper;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.sourceMapper = sourceMapper;
        this.objectMapper = objectMapper;
    }

    /** 把指定版本完整投影到客服端库；仅 PUBLIC 的历史与当前修订交集可供客户检索与预览。 */
    @Transactional(rollbackFor = Exception.class)
    public int project(Long knowledgeBaseId, Long versionId) {
        // 必须先做锁定当前读；锁之前读取文档会在 REPEATABLE READ 下留下旧权限快照。
        AiKnowledgeBase knowledgeBase = accessGuard.lockActive(knowledgeBaseId);
        String tenant = TenantContext.require();
        AiKnowledgeBaseVersion version = versionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getKnowledgeBaseId(), knowledgeBaseId)
            || !TenantContext.sameTenant(tenant, version.getTenantId())) {
            throw new IllegalArgumentException("知识库版本不存在：kbId=" + knowledgeBaseId + " versionId=" + versionId);
        }
        KnowledgeProjectionGateway gateway = gatewayProvider.get();
        long now = System.currentTimeMillis();
        gateway.versionMapper().blockByVersion(versionId, now);
        ProjectionData data = load(versionId);
        List<Long> retainedIds = new ArrayList<>();
        int skippedNoVector = 0;
        Integer dimensions = null;
        for (AiKnowledgeDocumentChunk source : data.chunks()) {
            float[] vector = parseVector(source);
            if (vector.length == 0) {
                skippedNoVector++;
                continue;
            }
            if (dimensions == null) dimensions = vector.length;
            AiKnowledgeBaseVersionDocument member = data.members().get(source.getDocumentRevisionId());
            AiKnowledgeDocumentRevision revision = data.revisions().get(source.getDocumentRevisionId());
            KnowledgeChunkDO target = new KnowledgeChunkDO();
            target.setKbVersionId(versionId);
            target.setDocRevisionId(source.getDocumentRevisionId());
            target.setChunkIndex(source.getChunkIndex());
            target.setContent(source.getContent());
            target.setEmbedding(VectorCodec.encode(vector));
            target.setDimensions(vector.length);
            target.setAclMode(publiclyReadable(knowledgeBase, version, member, revision, source, data)
                ? KnowledgeAclMode.PUBLIC.name() : PRIVATE_ACL);
            target.setExternalId(member == null ? null : member.getExternalId());
            target.setDocumentTitle(revision == null ? null : revision.getTitle());
            target.setSourceVersion(revision == null ? null : revision.getSourceVersion());
            target.setCreatedAtMs(now);
            target.setUpdatedAtMs(now);
            gateway.chunkMapper().upsertProjection(target);
            if (target.getId() == null || target.getId() <= 0) {
                throw new IllegalStateException("knowledge projection did not return a stable chunk id");
            }
            retainedIds.add(target.getId());
        }
        gateway.chunkMapper().deleteVersionChunksExcept(versionId, retainedIds);
        upsertVersion(gateway, knowledgeBase, version, dimensions, retainedIds.size(), now);
        if (skippedNoVector > 0) {
            log.error("knowledge projection skipped chunks without vector, errorCode={}, versionId={}, skipped={}",
                "KB-PROJECTION-NO-VECTOR", versionId, skippedNoVector);
        }
        log.info("knowledge projection done: kbId={} versionId={} chunks={} dimensions={}",
            knowledgeBaseId, versionId, retainedIds.size(), dimensions);
        return retainedIds.size();
    }

    /** READY 必须是最后一笔客服库写入，且仍持有统一的 Admin KB 行锁。 */
    private void upsertVersion(KnowledgeProjectionGateway gateway, AiKnowledgeBase knowledgeBase,
                               AiKnowledgeBaseVersion version, Integer dimensions, int chunkCount, long now) {
        KnowledgeVersionDO existing = gateway.versionMapper().selectOne(
            new QueryWrapper<KnowledgeVersionDO>().eq("kb_version_id", version.getId()));
        KnowledgeVersionDO row = existing == null ? new KnowledgeVersionDO() : existing;
        row.setKbVersionId(version.getId());
        row.setKbCode(String.valueOf(knowledgeBase.getId()));
        row.setKbName(knowledgeBase.getKbName());
        row.setVersionNo(version.getVersionNo());
        row.setTopN(version.getTopN() == null || version.getTopN() <= 0 ? 3 : version.getTopN());
        row.setScoreThreshold(version.getScoreThreshold() == null ? BigDecimal.ZERO : version.getScoreThreshold());
        row.setDimensions(dimensions == null ? 0 : dimensions);
        row.setChunkCount(chunkCount);
        row.setSyncedAtMs(now);
        row.setUpdatedAtMs(now);
        row.setAccessStatus(KnowledgeProjectionStatus.READY);
        if (existing == null) {
            row.setCreatedAtMs(now);
            gateway.versionMapper().insert(row);
        } else {
            gateway.versionMapper().updateById(row);
        }
    }

    /** 批量加载权限事实，避免每个分片都发起跨表查询。 */
    private ProjectionData load(Long versionId) {
        List<AiKnowledgeBaseVersionDocument> members = memberMapper.selectList(
            new QueryWrapper<AiKnowledgeBaseVersionDocument>().eq("knowledge_base_version_id", versionId));
        if (members.isEmpty()) return new ProjectionData(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        Map<Long, AiKnowledgeBaseVersionDocument> byRevision = members.stream().collect(Collectors.toMap(
            AiKnowledgeBaseVersionDocument::getDocumentRevisionId, Function.identity()));
        Map<Long, AiKnowledgeDocumentRevision> revisions = new HashMap<>(revisionMapper.selectBatchIds(byRevision.keySet())
            .stream().collect(Collectors.toMap(AiKnowledgeDocumentRevision::getId, Function.identity())));
        List<Long> documentIds = revisions.values().stream().map(AiKnowledgeDocumentRevision::getDocumentId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeDocument> documents = documentIds.isEmpty() ? Map.of()
            : documentMapper.selectBatchIds(documentIds).stream()
                .collect(Collectors.toMap(AiKnowledgeDocument::getId, Function.identity()));
        List<Long> currentRevisionIds = documents.values().stream().map(AiKnowledgeDocument::getCurrentRevisionId)
            .filter(Objects::nonNull).filter(id -> !revisions.containsKey(id)).distinct().toList();
        if (!currentRevisionIds.isEmpty()) {
            revisionMapper.selectBatchIds(currentRevisionIds).forEach(revision -> revisions.put(revision.getId(), revision));
        }
        List<Long> sourceIds = members.stream().map(AiKnowledgeBaseVersionDocument::getSourceId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiKnowledgeSource> sources = sourceIds.isEmpty() ? Map.of()
            : sourceMapper.selectBatchIds(sourceIds).stream().collect(Collectors.toMap(AiKnowledgeSource::getId, Function.identity()));
        List<AiKnowledgeDocumentChunk> chunks = chunkMapper.selectList(new QueryWrapper<AiKnowledgeDocumentChunk>()
            .in("document_revision_id", byRevision.keySet()).orderByAsc("document_revision_id", "chunk_index"));
        return new ProjectionData(chunks, byRevision, revisions, documents, sources);
    }

    private boolean publiclyReadable(AiKnowledgeBase base, AiKnowledgeBaseVersion version,
                                      AiKnowledgeBaseVersionDocument member, AiKnowledgeDocumentRevision historical,
                                      AiKnowledgeDocumentChunk chunk, ProjectionData data) {
        String tenant = version.getTenantId();
        if (!Integer.valueOf(StatusFlags.ENABLED).equals(base.getStatus()) || member == null || historical == null
            || !TenantContext.sameTenant(tenant, member.getTenantId())
            || !TenantContext.sameTenant(tenant, chunk.getTenantId())
            || !Objects.equals(version.getId(), member.getKnowledgeBaseVersionId())
            || !Objects.equals(member.getDocumentRevisionId(), historical.getId())
            || !Objects.equals(member.getSourceId(), historical.getSourceId())
            || !publicRevision(historical, tenant)) return false;
        AiKnowledgeDocument document = data.documents().get(historical.getDocumentId());
        AiKnowledgeSource source = data.sources().get(member.getSourceId());
        if (document == null || source == null || !TenantContext.sameTenant(tenant, document.getTenantId())
            || !TenantContext.sameTenant(tenant, source.getTenantId())
            || !Objects.equals(document.getKnowledgeBaseId(), base.getId())
            || !Objects.equals(source.getKnowledgeBaseId(), base.getId())
            || !Objects.equals(document.getSourceId(), member.getSourceId())
            || !Objects.equals(document.getExternalId(), member.getExternalId())
            || !Integer.valueOf(DOCUMENT_ACTIVE).equals(document.getDeleted())
            || !Integer.valueOf(DOCUMENT_ACTIVE).equals(source.getDeleted())
            || !Integer.valueOf(StatusFlags.ENABLED).equals(source.getStatus())) return false;
        AiKnowledgeDocumentRevision current = data.revisions().get(document.getCurrentRevisionId());
        return publicRevision(current, tenant) && Objects.equals(current.getDocumentId(), document.getId())
            && Objects.equals(current.getSourceId(), source.getId());
    }

    private boolean publicRevision(AiKnowledgeDocumentRevision revision, String tenant) {
        return revision != null && TenantContext.sameTenant(tenant, revision.getTenantId())
            && KnowledgeDocumentOperation.UPSERT.name().equals(revision.getOperation())
            && KnowledgeAclMode.PUBLIC.name().equals(revision.getAclMode()) && revision.getContent() != null;
    }

    private record ProjectionData(List<AiKnowledgeDocumentChunk> chunks,
                                  Map<Long, AiKnowledgeBaseVersionDocument> members,
                                  Map<Long, AiKnowledgeDocumentRevision> revisions,
                                  Map<Long, AiKnowledgeDocument> documents,
                                  Map<Long, AiKnowledgeSource> sources) { }

    /**
     * 后台以 JSON 文本存向量（历史格式），这里转成定长 float32。
     *
     * <p>解析失败返回空数组由调用方跳过并计数——单条坏数据不该让整次投影失败，
     * 但必须以错误码留痕，否则"知识库少了几条"没人能查出原因。</p>
     */
    private float[] parseVector(AiKnowledgeDocumentChunk chunk) {
        String raw = chunk.getEmbedding();
        if (raw == null || raw.isBlank()) {
            return new float[0];
        }
        try {
            return objectMapper.readValue(raw, float[].class);
        } catch (Exception e) {
            log.error("knowledge projection vector parse failed, errorCode={}, chunkId={}",
                "KB-PROJECTION-VECTOR-PARSE-FAIL", chunk.getId(), e);
            return new float[0];
        }
    }
}
