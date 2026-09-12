package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeVersionPreviewService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatKnowledgeSourcesVO;
import com.richard.fyoung.customeradmin.workspace.chat.entity.ChatKnowledgeEvidence;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.ChatKnowledgeEvidenceMapper;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalCapture;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 来源快照归属消息，读取时重查消息和文档授权；不使用历史缓存或调用日志替代权威存储。 */
@Service
public class ChatKnowledgeSourcesService {
    private static final TypeReference<List<KnowledgeRetrievalCapture.Retrieval>> RETRIEVALS_TYPE =
        new TypeReference<>() {};
    private final ChatKnowledgeEvidenceMapper mapper;
    private final AgentStateStore stateStore;
    private final KnowledgeVersionPreviewService previewService;
    private final ObjectMapper objectMapper;

    public ChatKnowledgeSourcesService(ChatKnowledgeEvidenceMapper mapper, AgentStateStore stateStore,
                                       KnowledgeVersionPreviewService previewService, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.stateStore = stateStore;
        this.previewService = previewService;
        this.objectMapper = objectMapper;
    }

    /** 只由本轮回读成功后的完成确认调用；异步写入恢复本轮冻结的租户，失败不篡改已保存的答复。 */
    boolean saveConfirmed(RuntimeContext context, String turnId, String messageId) {
        AgentInvocationIdentity identity = context.get(AgentInvocationIdentity.class);
        KnowledgeRetrievalCapture capture = context.get(KnowledgeRetrievalCapture.class);
        if (identity == null || !identity.authenticated() || identity.subjectType() != QuotaSubjectType.ADMIN_USER
            || capture == null) {
            return false;
        }
        List<KnowledgeRetrievalCapture.Retrieval> retrievals = capture.snapshot();
        return TenantContext.callWith(identity.tenantId(), () -> {
            ChatKnowledgeEvidence evidence = new ChatKnowledgeEvidence();
            evidence.setTenantId(TenantContext.require());
            evidence.setStateUserId(context.getUserId());
            evidence.setAgentCode(identity.agentCode());
            evidence.setSessionId(context.getSessionId());
            evidence.setTurnId(turnId);
            evidence.setMessageId(messageId);
            evidence.setRetrievals(encode(retrievals));
            evidence.setCreatedAtMs(System.currentTimeMillis());
            mapper.insertIfAbsent(evidence);
            ChatKnowledgeEvidence saved = mapper.findByMessage(evidence.getTenantId(), evidence.getStateUserId(),
                evidence.getAgentCode(), evidence.getSessionId(), messageId);
            return matches(saved, evidence.getTenantId(), evidence.getStateUserId(), evidence.getAgentCode(),
                evidence.getSessionId(), messageId) && Objects.equals(turnId, saved.getTurnId())
                && retrievals.equals(decode(saved.getRetrievals()));
        });
    }

    /** 列表只返回本次仍可读取的元数据，撤权的旧标题和分数一并隐藏。 */
    public ChatKnowledgeSourcesVO sources(String agentCode, String sessionId, String messageId,
                                         AgentInvocationIdentity identity) {
        ChatKnowledgeEvidence evidence = findOwnedMessage(agentCode, sessionId, messageId, identity);
        if (evidence == null) {
            return new ChatKnowledgeSourcesVO(ChatKnowledgeSourcesVO.Status.NOT_RECORDED, List.of());
        }
        List<ChatKnowledgeSourcesVO.Retrieval> results = new ArrayList<>();
        Map<DocumentVersion, KnowledgeDocumentPreviewVO> previews = new HashMap<>();
        int sourceId = 0;
        for (var retrieval : decode(evidence.getRetrievals())) {
            List<ChatKnowledgeSourcesVO.Source> sources = new ArrayList<>();
            for (KnowledgeRetrievalSource source : retrieval.sources()) {
                sources.add(describe(sourceId++, source, identity, previews));
            }
            results.add(new ChatKnowledgeSourcesVO.Retrieval(retrieval.agentCode(), retrieval.status(), sources));
        }
        return new ChatKnowledgeSourcesVO(ChatKnowledgeSourcesVO.Status.RECORDED, results);
    }

    /** 浏览器只能选本消息内的序号，不能替换知识库、版本或修订关系。 */
    public KnowledgeDocumentPreviewVO preview(String agentCode, String sessionId, String messageId,
                                              int sourceId, AgentInvocationIdentity identity) {
        ChatKnowledgeEvidence evidence = findOwnedMessage(agentCode, sessionId, messageId, identity);
        if (evidence == null) {
            throw unavailable();
        }
        var sources = decode(evidence.getRetrievals()).stream().flatMap(retrieval -> retrieval.sources().stream())
            .toList();
        if (sourceId < 0 || sourceId >= sources.size()) {
            throw unavailable();
        }
        KnowledgeDocumentReference reference = sources.get(sourceId).documentReference();
        if (reference == null || !reference.complete()) {
            throw unavailable();
        }
        return previewService.preview(reference.knowledgeBaseId(), reference.versionId(), reference.revisionId(),
            identity);
    }

