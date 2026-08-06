package com.richard.fyoung.customerwork.tool.devtool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonDevToolOps} 单测：格式化/压缩正常路径 + 坏 JSON 的行列号定位 + 参数非法。
 * @author owlzhangfq@gmail.com
 */
class JsonDevToolOpsTest {

    private final JsonDevToolOps ops = new JsonDevToolOps();

    @Test
    void format_shouldPrettyPrint_withTwoSpaceIndent() {
        String out = ops.format("{\"a\":1,\"b\":[2,3]}", 2);
        assertTrue(out.contains("\n"), "应包含换行");
        assertTrue(out.contains("  \"a\""), "应有 2 空格缩进");
    }

    @Test
    void format_shouldPrettyPrint_withFourSpaceIndent() {
        String out = ops.format("{\"a\":1}", 4);
        assertTrue(out.contains("    \"a\""), "应有 4 空格缩进");
    }

    @Test
    void format_shouldRejectIllegalIndent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ops.format("{}", 3));
        assertTrue(ex.getMessage().contains("indent"));
    }

    @Test
    void format_shouldThrowWithLineColumn_onBadJson() {
        // 第 1 行第 8 列附近缺值，Jackson 报错带行列号
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ops.format("{\"a\": }", 2));
        assertTrue(ex.getMessage().contains("line"), "错误信息应含 line");
        assertTrue(ex.getMessage().contains("column"), "错误信息应含 column");
    }

    @Test
    void minify_shouldRemoveWhitespace() {
        String out = ops.minify("{\n  \"a\": 1,\n  \"b\": 2\n}");
        assertEquals("{\"a\":1,\"b\":2}", out);
    }

    @Test
    void minify_shouldThrow_onBadJson() {
        assertThrows(IllegalArgumentException.class, () -> ops.minify("{bad"));
    }

    @Test
    void validate_shouldReturnValid_forGoodJson() {
        JsonDevToolOps.ValidationResult result = ops.validate("{\"a\":[1,2,3]}");
        assertTrue(result.isValid());
        assertNotNull(result);
        assertEquals(-1, result.getLine());
    }

    @Test
    void validate_shouldReportLineColumn_forBadJson() {
        JsonDevToolOps.ValidationResult result = ops.validate("{\"a\": }");
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getLine() >= 1, "行号应可用");
        assertTrue(result.getColumn() >= 1, "列号应可用");
    }

    @Test
    void anyOp_shouldRejectBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> ops.format("  ", 2));
        assertThrows(IllegalArgumentException.class, () -> ops.minify(null));
        assertThrows(IllegalArgumentException.class, () -> ops.validate(""));
    }

    // -------- 转义 / 去转义 / Unicode 解码（与页面版同语义） --------

    @Test
    void escape_shouldWrapWithQuotesAndEscapeSpecialChars() {
        assertEquals("\"{\\\"a\\\":1}\"", ops.escape("{\"a\":1}"));
        assertEquals("\"line1\\nline2\"", ops.escape("line1\nline2"));
    }

    /** 与页面版 JSON.stringify 一致：中文保持原样，不转成 Unicode 转义序列。 */
    @Test
    void escape_shouldKeepNonAsciiAsIs() {
        assertEquals("\"中文\"", ops.escape("中文"));
    }

    @Test
    void unescape_shouldAcceptWithOrWithoutOuterQuotes() {
        assertEquals("{\"a\":1}", ops.unescape("\"{\\\"a\\\":1}\""));
        assertEquals("{\"a\":1}", ops.unescape("{\\\"a\\\":1}"));
    }

    @Test
    void escapeUnescape_shouldRoundTrip() {
        String origin = "含\"引号\"、反斜杠\\ 与换行\n的原文";
        assertEquals(origin, ops.unescape(ops.escape(origin)));
    }

    /** 传入的是对象而非字符串字面量时应报错，而不是悄悄返回对象的文本形式。 */
    @Test
    void unescape_shouldRejectNonStringJson() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ops.unescape("{\"a\":1}"));
        assertTrue(ex.getMessage().contains("不是"));
    }

    @Test
    void unescape_shouldRejectBrokenEscape() {
        assertThrows(IllegalArgumentException.class, () -> ops.unescape("\"unclosed\\\""));
    }

    @Test
    void decodeUnicode_shouldRestoreChineseAndKeepOtherText() {
        assertEquals("中文 abc", ops.decodeUnicode("\\u4e2d\\u6587 abc"));
    }

    @Test
    void decodeUnicode_shouldRestoreSurrogatePair() {
        assertEquals("😀", ops.decodeUnicode("\\ud83d\\ude00"));
    }

    @Test
    void decodeUnicode_shouldLeaveTextWithoutEscapesUntouched() {
        assertEquals("plain text 中文", ops.decodeUnicode("plain text 中文"));
        assertEquals("", ops.decodeUnicode(""));
    }
}
