package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParser;
import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.data.attachment.ExcelMarkdownParser;
import com.richard.fyoung.customerwork.data.attachment.TextAttachmentParser;
import com.richard.fyoung.customerwork.data.attachment.TikaDocumentParser;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrParser;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAttachmentService} 单测：委托 starter {@link AttachmentParseService} 的多格式解析落库链路。
 *
 * <p>用真实解析管道（文本 / Excel / Tika / 图片 OCR 假实现）+ 临时落盘目录 + Mock 的
 * {@link AiChatAttachmentMapper}（不依赖真库，与仓库现有 Service 单测同款）验证：txt 正常解析落库、
 * 白名单拒绝、解析失败落 FAILED、前端 DTO 契约字段、以及 agent_code 经 store 线程上下文落库。</p>
 * @author owlzhangfq@gmail.com
 */
class ChatAttachmentServiceTest {

    private static final String AGENT_CODE = "demo-agent";
    private static final String CHANNEL = "admin_chat";

    private AiChatAttachmentMapper mapper;
    private AttachmentFileStorage fileStorage;
    private ChatAttachmentService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mapper = mock(AiChatAttachmentMapper.class);
        AdminChatAttachmentStore store = new AdminChatAttachmentStore(mapper);

        AttachmentProperties properties = new AttachmentProperties();

        // 图片 OCR 用假实现，避免依赖真实视觉模型/网络（本用例不覆盖图片路径，仅保证解析器齐全）
        VisionOcrService fakeOcr = (bytes, mime) -> "fake-ocr-text";
        List<AttachmentParser> parsers = List.of(
            new TextAttachmentParser(),
            new ExcelMarkdownParser(),
            new TikaDocumentParser(),
            new VisionOcrParser(fakeOcr));
        // 文件存储只剩 MinIO 一种实现，单测用内存替身（本类验证的是附件编排，不是对象存储往返）
        fileStorage = new InMemoryTestFileStorage();
        AttachmentParseService parseService = new AttachmentParseService(parsers, store, fileStorage, properties);

