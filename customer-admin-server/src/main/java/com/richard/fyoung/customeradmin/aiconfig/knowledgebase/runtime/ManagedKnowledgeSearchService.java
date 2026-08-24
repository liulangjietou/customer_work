package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeAclMode;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customerwork.data.knowledge.VectorMath;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 对不可变知识库版本执行本地向量检索，并在读取正文前实施文档 ACL。 */
@Component
public class ManagedKnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(ManagedKnowledgeSearchService.class);
    private static final String ACL_PARSE_ERROR_CODE = "KB-ACL-PARSE-FAILED";
    private static final int DEFAULT_TOP_N = 5;

    private final AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private final AiKnowledgeDocumentRevisionMapper revisionMapper;
    private final AiKnowledgeDocumentChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public ManagedKnowledgeSearchService(AiKnowledgeBaseVersionDocumentMapper memberMapper,
                                         AiKnowledgeDocumentRevisionMapper revisionMapper,
                                         AiKnowledgeDocumentChunkMapper chunkMapper,
                                         EmbeddingClient embeddingClient,
                                         ObjectMapper objectMapper) {
        this.memberMapper = memberMapper;
        this.revisionMapper = revisionMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeNode> search(String knowledgeBaseName,
                                      AiKnowledgeBaseVersion version,
                                      String query,
                                      AgentInvocationIdentity identity) {
        List<AiKnowledgeBaseVersionDocument> members = memberMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeBaseVersionDocument>()
                .eq(AiKnowledgeBaseVersionDocument::getKnowledgeBaseVersionId, version.getId()));
        if (CollectionUtils.isEmpty(members)) {
            return List.of();
        }
        Map<Long, AiKnowledgeBaseVersionDocument> memberByRevision = members.stream()
            .collect(Collectors.toMap(AiKnowledgeBaseVersionDocument::getDocumentRevisionId,
                Function.identity()));
        List<AiKnowledgeDocumentRevision> authorized = revisionMapper
            .selectBatchIds(memberByRevision.keySet()).stream()
            .filter(revision -> allowed(revision, identity))
            .toList();
        if (CollectionUtils.isEmpty(authorized)) {
            return List.of();
        }
        Set<Long> authorizedIds = authorized.stream().map(AiKnowledgeDocumentRevision::getId)
            .collect(Collectors.toSet());
        List<AiKnowledgeDocumentChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<AiKnowledgeDocumentChunk>()
                .in(AiKnowledgeDocumentChunk::getDocumentRevisionId, authorizedIds));
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        float[] queryVector = embeddingClient.embedQuery(query);
        BigDecimal threshold = version.getScoreThreshold() == null
            ? BigDecimal.ZERO : version.getScoreThreshold();
        int topN = version.getTopN() == null || version.getTopN() <= 0
            ? DEFAULT_TOP_N : version.getTopN();
        return chunks.stream()
            // ACL 必须在正文进入排序/渲染前再次约束到已授权修订，避免未来自定义 Mapper
            // 忽略 in 条件时把未授权 chunk 带入内存。
            .filter(chunk -> authorizedIds.contains(chunk.getDocumentRevisionId()))
            .map(chunk -> score(chunk, queryVector))
            .filter(scored -> scored.score().compareTo(threshold) >= 0)
            .sorted(Comparator.comparing(ScoredChunk::score).reversed())
            .limit(topN)
            .map(scored -> {
                AiKnowledgeBaseVersionDocument member = memberByRevision
                    .get(scored.chunk().getDocumentRevisionId());
                return new KnowledgeNode(knowledgeBaseName, scored.chunk().getContent(), scored.score(),
                    member.getExternalId(), String.valueOf(scored.chunk().getId()));
            })
            .toList();
    }

    private ScoredChunk score(AiKnowledgeDocumentChunk chunk, float[] queryVector) {
        double raw = VectorMath.cosine(queryVector, parseVector(chunk.getEmbedding()));
        return new ScoredChunk(chunk, BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP));
    }

    private boolean allowed(AiKnowledgeDocumentRevision revision, AgentInvocationIdentity identity) {
        if (KnowledgeAclMode.PUBLIC.name().equals(revision.getAclMode())) {
            return true;
        }
        if (!KnowledgeAclMode.RESTRICTED.name().equals(revision.getAclMode())) {
            return false;
        }
        if (identity == null || identity.subjectType() == null) {
            return false;
        }
        return matchesCsv(revision.getAllowedSubjectTypes(), identity.subjectType().name())
            && matchesJson(revision.getAllowedSubjectIds(), identity.subjectId(), revision.getId(), "subjectIds")
            && matchesJson(revision.getAllowedChannels(), identity.channelCode(), revision.getId(), "channels");
    }

    private boolean matchesCsv(String configured, String actual) {
        if (!StringUtils.hasText(configured)) {
            return true;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return List.of(configured.split(",")).stream()
            .map(String::trim).anyMatch(actual::equalsIgnoreCase);
    }

    private boolean matchesJson(String configured, String actual, Long revisionId, String field) {
        if (!StringUtils.hasText(configured) || "[]".equals(configured.trim())) {
            return true;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        try {
            List<String> values = objectMapper.readValue(configured,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return values.stream().filter(Objects::nonNull).anyMatch(actual::equalsIgnoreCase);
        } catch (Exception e) {
            log.error("knowledge ACL parse failed, errorCode={}, revisionId={}, field={}",
                ACL_PARSE_ERROR_CODE, revisionId, field, e);
            return false;
        }
    }

    private float[] parseVector(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.error("knowledge embedding parse failed, errorCode={}, vectorLength={}",
                "KB-VECTOR-PARSE-FAILED", json == null ? 0 : json.length(), e);
            return new float[0];
        }
    }

    private record ScoredChunk(AiKnowledgeDocumentChunk chunk, BigDecimal score) {
    }
}
