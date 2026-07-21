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
}
