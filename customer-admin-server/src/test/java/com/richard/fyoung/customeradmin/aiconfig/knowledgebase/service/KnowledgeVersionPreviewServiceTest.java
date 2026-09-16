package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgePreviewStatus;
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
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原文预览跨越历史版本和当前权限，测试以越权、撤回及不可变正文为边界。 */
class KnowledgeVersionPreviewServiceTest {
    private final AiKnowledgeBaseMapper bases = mock(AiKnowledgeBaseMapper.class);
    private final AiKnowledgeBaseVersionMapper versions = mock(AiKnowledgeBaseVersionMapper.class);
    private final AiKnowledgeBaseVersionDocumentMapper members = mock(AiKnowledgeBaseVersionDocumentMapper.class);
    private final AiKnowledgeDocumentRevisionMapper revisions = mock(AiKnowledgeDocumentRevisionMapper.class);
    private final AiKnowledgeDocumentMapper documents = mock(AiKnowledgeDocumentMapper.class);
    private final AiKnowledgeSourceMapper sources = mock(AiKnowledgeSourceMapper.class);
    private final KnowledgeVersionPreviewService service = new KnowledgeVersionPreviewService(
        bases, versions, members, revisions, documents, sources, new ObjectMapper());
    private final AgentInvocationIdentity identity = new AgentInvocationIdentity("tenant-a",
        QuotaSubjectType.ADMIN_USER, "42", true).withChannel(AgentInvocationIdentity.CHANNEL_ADMIN);
    private AiKnowledgeBase base;
    private AiKnowledgeBaseVersion version;
    private AiKnowledgeBaseVersionDocument member;
    private AiKnowledgeDocumentRevision original;
    private AiKnowledgeDocumentRevision current;
    private AiKnowledgeDocument document;
    private AiKnowledgeSource source;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
            AiKnowledgeBaseVersionDocument.class);
    }

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        base = new AiKnowledgeBase();
        base.setId(7L);
        version = new AiKnowledgeBaseVersion();
        version.setId(70L);
        version.setKnowledgeBaseId(7L);
        version.setTenantId("tenant-a");
        version.setVersionNo(3);
        member = new AiKnowledgeBaseVersionDocument();
        member.setId(1L);
        member.setTenantId("tenant-a");
        member.setKnowledgeBaseVersionId(70L);
        member.setDocumentRevisionId(100L);
        member.setSourceId(10L);
        member.setExternalId("refund-policy");
        original = revision(100L, "历史正文");
        current = revision(101L, "新版正文，不应替代历史依据");
        document = new AiKnowledgeDocument();
        document.setId(20L);
        document.setTenantId("tenant-a");
        document.setKnowledgeBaseId(7L);
        document.setSourceId(10L);
        document.setExternalId("refund-policy");
        document.setCurrentRevisionId(101L);
        document.setDeleted(0);
        source = new AiKnowledgeSource();
        source.setId(10L);
        source.setTenantId("tenant-a");
        source.setKnowledgeBaseId(7L);
        source.setSourceName("售后政策源");
        source.setStatus(1);
        when(bases.selectOne(any())).thenReturn(base);
        when(versions.selectById(70L)).thenReturn(version);
        when(members.selectOne(any())).thenReturn(member);
        when(members.selectPage(any(Page.class), any())).thenAnswer(call -> {
            Page<AiKnowledgeBaseVersionDocument> page = call.getArgument(0);
            assertEquals(20, page.getSize());
            return page.setTotal(1).setRecords(List.of(member));
        });
        when(revisions.selectBatchIds(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().map(Map.of(100L, original, 101L, current)::get).toList();
        });
        when(documents.selectBatchIds(any())).thenReturn(List.of(document));
        when(sources.selectBatchIds(any())).thenReturn(List.of(source));
    }

    @AfterEach
    void cleanTenant() {
        TenantContext.clear();
    }

    @Test
    void previewReturnsExactHistoricalBodyAndVersionWithoutPromotingLatest() {
        var result = service.preview(7L, 70L, 100L, identity);
        assertEquals("历史正文", result.content());
        assertEquals(3, result.document().versionNo());
        assertEquals("source-v100", result.document().sourceVersion());
        assertEquals("hash-100", result.document().contentHash());
        assertFalse(result.document().currentRevision());
        document.setCurrentRevisionId(100L);
        assertTrue(service.preview(7L, 70L, 100L, identity).document().currentRevision());
        verify(revisions, never()).selectById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "version", "member", "original", "current", "document", "source"})
    void everyCrossTenantAssociationIsRejected(String row) {
        switch (row) {
            case "base" -> when(bases.selectOne(any())).thenReturn(null);
            case "version" -> version.setTenantId("tenant-b");
            case "member" -> member.setTenantId("tenant-b");
            case "original" -> original.setTenantId("tenant-b");
            case "current" -> current.setTenantId("tenant-b");
            case "document" -> document.setTenantId("tenant-b");
            case "source" -> source.setTenantId("tenant-b");
            default -> throw new AssertionError(row);
        }
        assertFailure(ResultCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"version-base", "member-version", "original-source", "document-base",
        "document-source", "document-external", "source-base", "current-document"})
    void mismatchedVersionMembershipCannotSelectUnrelatedBody(String relation) {
        switch (relation) {
            case "version-base" -> version.setKnowledgeBaseId(8L);
            case "member-version" -> member.setKnowledgeBaseVersionId(71L);
            case "original-source" -> original.setSourceId(11L);
            case "document-base" -> document.setKnowledgeBaseId(8L);
            case "document-source" -> document.setSourceId(11L);
            case "document-external" -> document.setExternalId("another-policy");
            case "source-base" -> source.setKnowledgeBaseId(8L);
            case "current-document" -> current.setDocumentId(21L);
            default -> throw new AssertionError(relation);
        }
        assertFailure(ResultCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "source-disabled", "tombstone", "body-purged", "member-missing"})
    void withdrawnOrUnavailableSourcesNeverFallBackToLatest(String state) {
        switch (state) {
            case "deleted" -> document.setDeleted(1);
            case "source-disabled" -> source.setStatus(0);
            case "tombstone" -> current.setOperation("DELETE");
            case "body-purged" -> original.setContent(null);
            case "member-missing" -> when(members.selectOne(any())).thenReturn(null);
            default -> throw new AssertionError(state);
        }
        assertFailure(ResultCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"historical", "current", "wrong-type", "wrong-channel", "malformed"})
    void bothHistoricalAndCurrentAclMustAllowTheTrustedIdentity(String rule) {
        AiKnowledgeDocumentRevision target = "historical".equals(rule) ? original : current;
        target.setAclMode("RESTRICTED");
        target.setAllowedSubjectIds("[\"someone-else\"]");
        switch (rule) {
            case "wrong-type" -> {
                target.setAllowedSubjectIds("[]");
                target.setAllowedSubjectTypes("USER");
            }
            case "wrong-channel" -> {
                target.setAllowedSubjectIds("[\"42\"]");
                target.setAllowedChannels("[\"user-http\"]");
            }
            case "malformed" -> target.setAllowedSubjectIds("broken-json");
            default -> { }
        }
        assertFailure(ResultCode.FORBIDDEN);
        var row = service.documents(7L, 70L, 1, identity).getList().get(0);
        assertEquals(KnowledgePreviewStatus.FORBIDDEN, row.status());
        assertNull(row.title());
        assertNull(row.sourceUri());
        assertNull(row.contentHash());
    }

    @Test
    void allowedRestrictedIdentityCanReadButClientCannotChooseAnotherRevision() {
        current.setAclMode("RESTRICTED");
        current.setAllowedSubjectTypes("ADMIN_USER");
        current.setAllowedSubjectIds("[\"42\"]");
        current.setAllowedChannels("[\"admin\"]");
        assertEquals("历史正文", service.preview(7L, 70L, 100L, identity).content());
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, assertThrows(BizException.class,
            () -> service.preview(7L, 70L, 101L, identity)).getResultCode());
    }

    @Test
    void identityMismatchIsRejectedBeforeDataAccess() {
        var otherTenant = new AgentInvocationIdentity("tenant-b", QuotaSubjectType.ADMIN_USER, "42", true);
        assertEquals(ResultCode.FORBIDDEN, assertThrows(BizException.class,
            () -> service.preview(7L, 70L, 100L, otherTenant)).getResultCode());
        assertEquals(ResultCode.FORBIDDEN, assertThrows(BizException.class,
            () -> service.preview(7L, 70L, 100L, null)).getResultCode());
        verifyNoInteractions(bases, versions, members, revisions, documents, sources);
    }

    @Test
    void metadataHasNoBodyAndDependencyFailureRemainsAnError() throws Exception {
        var page = service.documents(7L, 70L, 1, identity);
        assertEquals(1, page.getTotal());
        assertFalse(new ObjectMapper().writeValueAsString(page).contains("历史正文"));
        doThrow(new IllegalStateException("database unavailable")).when(revisions).selectBatchIds(any());
        assertThrows(IllegalStateException.class, () -> service.documents(7L, 70L, 1, identity));
    }

    private void assertFailure(ResultCode code) {
        assertEquals(code, assertThrows(BizException.class,
            () -> service.preview(7L, 70L, 100L, identity)).getResultCode());
    }

    private AiKnowledgeDocumentRevision revision(Long id, String content) {
        var row = new AiKnowledgeDocumentRevision();
        row.setId(id);
        row.setTenantId("tenant-a");
        row.setDocumentId(20L);
        row.setSourceId(10L);
        row.setOperation("UPSERT");
        row.setTitle("退款说明");
        row.setSourceVersion("source-v" + id);
        row.setContentHash("hash-" + id);
        row.setContent(content);
        row.setAclMode("PUBLIC");
        return row;
    }
}
