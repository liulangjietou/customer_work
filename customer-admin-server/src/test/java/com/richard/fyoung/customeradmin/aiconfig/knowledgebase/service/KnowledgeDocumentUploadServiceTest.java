package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentChangeRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeSource;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customerwork.data.attachment.DocumentTextExtractor;
import com.richard.fyoung.customerwork.data.attachment.TextAttachmentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档上传入库。
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeDocumentUploadServiceTest {

    private KnowledgeSourceService sourceService;
    private KnowledgeSourceSyncService syncService;
    private KnowledgeDocumentUploadService service;

    @BeforeEach
    void setUp() {
        sourceService = mock(KnowledgeSourceService.class);
        syncService = mock(KnowledgeSourceSyncService.class);
        AiKnowledgeSource source = new AiKnowledgeSource();
        source.setId(8L);
        source.setCurrentCheckpoint("ck-100");
        when(sourceService.requireSource(eq(1L), eq(8L))).thenReturn(source);

        service = new KnowledgeDocumentUploadService(sourceService, syncService,
            new DocumentTextExtractor(List.of(new TextAttachmentParser())));
    }

    /**
     * 这条是本类里最要紧的一条。
     *
     * <p>{@code fullSnapshot=true} 的语义是「这批就是该源的全部内容，缺失的自动删除」。
     * 运营上传三个文件时若按全量提交，该源里其余文档会被<b>全部删掉</b>——
     * 而界面上看起来只是「传了三个文件，成功」。</p>
     */
    @Test
    @DisplayName("上传永远是增量，绝不能提交成全量快照")
    void neverSubmitsFullSnapshot() {
        service.upload(1L, 8L, List.of(file("a.txt", "甲条款")));

        assertFalse(Boolean.TRUE.equals(captureRequest().fullSnapshot()),
            "上传按全量快照提交，会把该文档源里这批之外的全部文档删掉");
    }

    @Test
    @DisplayName("提取正文并以文件名作为文档标识")
    void extractsContentAndUsesFileNameAsExternalId() {
        service.upload(1L, 8L, List.of(file("退货政策.txt", "签收后七天内可退")));

        KnowledgeSyncRequest request = captureRequest();
        assertEquals(1, request.documents().size());
        KnowledgeDocumentChangeRequest doc = request.documents().get(0);
        assertEquals("退货政策.txt", doc.externalId(), "同名文件再传应更新同一篇文档");
        assertEquals("签收后七天内可退", doc.content());
        assertEquals("UPSERT", doc.operation());
    }

    /**
     * checkpoint 必须以源当前值为 expected 做 CAS 推进，且两者不能相同
     * （底层 {@code validateRequest} 对此直接拒绝）。
     */
    @Test
    @DisplayName("checkpoint 取源当前值做 CAS，并推进到一个不同的新值")
    void advancesCheckpointFromCurrent() {
        service.upload(1L, 8L, List.of(file("a.txt", "内容")));

        KnowledgeSyncRequest request = captureRequest();
        assertEquals("ck-100", request.expectedCheckpoint());
        assertNotEquals(request.expectedCheckpoint(), request.checkpoint(),
            "checkpoint 与 expectedCheckpoint 相同会被底层直接拒绝");
    }

    /**
     * 同批重名要当场拒绝。
     *
     * <p>底层确实会因 externalId 重复报错，但那句错误里只有一个 id，
     * 运营看不出是自己选了两个同名文件。</p>
     */
    @Test
    @DisplayName("同一批里有重名文件时当场拒绝并点名")
    void rejectsDuplicateFileNames() {
        BizException e = assertThrows(BizException.class, () ->
            service.upload(1L, 8L, List.of(file("a.txt", "甲"), file("a.txt", "乙"))));

        assertTrue(e.getMessage().contains("a.txt"));
        verify(syncService, never()).sync(any(), any(), any());
    }

    /**
     * 一个文件坏掉就整批拒绝。
     *
     * <p>部分成功会让运营以为传完了，而少掉的那几篇要等用户问起来才发现。</p>
     */
    @Test
    @DisplayName("任一文件解析失败即整批拒绝，不做部分成功")
    void rejectsWholeBatchWhenOneFileFails() {
        assertThrows(BizException.class, () ->
            service.upload(1L, 8L, List.of(file("good.txt", "正常内容"), file("bad.exe", "x"))));

        verify(syncService, never()).sync(any(), any(), any());
    }

    @Test
    @DisplayName("同一批文件原样重传得到同一个 requestId，底层据此幂等")
    void sameFilesProduceSameRequestId() {
        service.upload(1L, 8L, List.of(file("a.txt", "内容甲")));
        String first = captureRequest().requestId();

        service.upload(1L, 8L, List.of(file("a.txt", "内容甲")));
        String again = lastRequest().requestId();
        assertEquals(first, again, "重传同样的文件应命中幂等，不该重复建一次同步批次");

        service.upload(1L, 8L, List.of(file("a.txt", "内容乙")));
        assertNotEquals(first, lastRequest().requestId(), "内容变了就是新的一批，不该被幂等吃掉");
    }

    @Test
    @DisplayName("浏览器带上路径时只取文件名，目录不进文档标识")
    void stripsPathFromFileName() {
        service.upload(1L, 8L, List.of(
            new MockMultipartFile("files", "C:\\docs\\制度.txt", "text/plain",
                "内容".getBytes(StandardCharsets.UTF_8))));

        assertEquals("制度.txt", captureRequest().documents().get(0).externalId());
    }

    @Test
    @DisplayName("空文件列表与超量上传都被挡在链路之外")
    void guardsBatchSize() {
        assertThrows(BizException.class, () -> service.upload(1L, 8L, List.of()));
        assertThrows(BizException.class, () -> service.upload(1L, 8L, null));

        List<MultipartFile> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(file("f" + i + ".txt", "内容" + i));
        }
        assertThrows(BizException.class, () -> service.upload(1L, 8L, tooMany));
        verify(syncService, never()).sync(any(), any(), any());
    }

    private MultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
            content.getBytes(StandardCharsets.UTF_8));
    }

    private KnowledgeSyncRequest captureRequest() {
        ArgumentCaptor<KnowledgeSyncRequest> captor = ArgumentCaptor.forClass(KnowledgeSyncRequest.class);
        verify(syncService).sync(eq(1L), eq(8L), captor.capture());
        return captor.getValue();
    }

    private KnowledgeSyncRequest lastRequest() {
        ArgumentCaptor<KnowledgeSyncRequest> captor = ArgumentCaptor.forClass(KnowledgeSyncRequest.class);
        verify(syncService, org.mockito.Mockito.atLeastOnce()).sync(eq(1L), eq(8L), captor.capture());
        return captor.getValue();
    }
}
