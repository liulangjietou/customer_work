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
        assertFalse(parser.supports("pdf", "application/pdf"));
        assertFalse(parser.supports("png", "image/png"));
    }

    @Test
    void parse_shouldReadUtf8Text() throws Exception {
        String raw = "你好，附件解析\nline2";
        ParsedContent content = parser.parse(raw.getBytes(StandardCharsets.UTF_8), "note.txt", "txt", "text/plain");
        assertEquals(raw, content.text());
    }
}
