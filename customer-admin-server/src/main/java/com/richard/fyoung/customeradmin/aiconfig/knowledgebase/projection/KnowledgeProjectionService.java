package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把后台的知识库版本投影到客服端库，供 C 端检索。
 *
 * <p><b>为什么需要这一步</b>：运行时要读的表放客服端库是本仓库既有的落位规则，
 * 而知识资产的编辑、审核、版本与 lineage 都在后台。两边各管一段，中间靠这次投影衔接——
 * 后台写、客服端读，方向单向。让 starter 反向读后台库是不成立的方向，
 * 那正是这套知识栈此前割裂的根因。</p>
 *
 * <p><b>整版替换而不是增量合并</b>：知识库版本本身是不可变快照，一个版本投影出来就该
 * 与后台那一版逐字一致。增量合并要处理删除、改名、重排，任何一处漏掉都会让客服端
 * 停留在半新半旧的状态——而这种不一致查起来极难，因为两边看各自都"正常"。
 * 先清场再整批写入，代价是投影期间该版本短暂查不到，收益是终态确定。</p>
 *
 * <p><b>向量在这里完成格式转换</b>：后台以 JSON 文本存（历史格式），客服端存定长 float32。
 * 转换放在投影这一步而不是检索时——检索每次都要转的话，这次优化就白做了。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class KnowledgeProjectionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProjectionService.class);

    /** 批量写入的分片数：够摊薄往返开销，又不至于让单条 SQL 过大。 */
    private static final int WRITE_BATCH_SIZE = 200;

    private final KnowledgeProjectionGatewayProvider gatewayProvider;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiKnowledgeBaseVersionMapper versionMapper;
    private final AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeDocumentChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeProjectionService(KnowledgeProjectionGatewayProvider gatewayProvider,
                                      AiKnowledgeBaseMapper knowledgeBaseMapper,
                                      AiKnowledgeBaseVersionMapper versionMapper,
                                      AiKnowledgeBaseVersionDocumentMapper memberMapper,
                                      AiKnowledgeDocumentRevisionMapper revisionMapper,
                                      AiKnowledgeDocumentChunkMapper chunkMapper,
                                      ObjectMapper objectMapper) {
        this.gatewayProvider = gatewayProvider;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.versionMapper = versionMapper;
        this.memberMapper = memberMapper;
        this.revisionMapper = revisionMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 把一个知识库版本投影到客服端库。
     *
     * @return 实际写入的分片数
     */
    public int project(Long knowledgeBaseId, Long versionId) {
        AiKnowledgeBaseVersion version = versionMapper.selectById(versionId);
        if (version == null || !version.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("知识库版本不存在：kbId=" + knowledgeBaseId + " versionId=" + versionId);
        }
        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new IllegalArgumentException("知识库不存在：" + knowledgeBaseId);
        }

        List<AiKnowledgeDocumentChunk> chunks = loadChunks(versionId);
        Map<Long, AiKnowledgeDocumentRevision> revisions = loadRevisions(chunks);
        Map<Long, String> externalIds = loadExternalIds(versionId);

        KnowledgeProjectionGateway gateway = gatewayProvider.get();
        long now = System.currentTimeMillis();

        // 先清场：版本是不可变快照，投影出来就该与后台那一版逐字一致
        gateway.chunkMapper().deleteByVersion(versionId);

        int written = 0;
        int skippedNoVector = 0;
        Integer dimensions = null;
        for (AiKnowledgeDocumentChunk source : chunks) {
            float[] vector = parseVector(source);
            if (vector.length == 0) {
                skippedNoVector++;
                continue;
            }
            if (dimensions == null) {
                dimensions = vector.length;
            }
            AiKnowledgeDocumentRevision revision = revisions.get(source.getDocumentRevisionId());
            KnowledgeChunkDO target = new KnowledgeChunkDO();
            target.setKbVersionId(versionId);
            target.setDocRevisionId(source.getDocumentRevisionId());
            target.setChunkIndex(source.getChunkIndex());
            target.setContent(source.getContent());
            target.setEmbedding(VectorCodec.encode(vector));
            target.setDimensions(vector.length);
            // ACL 随分片冗余：检索发生在客服端，那边没有后台的文档修订表可 JOIN
            target.setAclMode(revision == null ? "PRIVATE" : revision.getAclMode());
            target.setExternalId(externalIds.get(source.getDocumentRevisionId()));
            target.setCreatedAtMs(now);
            target.setUpdatedAtMs(now);
            gateway.chunkMapper().insert(target);
            written++;
        }

        upsertVersion(gateway, knowledgeBase, version, dimensions, written, now);

        if (skippedNoVector > 0) {
            log.error("knowledge projection skipped chunks without vector, code={} versionId={} skipped={}",
                "KB-PROJECTION-NO-VECTOR", versionId, skippedNoVector);
        }
        log.info("knowledge projection done: kbId={} versionId={} chunks={} dimensions={}",
            knowledgeBaseId, versionId, written, dimensions);
        return written;
    }

    /** 写入客服端侧的版本投影记录，供 C 端按版本检索。 */
    private void upsertVersion(KnowledgeProjectionGateway gateway,
                               AiKnowledgeBase knowledgeBase,
                               AiKnowledgeBaseVersion version,
                               Integer dimensions,
                               int chunkCount,
                               long now) {
        KnowledgeVersionDO existing = gateway.versionMapper().selectOne(
            new QueryWrapper<KnowledgeVersionDO>().eq("kb_version_id", version.getId()));

        KnowledgeVersionDO row = existing == null ? new KnowledgeVersionDO() : existing;
        row.setKbVersionId(version.getId());
        row.setKbCode(String.valueOf(knowledgeBase.getId()));
        row.setKbName(knowledgeBase.getKbName());
        row.setTopN(version.getTopN() == null || version.getTopN() <= 0 ? 3 : version.getTopN());
        row.setScoreThreshold(version.getScoreThreshold() == null
            ? java.math.BigDecimal.ZERO : version.getScoreThreshold());
        // 维度取本次投影实测值：它决定了 C 端拿查询向量来比时能不能比
        row.setDimensions(dimensions == null ? 0 : dimensions);
        row.setChunkCount(chunkCount);
        row.setSyncedAtMs(now);
        row.setUpdatedAtMs(now);
        if (existing == null) {
            row.setCreatedAtMs(now);
            gateway.versionMapper().insert(row);
        } else {
            gateway.versionMapper().updateById(row);
        }
    }

    private List<AiKnowledgeDocumentChunk> loadChunks(Long versionId) {
        List<AiKnowledgeBaseVersionDocument> members = memberMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeBaseVersionDocument>()
                .eq(AiKnowledgeBaseVersionDocument::getKnowledgeBaseVersionId, versionId));
        if (CollectionUtils.isEmpty(members)) {
            return List.of();
        }
        List<Long> revisionIds = members.stream()
            .map(AiKnowledgeBaseVersionDocument::getDocumentRevisionId).toList();
        List<AiKnowledgeDocumentChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeDocumentChunk>()
                .in(AiKnowledgeDocumentChunk::getDocumentRevisionId, revisionIds));
        return chunks == null ? List.of() : chunks;
    }

    private Map<Long, AiKnowledgeDocumentRevision> loadRevisions(List<AiKnowledgeDocumentChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return Map.of();
        }
        List<Long> ids = chunks.stream()
            .map(AiKnowledgeDocumentChunk::getDocumentRevisionId).distinct().toList();
        List<AiKnowledgeDocumentRevision> revisions = revisionMapper.selectBatchIds(ids);
        return revisions == null ? Map.of() : revisions.stream()
            .collect(Collectors.toMap(AiKnowledgeDocumentRevision::getId, Function.identity(),
                (a, b) -> a, HashMap::new));
    }

    /** 文档在知识库版本里的外部标识，用于回答的来源溯源。 */
    private Map<Long, String> loadExternalIds(Long versionId) {
        List<AiKnowledgeBaseVersionDocument> members = memberMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeBaseVersionDocument>()
                .eq(AiKnowledgeBaseVersionDocument::getKnowledgeBaseVersionId, versionId));
        if (CollectionUtils.isEmpty(members)) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>();
        for (AiKnowledgeBaseVersionDocument member : members) {
            if (member.getExternalId() != null) {
                map.put(member.getDocumentRevisionId(), member.getExternalId());
            }
        }
        return map;
    }

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
            log.error("knowledge projection vector parse failed, code={} chunkId={}",
                "KB-PROJECTION-VECTOR-PARSE-FAIL", chunk.getId(), e);
            return new float[0];
        }
    }
}
