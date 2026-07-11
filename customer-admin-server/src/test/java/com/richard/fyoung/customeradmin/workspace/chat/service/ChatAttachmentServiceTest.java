package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChatAttachmentService} 单测：.md/.txt 正常解析、扩展名/大小/空文件校验。
 * @author owlzhangfq@gmail.com
 */
class ChatAttachmentServiceTest {

    private final ChatAttachmentService service = new ChatAttachmentService();

    @Test
    void parseAttachment_shouldReturnTextContent_forMdFile() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown",
            "# 标题\n正文内容".getBytes(StandardCharsets.UTF_8));

        String content = service.parseAttachment(file);

        assertEquals("# 标题\n正文内容", content);
    }

    @Test
    void parseAttachment_shouldReturnTextContent_forTxtFile() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
            "纯文本内容".getBytes(StandardCharsets.UTF_8));

        String content = service.parseAttachment(file);

        assertEquals("纯文本内容", content);
    }

    @Test
    void parseAttachment_shouldReject_whenExtensionUnsupported() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf",
            "abc".getBytes(StandardCharsets.UTF_8));

        assertThrows(BizException.class, () -> service.parseAttachment(file));
    }

    @Test
    void parseAttachment_shouldReject_whenFileEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", new byte[0]);

        assertThrows(BizException.class, () -> service.parseAttachment(file));
    }

    @Test
    void parseAttachment_shouldReject_whenFileTooLarge() {
        byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", tooLarge);

        assertThrows(BizException.class, () -> service.parseAttachment(file));
    }
}
