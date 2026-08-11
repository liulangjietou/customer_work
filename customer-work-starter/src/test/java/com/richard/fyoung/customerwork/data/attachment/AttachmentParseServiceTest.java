package com.richard.fyoung.customerwork.data.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排入口单测（唯一防御点）：白名单拒绝 / 超限拒绝 / 解析成功落库 / 解析失败落 FAILED / 超长截断。
 * 全程离线：文本 / Excel 解析器 + 内存 Store + 临时目录落盘，不触模型与 MySQL。
 * @author owlzhangfq@gmail.com
 */
class AttachmentParseServiceTest {

    @TempDir
    Path tempDir;

    private AttachmentParseService service(AttachmentProperties props) {
        List<AttachmentParser> parsers = List.of(
            new TextAttachmentParser(props.getExtraTextExtensions()),
            new ExcelMarkdownParser(),
            new TikaDocumentParser());
        return new AttachmentParseService(parsers, new InMemoryAttachmentStore(),
            new LocalAttachmentFileStorage(tempDir.toString()), props);
    }

    private AttachmentProperties props() {
        AttachmentProperties p = new AttachmentProperties();
        p.setBaseDir(tempDir.toString());
        return p;
    }

    @Test
    void parseAndStore_shouldSucceedForText() {
        AttachmentParseService service = service(props());
        ChatAttachment att = service.parseAndStore("hello 附件".getBytes(StandardCharsets.UTF_8),
            "note.txt", "s1", "u1", "user_chat");
        assertEquals(AttachmentParseStatus.SUCCESS, att.getParseStatus());
        assertEquals("hello 附件", att.getParsedText());
        assertNull(att.getErrorMessage());
        assertNotNull(att.getStoragePath());
        assertEquals("txt", att.getExtension());
        assertEquals("user_chat", att.getChannel());
    }

    @Test
    void parseAndStore_shouldRejectNonWhitelistExtension() {
        AttachmentParseService service = service(props());
        assertThrows(IllegalArgumentException.class, () -> service.parseAndStore(
            new byte[]{1, 2, 3}, "virus.exe", "s1", "u1", "user_chat"));
    }

    @Test
    void parseAndStore_shouldSucceedForBuiltinBroadTextTypes() {
        // sql / yaml 等宽泛文本类型走直读（用户实测反馈补入的内置清单）
        AttachmentParseService service = service(props());
        ChatAttachment sql = service.parseAndStore("SELECT 1;".getBytes(StandardCharsets.UTF_8),
            "init.sql", "s1", "u1", "admin_chat");
        assertEquals(AttachmentParseStatus.SUCCESS, sql.getParseStatus());
        assertEquals("SELECT 1;", sql.getParsedText());
    }

    @Test
    void parseAndStore_shouldAcceptConfiguredExtraTextExtension() {
        AttachmentProperties p = props();
        p.setExtraTextExtensions(List.of("adoc"));
        AttachmentParseService service = service(p);
        ChatAttachment att = service.parseAndStore("= Title".getBytes(StandardCharsets.UTF_8),
            "doc.adoc", "s1", "u1", "admin_chat");
        assertEquals(AttachmentParseStatus.SUCCESS, att.getParseStatus());
        // 未配置的类型仍被唯一防御点拒绝
        assertThrows(IllegalArgumentException.class, () -> service(props()).parseAndStore(
            "= Title".getBytes(StandardCharsets.UTF_8), "doc.adoc", "s1", "u1", "admin_chat"));
    }

    @Test
    void parseAndStore_shouldRejectOversizeFile() {
        AttachmentProperties p = props();
        p.setMaxFileSizeMb(1);
        AttachmentParseService service = service(p);
        byte[] big = new byte[2 * 1024 * 1024];
        assertThrows(IllegalArgumentException.class, () -> service.parseAndStore(
            big, "big.txt", "s1", "u1", "user_chat"));
    }

    @Test
    void parseAndStore_shouldRecordFailedWhenParserThrows() {
        AttachmentParseService service = service(props());
        // 非法 xlsx 字节：POI 无法解析 → 落 FAILED（文件仍落盘，不抛 500）
        ChatAttachment att = service.parseAndStore("not a real xlsx".getBytes(StandardCharsets.UTF_8),
            "broken.xlsx", "s1", "u1", "admin_chat");
        assertEquals(AttachmentParseStatus.FAILED, att.getParseStatus());
        assertNotNull(att.getErrorMessage());
        assertNotNull(att.getStoragePath(), "解析失败也应已落盘");
    }

    @Test
    void parseAndStore_shouldTruncateOverlongText() {
        AttachmentProperties p = props();
        p.setMaxParsedChars(10);
        AttachmentParseService service = service(p);
        String longText = "0123456789ABCDEFGHIJ";
        ChatAttachment att = service.parseAndStore(longText.getBytes(StandardCharsets.UTF_8),
            "long.txt", "s1", "u1", "user_chat");
        assertEquals(AttachmentParseStatus.SUCCESS, att.getParseStatus());
        assertTrue(att.getParsedText().startsWith("0123456789"));
        assertTrue(att.getParsedText().contains("内容超长已截断"), "超长应追加截断提示");
    }

    @Test
    void parseAndStore_shouldRejectEmptyFile() {
        AttachmentParseService service = service(props());
        assertThrows(IllegalArgumentException.class, () -> service.parseAndStore(
            new byte[0], "empty.txt", "s1", "u1", "user_chat"));
    }
}
