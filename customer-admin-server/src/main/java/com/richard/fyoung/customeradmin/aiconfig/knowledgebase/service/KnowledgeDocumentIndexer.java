package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeAclMode;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeAclRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文档内容切块、向量化及 ACL 规范化的唯一入口。 */
@Component
public class KnowledgeDocumentIndexer {

    static final int MAX_DOCUMENT_CHARS = 2_000_000;
    private static final int MIN_CHUNK_CHARS = 200;

    private final EmbeddingClient embeddingClient;
    private final AdminKnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeDocumentIndexer(EmbeddingClient embeddingClient,
                                    AdminKnowledgeProperties properties,
                                    ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PreparedDocument prepare(KnowledgeDocumentChangeRequest change,
                                    KnowledgeAclRequest sourceDefaultAcl) {
        if (!StringUtils.hasText(change.content())) {
            throw new BizException(ResultCode.PARAM_MISSING,
                "UPSERT 文档 content 不能为空: " + change.externalId());
        }
        String content = change.content().strip();
        if (content.length() > MAX_DOCUMENT_CHARS) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "文档内容超过 " + MAX_DOCUMENT_CHARS + " 字符限制: " + change.externalId());
        }
        NormalizedAcl acl = normalizeAcl(change.acl() == null ? sourceDefaultAcl : change.acl());
        List<String> chunks = chunk(content, Math.max(MIN_CHUNK_CHARS, properties.getMaxChunkChars()));
        List<float[]> vectors = embeddingClient.embedDocuments(chunks);
        if (vectors.size() != chunks.size()) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED,
                "Embedding 返回数量与文档分块数量不一致");
        }
        List<PreparedChunk> preparedChunks = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            preparedChunks.add(new PreparedChunk(i, chunks.get(i), writeVector(vectors.get(i)),
                embeddingClient.dimensions()));
        }
        return new PreparedDocument(change, EvalFingerprint.of("knowledge-document-v1", content),
            acl, List.copyOf(preparedChunks));
    }

    public NormalizedAcl normalizeAcl(KnowledgeAclRequest request) {
        KnowledgeAclMode mode = parseMode(request == null ? null : request.mode());
        List<String> subjectTypes = normalizeSubjectTypes(request == null ? null : request.allowedSubjectTypes());
        List<String> subjectIds = normalizeValues(request == null ? null : request.allowedSubjectIds());
        List<String> channels = normalizeValues(request == null ? null : request.allowedChannels());
        if (mode == KnowledgeAclMode.RESTRICTED
            && subjectTypes.isEmpty() && subjectIds.isEmpty() && channels.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "RESTRICTED 文档 ACL 至少要配置主体类型、主体 ID 或渠道之一");
        }
        return new NormalizedAcl(mode.name(), String.join(",", subjectTypes),
            writeJson(subjectIds), writeJson(channels));
    }

    public KnowledgeAclRequest parseAcl(String json) {
        if (!StringUtils.hasText(json)) {
            return new KnowledgeAclRequest(KnowledgeAclMode.PUBLIC.name(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, KnowledgeAclRequest.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "defaultAcl 不是合法 JSON");
        }
    }

    public String writeAcl(KnowledgeAclRequest request) {
        NormalizedAcl normalized = normalizeAcl(request);
        return writeJson(new KnowledgeAclRequest(normalized.mode(),
            csv(normalized.allowedSubjectTypes()), readList(normalized.allowedSubjectIds()),
            readList(normalized.allowedChannels())));
    }

    static List<String> chunk(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\\R\\s*\\R")) {
            String normalized = paragraph.strip();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.length() > maxChars) {
                flush(current, chunks);
                for (int start = 0; start < normalized.length(); start += maxChars) {
                    chunks.add(normalized.substring(start, Math.min(normalized.length(), start + maxChars)));
                }
            } else if (current.length() == 0) {
                current.append(normalized);
            } else if (current.length() + 2 + normalized.length() <= maxChars) {
                current.append("\n\n").append(normalized);
            } else {
                flush(current, chunks);
                current.append(normalized);
            }
        }
        flush(current, chunks);
        return chunks.isEmpty() ? List.of(content) : List.copyOf(chunks);
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private KnowledgeAclMode parseMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return KnowledgeAclMode.PUBLIC;
        }
        try {
            return KnowledgeAclMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "ACL mode 仅支持 PUBLIC/RESTRICTED");
        }
    }

    private List<String> normalizeSubjectTypes(List<String> raw) {
        List<String> values = normalizeValues(raw).stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).toList();
        for (String value : values) {
            try {
                QuotaSubjectType.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的 ACL subjectType: " + value);
            }
        }
        return values;
    }

    private List<String> normalizeValues(List<String> raw) {
        if (CollectionUtils.isEmpty(raw)) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : raw) {
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    private String writeVector(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.KNOWLEDGE_EMBEDDING_FAILED, "Embedding 序列化失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "ACL 序列化失败");
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "ACL 列表解析失败");
        }
    }

    private List<String> csv(String raw) {
        return StringUtils.hasText(raw) ? List.of(raw.split(",")) : List.of();
    }

    public record PreparedDocument(KnowledgeDocumentChangeRequest change,
                                   String contentHash,
                                   NormalizedAcl acl,
                                   List<PreparedChunk> chunks) {
    }

    public record PreparedChunk(int index, String content, String embedding, int dimensions) {
    }

    public record NormalizedAcl(String mode,
                                String allowedSubjectTypes,
                                String allowedSubjectIds,
                                String allowedChannels) {
    }
}
