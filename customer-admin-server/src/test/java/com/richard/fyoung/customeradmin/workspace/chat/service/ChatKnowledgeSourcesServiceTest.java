package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgePreviewStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeVersionDocumentVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeVersionPreviewService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatKnowledgeSourcesVO;
import com.richard.fyoung.customeradmin.workspace.chat.entity.ChatKnowledgeEvidence;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.ChatKnowledgeEvidenceMapper;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeDocumentReference;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalCapture;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalResult;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实框架消息 JSON 核对关联，存储和既有文档授权服务作为独立边界。 */
class ChatKnowledgeSourcesServiceTest {
    private final ChatKnowledgeEvidenceMapper mapper = mock(ChatKnowledgeEvidenceMapper.class);
    private final AgentStateStore stateStore = mock(AgentStateStore.class);
    private final KnowledgeVersionPreviewService preview = mock(KnowledgeVersionPreviewService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ChatKnowledgeSourcesService service = new ChatKnowledgeSourcesService(mapper, stateStore, preview, json);
    private final AgentInvocationIdentity identity = identity("tenant-a", "42");
    private String stateUserId;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        stateUserId = AgentInvocationIdentityContext.callWith(identity,
            () -> AgentMemoryScope.current("refund").stateUserId());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @Test
    void oldUnrecordedMessageIsNotInventedAsAMiss() {
        assertEquals(ChatKnowledgeSourcesVO.Status.NOT_RECORDED,
            service.sources("refund", "s1", "old", identity).status());
        verifyNoInteractions(stateStore, preview);
    }

    @ParameterizedTest
    @EnumSource(KnowledgeRetrievalResult.Status.class)
    void actualRetrievalOutcomeSurvivesStorage(KnowledgeRetrievalResult.Status status) throws Exception {
        stored(List.of(new KnowledgeRetrievalCapture.Retrieval("refund", status, List.of())));
        savedMessages("turn-1", "reply-1");
        var result = service.sources("refund", "s1", "reply-1", identity);
        assertEquals(ChatKnowledgeSourcesVO.Status.RECORDED, result.status());
        assertEquals(status, result.retrievals().get(0).status());
        verifyNoInteractions(preview);
    }

    @Test
    void emptyCaptureStaysDistinctFromARecordedMiss() throws Exception {
        stored(List.of());
        savedMessages("turn-1", "reply-1");
        var result = service.sources("refund", "s1", "reply-1", identity);
        assertEquals(ChatKnowledgeSourcesVO.Status.RECORDED, result.status());
        assertTrue(result.retrievals().isEmpty());
    }

    @Test
    void metadataAndPreviewUseStoredVersionWithTheCurrentIdentity() throws Exception {
        stored(retrievals(internal()));
        savedMessages("turn-1", "reply-1");
        when(preview.preview(7L, 70L, 100L, identity)).thenReturn(authorized());
        // 其他残留线程身份不得改变此次请求的主体分区。
        AgentInvocationIdentityContext.set(identity("tenant-a", "99"));
        var source = service.sources("refund", "s1", "reply-1", identity).retrievals().get(0).sources().get(0);
        assertEquals(ChatKnowledgeSourcesVO.SourceStatus.AVAILABLE, source.status());
        assertEquals(0, source.sourceId());
        assertEquals(1, source.number());
        assertEquals(12, source.document().versionNo());
        assertFalse(json.writeValueAsString(source).contains("原文正文"));
        assertEquals("原文正文", service.preview("refund", "s1", "reply-1", 0, identity).content());
        verify(preview, times(2)).preview(7L, 70L, 100L, identity);
        verify(mapper, times(2)).findByMessage("tenant-a", stateUserId, "refund", "s1", "reply-1");
    }

    @Test
    void revokedSourcesHideOldMetadataAndCannotReuseEarlierPreview() throws Exception {
        stored(retrievals(internal()));
        savedMessages("turn-1", "reply-1");
        when(preview.preview(7L, 70L, 100L, identity)).thenReturn(authorized());
        assertEquals("原文正文", service.preview("refund", "s1", "reply-1", 0, identity).content());
        when(preview.preview(7L, 70L, 100L, identity)).thenThrow(new BizException(ResultCode.FORBIDDEN));
        var source = service.sources("refund", "s1", "reply-1", identity).retrievals().get(0).sources().get(0);
        assertEquals(ChatKnowledgeSourcesVO.SourceStatus.FORBIDDEN, source.status());
        assertNull(source.knowledgeBaseName());
        assertNull(source.documentId());
        assertNull(source.chunkId());
        assertNull(source.score());
        assertNull(source.document());
        assertThrows(BizException.class, () -> service.preview("refund", "s1", "reply-1", 0, identity));
        doThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND)).when(preview).preview(7L, 70L, 100L, identity);
        assertEquals(ChatKnowledgeSourcesVO.SourceStatus.UNAVAILABLE,
            service.sources("refund", "s1", "reply-1", identity).retrievals().get(0).sources().get(0).status());
    }

    @Test
    void externalClueCannotInventATrustedDocumentReference() throws Exception {
        stored(retrievals(new KnowledgeRetrievalSource(1, "external", "revision_id=100", "https://private/70",
            BigDecimal.ONE, null)));
        savedMessages("turn-1", "reply-1");
        assertEquals(ChatKnowledgeSourcesVO.SourceStatus.EXTERNAL,
            service.sources("refund", "s1", "reply-1", identity).retrievals().get(0).sources().get(0).status());
        assertThrows(BizException.class, () -> service.preview("refund", "s1", "reply-1", 0, identity));
        verifyNoInteractions(preview);
    }

    @Test
    void missingOrMovedMessageCannotBeResurrectedByEvidence() throws Exception {
        stored(retrievals(internal()));
        when(stateStore.get(stateUserId, "s1", "agent_state", AgentState.class)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.sources("refund", "s1", "reply-1", identity));
        savedMessages("another-turn", "reply-1");
        assertThrows(BizException.class, () -> service.preview("refund", "s1", "reply-1", 0, identity));
        savedMessages("turn-1", "another-reply");
        assertThrows(BizException.class, () -> service.sources("refund", "s1", "reply-1", identity));
        verifyNoInteractions(preview);
    }

    @Test
    void invalidSourceIndexCannotReachAnUnrelatedDocument() throws Exception {
        stored(retrievals(internal()));
        savedMessages("turn-1", "reply-1");
        assertThrows(BizException.class, () -> service.preview("refund", "s1", "reply-1", -1, identity));
        assertThrows(BizException.class, () -> service.preview("refund", "s1", "reply-1", 1, identity));
        verifyNoInteractions(preview);
    }

    @Test
    void wrongTenantAndUnauthenticatedIdentityFailBeforeStorage() {
        assertThrows(BizException.class, () -> service.sources("refund", "s1", "reply-1", identity("tenant-b", "42")));
        assertThrows(BizException.class, () -> service.sources("refund", "s1", "reply-1",
            new AgentInvocationIdentity("tenant-a", QuotaSubjectType.ADMIN_USER, "42", false)));
        verifyNoInteractions(mapper, stateStore, preview);
    }

    @Test
    void storageOrSourceReadFailureNeverLooksLikeAnEmptySearch() throws Exception {
        stored(retrievals(internal()));
        savedMessages("turn-1", "reply-1");
        when(preview.preview(anyLong(), anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> service.sources("refund", "s1", "reply-1", identity));
        when(mapper.findByMessage(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.sources("refund", "s1", "reply-1", identity));
    }

    private void stored(List<KnowledgeRetrievalCapture.Retrieval> retrievals) throws Exception {
        ChatKnowledgeEvidence evidence = new ChatKnowledgeEvidence();
        evidence.setTenantId("tenant-a");
        evidence.setStateUserId(stateUserId);
        evidence.setAgentCode("refund");
        evidence.setSessionId("s1");
        evidence.setTurnId("turn-1");
        evidence.setMessageId("reply-1");
        evidence.setRetrievals(json.writeValueAsString(retrievals));
        when(mapper.findByMessage("tenant-a", stateUserId, "refund", "s1", "reply-1")).thenReturn(evidence);
    }

    private void savedMessages(String turnId, String messageId) {
        AgentState state = AgentState.builder().userId(stateUserId).sessionId("s1").context(List.of(
            Msg.builder().id(turnId).role(MsgRole.USER).textContent("退款").build(),
            Msg.builder().id(messageId).role(MsgRole.ASSISTANT).textContent("答复").build())).build();
        when(stateStore.get(stateUserId, "s1", "agent_state", AgentState.class))
            .thenReturn(Optional.of(AgentState.fromJsonString(state.toJson())));
    }

    static AgentInvocationIdentity identity(String tenant, String userId) {
        return new AgentInvocationIdentity(tenant, QuotaSubjectType.ADMIN_USER, userId, true)
            .forInvocation(AgentInvocationIdentity.CHANNEL_ADMIN, "s1", "refund");
    }

    private static KnowledgeRetrievalSource internal() {
        return new KnowledgeRetrievalSource(1, "售后知识", "refund-policy", "chunk-9", new BigDecimal("0.750"),
            new KnowledgeDocumentReference(7L, 70L, 100L, 1000L));
    }

    private static List<KnowledgeRetrievalCapture.Retrieval> retrievals(KnowledgeRetrievalSource source) {
        return List.of(new KnowledgeRetrievalCapture.Retrieval("refund", KnowledgeRetrievalResult.Status.HIT,
            List.of(source)));
    }

    private static KnowledgeDocumentPreviewVO authorized() {
        return new KnowledgeDocumentPreviewVO(new KnowledgeVersionDocumentVO(7L, 70L, 12, 100L,
            KnowledgePreviewStatus.AVAILABLE, "售后规范", "refund-policy", "授权知识源", "v12", null, "hash",
            false, null, null), "原文正文");
    }
}
