package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.attachment.AttachmentParser;
import com.richard.fyoung.customerwork.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.attachment.ExcelMarkdownParser;
import com.richard.fyoung.customerwork.attachment.TextAttachmentParser;
import com.richard.fyoung.customerwork.attachment.TikaDocumentParser;
import com.richard.fyoung.customerwork.attachment.VisionOcrParser;
import com.richard.fyoung.customerwork.attachment.VisionOcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    private ChatAttachmentService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mapper = mock(AiChatAttachmentMapper.class);
        AdminChatAttachmentStore store = new AdminChatAttachmentStore(mapper);

        AttachmentProperties properties = new AttachmentProperties();
        properties.setBaseDir(tempDir.toString());

        // 图片 OCR 用假实现，避免依赖真实视觉模型/网络（本用例不覆盖图片路径，仅保证解析器齐全）
        VisionOcrService fakeOcr = (bytes, mime) -> "fake-ocr-text";
        List<AttachmentParser> parsers = List.of(
            new TextAttachmentParser(),
            new ExcelMarkdownParser(),
            new TikaDocumentParser(),
            new VisionOcrParser(fakeOcr));
        AttachmentFileStorage fileStorage = new AttachmentFileStorage(properties.getBaseDir());
        AttachmentParseService parseService = new AttachmentParseService(parsers, store, fileStorage, properties);

        service = new ChatAttachmentService(parseService, store);
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
}
