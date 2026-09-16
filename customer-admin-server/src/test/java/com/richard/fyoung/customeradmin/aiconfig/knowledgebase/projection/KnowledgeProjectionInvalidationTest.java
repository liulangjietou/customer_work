package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSyncRun;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSourceMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeSyncRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeDocumentIndexer;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceSyncCoordinator;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSyncRunRecorder;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原测试只模拟 Admin 写成功，未注入客服库封锁失败，因而无法发现权限两边失步。 */
class KnowledgeProjectionInvalidationTest {
    private AnnotationConfigApplicationContext context;
    private AiKnowledgeBaseMapper bases;
    private AiKnowledgeSourceMapper sources;
    private AiKnowledgeDocumentMapper documents;
    private AiKnowledgeDocumentRevisionMapper revisions;
    private final IllegalStateException unavailable = new IllegalStateException("customer database fence unavailable");

    @BeforeEach
    void setUp() {
        for (Class<?> entity : List.of(AiKnowledgeBase.class, AiKnowledgeSource.class,
            AiKnowledgeDocument.class, AiKnowledgeSyncRun.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), entity);
        }
        context = new AnnotationConfigApplicationContext();
        // 只扫描投影权限边界，服务用真实构造注入；跨库连接和 Admin SQL 是本单测的故障注入边界。
        var scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*KnowledgeProjectionAccessGuard")));
        scanner.scan(getClass().getPackageName());
        bases = bean(AiKnowledgeBaseMapper.class);
        sources = bean(AiKnowledgeSourceMapper.class);
        documents = bean(AiKnowledgeDocumentMapper.class);
        revisions = bean(AiKnowledgeDocumentRevisionMapper.class);
        bean(AiKnowledgeDocumentChunkMapper.class);
        bean(AiAgentKnowledgeBaseMapper.class);
        var runs = bean(AiKnowledgeSyncRunMapper.class);
        var versionService = bean(KnowledgeBaseVersionService.class);
        bean(KnowledgeSearchClient.class);
        bean(KnowledgeDocumentIndexer.class);
        bean(KnowledgeSyncRunRecorder.class);
        var provider = bean(KnowledgeProjectionGatewayProvider.class);
        when(provider.get()).thenThrow(unavailable);
        context.registerBean(AesGcmCryptoUtil.class, () -> new AesGcmCryptoUtil("0123456789abcdef"));
        context.registerBean(AdminRagProperties.class, AdminRagProperties::new);
        context.register(KnowledgeBaseService.class, KnowledgeSourceService.class, KnowledgeSourceSyncCoordinator.class);
        context.refresh();
        AiKnowledgeBase base = new AiKnowledgeBase();
        base.setId(7L);
        base.setStatus(1);
        base.setDeleted(0);
        when(bases.selectById(7L)).thenReturn(base);
        when(bases.selectByIdForUpdate(7L)).thenReturn(base);
        when(bases.selectOne(any())).thenReturn(base);
        AiKnowledgeSource source = new AiKnowledgeSource();
        source.setId(8L);
        source.setKnowledgeBaseId(7L);
        source.setSourceCode("policy");
        source.setSourceType("PUSH");
        source.setStatus(1);
        source.setCurrentCheckpoint("cp-0");
        when(sources.selectOne(any())).thenReturn(source);
        AiKnowledgeSyncRun run = new AiKnowledgeSyncRun();
        run.setId(11L);
        run.setSourceId(8L);
        run.setStatus("PROCESSING");
        when(runs.selectOne(any())).thenReturn(run);
        AiKnowledgeBaseVersion version = new AiKnowledgeBaseVersion();
        version.setId(70L);
        when(versionService.createDocumentSnapshotVersion(any(), any(), any(), any(), any(), any()))
            .thenReturn(version);
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void baseDisableMustFailBeforeAdminWriteWhenCustomerFenceCannotCommit() {
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeBaseService.class).updateStatus(7L, 0)));
        verify(bases, never()).updateById(any(AiKnowledgeBase.class));
    }

    @Test
    void sourceDisableMustFailBeforeAdminWriteWhenCustomerFenceCannotCommit() {
        var request = new KnowledgeSourceSaveRequest("policy", "Policy", "PUSH", 0, null, null, null);
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeSourceService.class).update(7L, 8L, request)));
        verify(sources, never()).updateById(any(AiKnowledgeSource.class));
    }

    @Test
    void sourceSnapshotMustFailBeforeAdvancingCheckpointWhenCustomerFenceCannotCommit() {
        var request = new KnowledgeSyncRequest("sync-1", "cp-0", "cp-1", true, 0, List.of());
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeSourceSyncCoordinator.class).commit(7L, 8L, null, 11L, request, Map.of())));
        verify(sources, never()).updateById(any(AiKnowledgeSource.class));
        verify(documents, never()).updateById(any(AiKnowledgeDocument.class));
        verify(revisions, never()).insert(any(AiKnowledgeDocumentRevision.class));
    }

    @Test
    void baseDeletionCannotLeaveHistoricalProjectionPublic() {
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeBaseService.class).delete(7L)));
        verify(bases, never()).deleteById(7L);
    }

    @Test
    void sourceDeletionCannotLeaveHistoricalProjectionPublic() {
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeSourceService.class).delete(7L, 8L)));
        verify(sources, never()).deleteById(8L);
    }

    @Test
    void recreatedKnowledgeBaseCannotReviveOldProjectionWhenFenceFails() {
        var deleted = new AiKnowledgeBase();
        deleted.setId(7L);
        deleted.setDeleted(1);
        when(bases.selectDeletedByName("Policy")).thenReturn(deleted);
        when(bases.selectByIdForUpdate(7L)).thenReturn(deleted);
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeBaseService.class).create(baseRequest())));
        verify(bases, never()).reviveDeleted(7L);
    }

    @Test
    void editingKnowledgeBaseCannotBypassFenceThroughConfigurationSave() {
        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> context.getBean(KnowledgeBaseService.class).update(7L, baseRequest())));
        verify(bases, never()).updateById(any(AiKnowledgeBase.class));
    }

    @Test
    void invalidSourceStatusIsRejectedBeforeBlockingHistoricalProjection() {
        var request = new KnowledgeSourceSaveRequest("policy", "Policy", "PUSH", 99, null, null, null);
        assertSame(ResultCode.PARAM_INVALID, assertThrows(BizException.class,
            () -> context.getBean(KnowledgeSourceService.class).update(7L, 8L, request)).getResultCode());
        verify(sources, never()).updateById(any(AiKnowledgeSource.class));
    }

    private KnowledgeBaseSaveRequest baseRequest() {
        return new KnowledgeBaseSaveRequest("Policy", "https://example.invalid", "app", "dummy-test-key",
            null, null, null, null, 1, "test");
    }

    private <T> T bean(Class<T> type) {
        T instance = mock(type);
        context.registerBean(type, () -> instance);
        return instance;
    }
}