        service = new ChatAttachmentService(parseService, store, fileStorage);
    }

    @Test
    void parseAttachment_shouldParseAndPersist_forTxtFile() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
            "纯文本内容".getBytes(StandardCharsets.UTF_8));

        ChatAttachmentDTO dto = service.parseAttachment(file, CHANNEL, "session-1", AGENT_CODE);

        // —— 前端契约字段（id/fileName/content/parseStatus/errorMessage）——
        assertNotNull(dto.id());
        assertEquals("notes.txt", dto.fileName());
        assertEquals("纯文本内容", dto.content());
        assertEquals("SUCCESS", dto.parseStatus());
        assertNull(dto.errorMessage());

        // —— 落库：agent_code 经线程上下文补写、解析文本与渠道正确写入 ——
        ArgumentCaptor<AiChatAttachment> captor = ArgumentCaptor.forClass(AiChatAttachment.class);
        verify(mapper).insert(captor.capture());
        AiChatAttachment saved = captor.getValue();
        assertEquals(AGENT_CODE, saved.getAgentCode());
        assertEquals(CHANNEL, saved.getChannel());
        assertEquals("notes.txt", saved.getFileName());
        assertEquals("session-1", saved.getSessionId());
        assertEquals("SUCCESS", saved.getParseStatus());
        assertEquals("纯文本内容", saved.getParsedText());
    }

    @Test
    void parseAttachment_shouldReject_whenExtensionNotWhitelisted() {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream",
            "MZ".getBytes(StandardCharsets.UTF_8));

        assertThrows(BizException.class,
            () -> service.parseAttachment(file, CHANNEL, null, AGENT_CODE));
    }

    @Test
    void parseAttachment_shouldReject_whenFileEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThrows(BizException.class,
            () -> service.parseAttachment(file, CHANNEL, null, AGENT_CODE));
    }

    @Test
    void parseAttachment_shouldPersistFailedRecord_whenParseFails() {
        // .xlsx 白名单内但字节非法（非 OOXML），走 ExcelMarkdownParser 解析必抛异常 → 落 FAILED
        MockMultipartFile file = new MockMultipartFile("file", "broken.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "not-a-real-xlsx".getBytes(StandardCharsets.UTF_8));

        ChatAttachmentDTO dto = service.parseAttachment(file, CHANNEL, null, AGENT_CODE);

        assertEquals("FAILED", dto.parseStatus());
        assertEquals("", dto.content());
        assertNotNull(dto.errorMessage());

        // 解析失败仍落库（可追溯）：parse_status=FAILED、agent_code 照写
        ArgumentCaptor<AiChatAttachment> captor = ArgumentCaptor.forClass(AiChatAttachment.class);
        verify(mapper).insert(captor.capture());
        AiChatAttachment saved = captor.getValue();
        assertEquals("FAILED", saved.getParseStatus());
        assertEquals(AGENT_CODE, saved.getAgentCode());
        assertTrue(saved.getParsedText() == null || saved.getParsedText().isEmpty());
        assertNotNull(saved.getErrorMessage());
    }

    @Test
    void parseAttachment_shouldExposeMimeTypeAndFileSize() {
        byte[] bytes = "纯文本内容".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", bytes);

        ChatAttachmentDTO dto = service.parseAttachment(file, CHANNEL, "session-1", AGENT_CODE);

        // 前端契约新增字段：mimeType 按扩展名推断、fileSize 为真实字节数
        assertEquals("text/plain", dto.mimeType());
        assertEquals(bytes.length, dto.fileSize());
    }

    // ===== 附件详情 / 原文件读取 / 消息绑定 =====

    @Test
    void getDetail_shouldReturnDto_whenOwnedByAgent() {
        AiChatAttachment entity = new AiChatAttachment();
        entity.setId("att-1");
        entity.setAgentCode(AGENT_CODE);
        entity.setFileName("spec.pdf");
        entity.setMimeType("application/pdf");
        entity.setFileSize(2048L);
        entity.setParseStatus("SUCCESS");
        entity.setParsedText("解析文本");
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        ChatAttachmentDTO dto = service.getDetail(AGENT_CODE, "att-1");

        assertEquals("att-1", dto.id());
        assertEquals("spec.pdf", dto.fileName());
        assertEquals("解析文本", dto.content());
        assertEquals("application/pdf", dto.mimeType());
        assertEquals(2048L, dto.fileSize());
    }

    @Test
    void getDetail_shouldThrow_whenNotFoundOrCrossAgent() {
        // 附件不存在 / agent_code 不匹配：findByIdAndAgentCode 查不到 → 业务异常
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThrows(BizException.class, () -> service.getDetail("other-agent", "att-x"));
    }

    @Test
    void loadFile_shouldReadOriginalBytes_whenOwnedByAgent() throws IOException {
        byte[] payload = "原始文件字节".getBytes(StandardCharsets.UTF_8);
        String key = fileStorage.store(payload, "att-2", "txt");

        AiChatAttachment entity = new AiChatAttachment();
        entity.setId("att-2");
        entity.setAgentCode(AGENT_CODE);
        entity.setFileName("原始 文件.txt");
        entity.setMimeType("text/plain");
        entity.setStoragePath(key);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        ChatAttachmentService.LoadedFile loaded = service.loadFile(AGENT_CODE, "att-2");

        assertArrayEquals(payload, loaded.bytes());
        assertEquals("text/plain", loaded.mimeType());
        assertEquals("原始 文件.txt", loaded.fileName());
    }

    @Test
    void loadFile_shouldDefaultMime_whenMimeBlank() throws IOException {
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        String key = fileStorage.store(payload, "att-3", "bin");
        AiChatAttachment entity = new AiChatAttachment();
        entity.setId("att-3");
        entity.setAgentCode(AGENT_CODE);
        entity.setFileName("blob.bin");
        entity.setMimeType("");
        entity.setStoragePath(key);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        ChatAttachmentService.LoadedFile loaded = service.loadFile(AGENT_CODE, "att-3");

        assertEquals("application/octet-stream", loaded.mimeType());
    }

    @Test
    void loadFile_shouldThrow_whenNotFound() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThrows(BizException.class, () -> service.loadFile(AGENT_CODE, "missing"));
    }

    @Test
    void bindToMessage_shouldShortCircuit_whenIdsEmpty() {
        service.bindToMessage(AGENT_CODE, "s1", "m1", List.of());

        // 空列表短路：不触发任何 UPDATE
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void bindToMessage_shouldDelegateUpdate_whenIdsPresent() {
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);

        service.bindToMessage(AGENT_CODE, "s1", "m1", List.of("a1", "a2"));

        verify(mapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void bindToMessage_shouldNotThrow_whenPersistenceFails() {
        // 持久化异常被吞（只 log），绝不打断对话主流程
        when(mapper.update(isNull(), any(UpdateWrapper.class)))
            .thenThrow(new RuntimeException("db down"));

        service.bindToMessage(AGENT_CODE, "s1", "m1", List.of("a1"));
        // 无异常抛出即通过
    }

    /**
     * 进程内附件文件存储替身：key 规则与 MinIO 实现一致（{@code {yyyyMM}/{id}.{ext}}）。
     * starter 的同名替身在其 test 源集里，admin 取不到，故此处内联一份。
     */
    private static class InMemoryTestFileStorage implements AttachmentFileStorage {
        private final java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String store(byte[] data, String id, String ext) {
            String fileName = (ext == null || ext.isEmpty()) ? id : id + "." + ext;
            String key = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "/" + fileName;
            objects.put(key, data);
            return key;
        }

        @Override
        public byte[] read(String storagePath) throws java.io.IOException {
            byte[] data = objects.get(storagePath);
            if (data == null) {
                throw new java.io.IOException("attachment object not found: " + storagePath);
            }
            return data;
        }

        @Override
        public void delete(String storagePath) {
            objects.remove(storagePath);
        }
    }
}
