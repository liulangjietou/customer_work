package com.richard.fyoung.customerwork.attachment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本解析器单测：UTF-8 直读与扩展名支持判定。
 * @author owlzhangfq@gmail.com
 */
class TextAttachmentParserTest {

    private final TextAttachmentParser parser = new TextAttachmentParser();

    @Test
    void supports_shouldMatchTextExtensionsOnly() {
        assertTrue(parser.supports("md", "text/markdown"));
        assertTrue(parser.supports("txt", "text/plain"));
        assertTrue(parser.supports("csv", "text/csv"));
        assertTrue(parser.supports("json", "application/json"));
        // 内置宽泛文本清单：数据/配置/日志/源码类
        assertTrue(parser.supports("sql", "application/sql"));
        assertTrue(parser.supports("yaml", "text/yaml"));
        assertTrue(parser.supports("log", "text/plain"));
        assertTrue(parser.supports("java", "text/x-java-source"));
        assertTrue(parser.supports("py", "text/x-python"));
        assertFalse(parser.supports("pdf", "application/pdf"));
        assertFalse(parser.supports("png", "image/png"));
        // html 交给 Tika 剥标签，不走直读
        assertFalse(parser.supports("html", "text/html"));
    }

    @Test
    void supports_shouldAcceptConfiguredExtraExtensions() {
        TextAttachmentParser withExtra = new TextAttachmentParser(java.util.List.of(" ADoc ", "custom"));
        // 配置追加：大小写不敏感、去空白
        assertTrue(withExtra.supports("adoc", "text/plain"));
        assertTrue(withExtra.supports("custom", "text/plain"));
        assertFalse(parser.supports("adoc", "text/plain"));
    }

    @Test
    void parse_shouldReadUtf8Text() throws Exception {
        String raw = "你好，附件解析\nline2";
        ParsedContent content = parser.parse(raw.getBytes(StandardCharsets.UTF_8), "note.txt", "txt", "text/plain");
        assertEquals(raw, content.text());
    }
}
