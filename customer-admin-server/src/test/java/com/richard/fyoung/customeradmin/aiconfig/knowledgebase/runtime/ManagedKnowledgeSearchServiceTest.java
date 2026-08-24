package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedKnowledgeSearchServiceTest {

    private AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private AiKnowledgeDocumentRevisionMapper revisionMapper;
    private AiKnowledgeDocumentChunkMapper chunkMapper;
    private ManagedKnowledgeSearchService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeBaseVersionDocument.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeDocumentRevision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeDocumentChunk.class);
    }

    @BeforeEach
    void setUp() {
        memberMapper = mock(AiKnowledgeBaseVersionDocumentMapper.class);
        revisionMapper = mock(AiKnowledgeDocumentRevisionMapper.class);
        chunkMapper = mock(AiKnowledgeDocumentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embedQuery("怎么退款")).thenReturn(new float[]{1.0f, 0.0f});
        service = new ManagedKnowledgeSearchService(memberMapper, revisionMapper, chunkMapper,
            embeddingClient, new ObjectMapper());
    }

    @Test
    void restrictedDocument_shouldRequireEveryConfiguredIdentityDimension() {
        givenCorpus();
        AiKnowledgeBaseVersion version = version();
        AgentInvocationIdentity allowed = new AgentInvocationIdentity("tenant-a", QuotaSubjectType.USER,
            "42", true, 1L, AgentInvocationIdentity.CHANNEL_USER_HTTP, "s1", "agent-a");

        List<KnowledgeNode> allowedNodes = service.search("退款知识库", version, "怎么退款", allowed);
        List<KnowledgeNode> wrongChannelNodes = service.search("退款知识库", version, "怎么退款",
            allowed.withChannel(AgentInvocationIdentity.CHANNEL_ADMIN));
        List<KnowledgeNode> anonymousNodes = service.search("退款知识库", version, "怎么退款", null);

        assertEquals(List.of("restricted", "public"), allowedNodes.stream()
            .map(KnowledgeNode::docId).toList());
        assertEquals(List.of("public"), wrongChannelNodes.stream().map(KnowledgeNode::docId).toList());
        assertEquals(List.of("public"), anonymousNodes.stream().map(KnowledgeNode::docId).toList());
    }

    @Test
    void malformedRestrictedAcl_shouldFailClosed_withoutHidingPublicDocument() {
        givenCorpus();
        AiKnowledgeDocumentRevision restricted = revision(20L, "RESTRICTED");
        restricted.setAllowedSubjectTypes("USER");
        restricted.setAllowedSubjectIds("not-json");
        restricted.setAllowedChannels("[]");
        when(revisionMapper.selectBatchIds(any())).thenReturn(List.of(revision(10L, "PUBLIC"), restricted));
        AgentInvocationIdentity identity = new AgentInvocationIdentity("tenant-a", QuotaSubjectType.USER,
            "42", true).forInvocation(AgentInvocationIdentity.CHANNEL_USER_HTTP, "s1", "agent-a");

        List<KnowledgeNode> nodes = service.search("退款知识库", version(), "怎么退款", identity);

        assertEquals(1, nodes.size());
        assertEquals("public", nodes.get(0).docId());
    }

    @Test
    void unknownAclMode_shouldFailClosed() {
        givenCorpus();
        AiKnowledgeDocumentRevision unknown = revision(20L, "UNKNOWN");
        when(revisionMapper.selectBatchIds(any())).thenReturn(List.of(revision(10L, "PUBLIC"), unknown));

        List<KnowledgeNode> nodes = service.search("退款知识库", version(), "怎么退款", null);

        assertEquals(List.of("public"), nodes.stream().map(KnowledgeNode::docId).toList());
    }

    private void givenCorpus() {
        AiKnowledgeBaseVersionDocument publicMember = member(10L, "public");
        AiKnowledgeBaseVersionDocument restrictedMember = member(20L, "restricted");
        when(memberMapper.selectList(any())).thenReturn(List.of(publicMember, restrictedMember));

        AiKnowledgeDocumentRevision publicRevision = revision(10L, "PUBLIC");
        AiKnowledgeDocumentRevision restrictedRevision = revision(20L, "RESTRICTED");
        restrictedRevision.setAllowedSubjectTypes("USER");
        restrictedRevision.setAllowedSubjectIds("[\"42\"]");
        restrictedRevision.setAllowedChannels("[\"user-http\"]");
        when(revisionMapper.selectBatchIds(any())).thenReturn(List.of(publicRevision, restrictedRevision));

        AiKnowledgeDocumentChunk publicChunk = chunk(100L, 10L, "公开说明", "[0.8,0.2]");
        AiKnowledgeDocumentChunk restrictedChunk = chunk(200L, 20L, "用户退款说明", "[1.0,0.0]");
        when(chunkMapper.selectList(any())).thenReturn(List.of(publicChunk, restrictedChunk));
    }

    private AiKnowledgeBaseVersion version() {
        AiKnowledgeBaseVersion version = new AiKnowledgeBaseVersion();
        version.setId(7L);
        version.setTopN(10);
        version.setScoreThreshold(BigDecimal.ZERO);
        return version;
    }

    private AiKnowledgeBaseVersionDocument member(Long revisionId, String externalId) {
        AiKnowledgeBaseVersionDocument member = new AiKnowledgeBaseVersionDocument();
        member.setDocumentRevisionId(revisionId);
        member.setExternalId(externalId);
        return member;
    }

    private AiKnowledgeDocumentRevision revision(Long id, String mode) {
        AiKnowledgeDocumentRevision revision = new AiKnowledgeDocumentRevision();
        revision.setId(id);
        revision.setAclMode(mode);
        revision.setAllowedSubjectTypes("");
        revision.setAllowedSubjectIds("[]");
        revision.setAllowedChannels("[]");
        return revision;
    }

    private AiKnowledgeDocumentChunk chunk(Long id, Long revisionId, String content, String embedding) {
        AiKnowledgeDocumentChunk chunk = new AiKnowledgeDocumentChunk();
        chunk.setId(id);
        chunk.setDocumentRevisionId(revisionId);
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        return chunk;
    }
}
