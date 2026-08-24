package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain.KnowledgeQualityStatus;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseVersionServiceTest {

    private AiKnowledgeBaseMapper knowledgeBaseMapper;
    private AiKnowledgeBaseVersionMapper versionMapper;
    private AiKnowledgeBaseVersionDocumentMapper versionDocumentMapper;
    private KnowledgeBaseVersionService service;
    private AiKnowledgeBase knowledgeBase;

    @BeforeAll
    static void initTableInfo() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeBase.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
            AiKnowledgeBaseVersionDocument.class);
    }

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
        versionMapper = mock(AiKnowledgeBaseVersionMapper.class);
        versionDocumentMapper = mock(AiKnowledgeBaseVersionDocumentMapper.class);
        service = new KnowledgeBaseVersionService(
            knowledgeBaseMapper, versionMapper, versionDocumentMapper);
        knowledgeBase = knowledgeBase();
        when(knowledgeBaseMapper.selectOne(any(QueryWrapper.class))).thenReturn(knowledgeBase);
        when(versionMapper.insert(any(AiKnowledgeBaseVersion.class))).thenAnswer(invocation -> {
            invocation.<AiKnowledgeBaseVersion>getArgument(0).setId(500L);
            return 1;
        });
    }

    @Test
    void createDocumentSnapshotVersion_shouldFreezeSortedMembersAndAdvancePointer() {
        AiKnowledgeDocument second = document(2L, "doc-b", 202L);
        AiKnowledgeDocument first = document(1L, "doc-a", 101L);

        AiKnowledgeBaseVersion version = service.createDocumentSnapshotVersion(
            7L, "cp-2", new BigDecimal("0.9500"), KnowledgeQualityStatus.PASSED.name(),
            List.of(second, first), "sync");

        assertEquals(500L, version.getId());
        assertEquals(3, version.getVersionNo());
        assertEquals(2, version.getDocumentCount());
        assertEquals("cp-2", version.getCheckpoint());
        assertEquals(new BigDecimal("0.9500"), version.getQualityScore());
        assertFalse(version.getSnapshotHash().isBlank());
        ArgumentCaptor<AiKnowledgeBaseVersionDocument> memberCaptor =
            ArgumentCaptor.forClass(AiKnowledgeBaseVersionDocument.class);
        verify(versionDocumentMapper, times(2)).insert(memberCaptor.capture());
        List<AiKnowledgeBaseVersionDocument> frozen = memberCaptor.getAllValues();
        assertEquals(1L, frozen.get(0).getSourceId());
        assertEquals("doc-a", frozen.get(0).getExternalId());
        assertEquals(101L, frozen.get(0).getDocumentRevisionId());
        assertEquals(2L, frozen.get(1).getSourceId());
        assertEquals("doc-b", frozen.get(1).getExternalId());
        assertEquals(500L, frozen.get(1).getKnowledgeBaseVersionId());
        verify(knowledgeBaseMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void createConfigurationVersion_shouldCloneCurrentSnapshotMembers() {
        knowledgeBase.setCurrentVersionId(400L);
        knowledgeBase.setLatestVersionNo(4);
        AiKnowledgeBaseVersion previous = new AiKnowledgeBaseVersion();
        previous.setId(400L);
        previous.setCheckpoint("cp-4");
        previous.setQualityScore(new BigDecimal("0.9000"));
        previous.setQualityStatus(KnowledgeQualityStatus.PASSED.name());
        AiKnowledgeBaseVersionDocument member = new AiKnowledgeBaseVersionDocument();
        member.setDocumentRevisionId(301L);
        member.setSourceId(3L);
        member.setExternalId("doc-c");
        when(versionMapper.selectById(400L)).thenReturn(previous);
        when(versionDocumentMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(member));

        AiKnowledgeBaseVersion version = service.createConfigurationVersion(7L, "change topN");

        assertEquals(5, version.getVersionNo());
        assertEquals("cp-4", version.getCheckpoint());
        assertEquals(new BigDecimal("0.9000"), version.getQualityScore());
        assertEquals(1, version.getDocumentCount());
        ArgumentCaptor<AiKnowledgeBaseVersionDocument> memberCaptor =
            ArgumentCaptor.forClass(AiKnowledgeBaseVersionDocument.class);
        verify(versionDocumentMapper).insert(memberCaptor.capture());
        assertEquals(500L, memberCaptor.getValue().getKnowledgeBaseVersionId());
        assertEquals(301L, memberCaptor.getValue().getDocumentRevisionId());
        assertEquals("doc-c", memberCaptor.getValue().getExternalId());
    }

    private AiKnowledgeBase knowledgeBase() {
        AiKnowledgeBase value = new AiKnowledgeBase();
        value.setId(7L);
        value.setLatestVersionNo(2);
        value.setBaseUrl("https://rag.example.test");
        value.setAppId("app-1");
        value.setApiKey("encrypted-key");
        value.setContentType("application/json");
        value.setExtraHeaders("{}");
        value.setTopN(5);
        value.setScoreThreshold(new BigDecimal("0.2000"));
        return value;
    }

    private AiKnowledgeDocument document(Long sourceId, String externalId, Long revisionId) {
        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setSourceId(sourceId);
        document.setExternalId(externalId);
        document.setCurrentRevisionId(revisionId);
        return document;
    }
}