    private ChatKnowledgeEvidence findOwnedMessage(String agentCode, String sessionId, String messageId,
                                                   AgentInvocationIdentity identity) {
        if (identity == null || !identity.authenticated() || identity.subjectType() != QuotaSubjectType.ADMIN_USER
            || !TenantContext.sameTenant(TenantContext.require(), identity.tenantId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        String stateUserId = AgentInvocationIdentityContext.callWith(identity,
            () -> AgentMemoryScope.current(agentCode).stateUserId());
        String safeSession = WorkspaceRuntimeScope.safeSession(sessionId);
        ChatKnowledgeEvidence evidence = mapper.findByMessage(TenantContext.require(), stateUserId, agentCode,
            safeSession, messageId);
        if (evidence == null) {
            return null;
        }
        if (!matches(evidence, TenantContext.require(), stateUserId, agentCode, safeSession, messageId)) {
            throw unavailable();
        }
        // 来源快照不能让已删除的会话或消息重新获得访问能力。
        var state = stateStore.get(stateUserId, safeSession, ChatCompletionVerifier.AGENT_STATE_KEY,
            AgentState.class);
        boolean inTurn = false;
        if (state.isPresent()) {
            for (var message : state.get().getContext()) {
                if (message.getRole() == MsgRole.USER) {
                    inTurn = Objects.equals(message.getId(), evidence.getTurnId());
                } else if (inTurn && message.getRole() == MsgRole.ASSISTANT
                    && Objects.equals(message.getId(), messageId)) {
                    return evidence;
                }
            }
        }
        throw unavailable();
    }

    private ChatKnowledgeSourcesVO.Source describe(int id, KnowledgeRetrievalSource source,
        AgentInvocationIdentity identity, Map<DocumentVersion, KnowledgeDocumentPreviewVO> previews) {
        KnowledgeDocumentReference reference = source.documentReference();
        if (reference == null || !reference.complete()) {
            return new ChatKnowledgeSourcesVO.Source(id, source.number(), ChatKnowledgeSourcesVO.SourceStatus.EXTERNAL,
                source.knowledgeBaseName(), source.documentId(), source.chunkId(), source.score(), null);
        }
        try {
            // 同一文档的多个分片共用本次授权结果，不跨请求缓存授权或原文。
            var version = new DocumentVersion(reference.knowledgeBaseId(), reference.versionId(), reference.revisionId());
            KnowledgeDocumentPreviewVO preview = previews.computeIfAbsent(version, ref ->
                previewService.preview(ref.knowledgeBaseId(), ref.versionId(), ref.revisionId(), identity));
            return new ChatKnowledgeSourcesVO.Source(id, source.number(), ChatKnowledgeSourcesVO.SourceStatus.AVAILABLE,
                source.knowledgeBaseName(), source.documentId(), source.chunkId(), source.score(), preview.document());
        } catch (BizException error) {
            ChatKnowledgeSourcesVO.SourceStatus status;
            if (error.getResultCode() == ResultCode.FORBIDDEN) {
                status = ChatKnowledgeSourcesVO.SourceStatus.FORBIDDEN;
            } else if (error.getResultCode() == ResultCode.RESOURCE_NOT_FOUND) {
                status = ChatKnowledgeSourcesVO.SourceStatus.UNAVAILABLE;
            } else {
                throw error;
            }
            return new ChatKnowledgeSourcesVO.Source(id, source.number(), status, null, null, null, null, null);
        }
    }

    private String encode(List<KnowledgeRetrievalCapture.Retrieval> retrievals) {
        try {
            return objectMapper.writeValueAsString(retrievals);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode chat knowledge evidence", error);
        }
    }

    private boolean matches(ChatKnowledgeEvidence evidence, String tenantId, String stateUserId,
                            String agentCode, String sessionId, String messageId) {
        // 库表采用统一的不区分大小写排序规则；资源标识仍须精确匹配，不能接受别名命中。
        return evidence != null && Objects.equals(tenantId, evidence.getTenantId())
            && Objects.equals(stateUserId, evidence.getStateUserId())
            && Objects.equals(agentCode, evidence.getAgentCode())
            && Objects.equals(sessionId, evidence.getSessionId())
            && Objects.equals(messageId, evidence.getMessageId());
    }

    private record DocumentVersion(Long knowledgeBaseId, Long versionId, Long revisionId) {
    }

    private List<KnowledgeRetrievalCapture.Retrieval> decode(String json) {
        try {
            return objectMapper.readValue(json, RETRIEVALS_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot decode chat knowledge evidence", error);
        }
    }

    private BizException unavailable() {
        return new BizException(ResultCode.RESOURCE_NOT_FOUND, "本条消息的来源记录已不可用，请重新核对会话历史");
    }
}
