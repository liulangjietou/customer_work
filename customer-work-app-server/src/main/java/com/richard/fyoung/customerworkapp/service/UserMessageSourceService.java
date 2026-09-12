package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.data.knowledge.KnowledgePublicSource;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerworkapp.dao.UserMessageSourceDao;
import com.richard.fyoung.customerworkapp.dto.UserMessageSource;
import com.richard.fyoung.customerworkapp.dto.UserMessageSourcePreview;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 客户消息范围内的只读来源用例：应用层负责身份/消息绑定，正文授权由投影 SQL 负责。 */
@Service
public class UserMessageSourceService {
    private final UserMessageSourceDao messageSources;
    private final ObjectProvider<KnowledgeChunkMapper> chunkMapper;

    public UserMessageSourceService(UserMessageSourceDao messageSources, ObjectProvider<KnowledgeChunkMapper> chunkMapper) {
        this.messageSources = messageSources;
        this.chunkMapper = chunkMapper;
    }

    /** 同一完整引用在目录中展示一次，仍保留原保存列表索引供预览绑定。 */
    public List<UserMessageSource> sources(UserPrincipal user, String sessionId, String messageId) {
        List<KnowledgeRetrievalSource> saved = savedSources(user, sessionId, messageId);
        List<UserMessageSource> sources = new ArrayList<>();
        Set<KnowledgeDocumentReference> seen = new HashSet<>();
        for (int index = 0; index < saved.size(); index++) {
            KnowledgeDocumentReference reference = saved.get(index).documentReference();
            if (reference != null && !seen.add(reference)) continue;
            sources.add(UserMessageSource.from(index, readSource(user.tenantId(), reference, false)));
        }
        return List.copyOf(sources);
    }

    /** 仅从本人消息保存的列表取引用，客户端不能自行指定内部文档和版本标识。 */
    public UserMessageSourcePreview preview(UserPrincipal user, String sessionId, String messageId, int sourceIndex) {
        List<KnowledgeRetrievalSource> saved = savedSources(user, sessionId, messageId);
        if (sourceIndex < 0 || sourceIndex >= saved.size()) throw notFound();
        return UserMessageSourcePreview.from(sourceIndex, readSource(user.tenantId(), saved.get(sourceIndex).documentReference(), true));
    }

    private List<KnowledgeRetrievalSource> savedSources(UserPrincipal user, String sessionId, String messageId) {
        return messageSources.findOwnedSources(user.tenantId(), user.userId(), sessionId, messageId)
            .orElseThrow(UserMessageSourceService::notFound);
    }

    private KnowledgePublicSource readSource(String tenantId, KnowledgeDocumentReference reference, boolean includeContent) {
        if (reference == null || !reference.complete()) return null;
        KnowledgeChunkMapper mapper = chunkMapper.getIfAvailable();
        if (mapper == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "source storage unavailable");
        }
        return mapper.findPublicSource(tenantId, String.valueOf(reference.knowledgeBaseId()), reference.versionId(),
            reference.revisionId(), reference.chunkId(), includeContent);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "message source not found");
    }
}
