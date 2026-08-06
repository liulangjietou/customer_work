package com.richard.fyoung.customerwork.tool.devtool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiffDevToolOps} 单测：一致判定、增删识别、行号映射、忽略选项与行数上限。
 * @author owlzhangfq@gmail.com
 */
class DiffDevToolOpsTest {

    private final DiffDevToolOps ops = new DiffDevToolOps();

    @Test
    void diff_shouldReportIdentical() {
        DiffDevToolOps.DiffResult result = ops.diff("a\nb\nc", "a\nb\nc", null, null);
        assertTrue(result.isIdentical());
        assertEquals(0, result.getAddedLines());
        assertEquals(0, result.getDeletedLines());
        assertEquals(3, result.getLines().size());
        assertTrue(result.getLines().stream().allMatch(line -> "EQUAL".equals(line.getType())));
    }

    @Test
    void diff_shouldDetectInsertedLine() {
        DiffDevToolOps.DiffResult result = ops.diff("a\nc", "a\nb\nc", null, null);
        assertFalse(result.isIdentical());
        assertEquals(1, result.getAddedLines());
        assertEquals(0, result.getDeletedLines());
        DiffDevToolOps.DiffLine inserted = result.getLines().stream()
            .filter(line -> "INSERT".equals(line.getType())).findFirst().orElseThrow();
        assertEquals("b", inserted.getContent());
        assertEquals(-1, inserted.getOldLineNo(), "新增行在原文本中不存在，行号应为 -1");
        assertEquals(2, inserted.getNewLineNo());
    }

    @Test
    void diff_shouldDetectDeletedLine() {
        DiffDevToolOps.DiffResult result = ops.diff("a\nb\nc", "a\nc", null, null);
        assertEquals(0, result.getAddedLines());
        assertEquals(1, result.getDeletedLines());
        DiffDevToolOps.DiffLine deleted = result.getLines().stream()
            .filter(line -> "DELETE".equals(line.getType())).findFirst().orElseThrow();
        assertEquals("b", deleted.getContent());
        assertEquals(2, deleted.getOldLineNo());
        assertEquals(-1, deleted.getNewLineNo());
    }

    /** 同一位置的修改应呈现为"先删后增"，读起来即旧行改成了新行。 */
    @Test
    void diff_shouldOrderDeleteBeforeInsert_onModifiedLine() {
        DiffDevToolOps.DiffResult result = ops.diff("a\nold\nc", "a\nnew\nc", null, null);
        assertEquals(1, result.getAddedLines());
        assertEquals(1, result.getDeletedLines());
        List<String> types = result.getLines().stream()
            .map(DiffDevToolOps.DiffLine::getType).collect(Collectors.toList());
        assertEquals(List.of("EQUAL", "DELETE", "INSERT", "EQUAL"), types);
    }

    @Test
    void diff_shouldIgnoreWhitespace_whenRequested() {
        assertFalse(ops.diff("a\n  b  ", "a\nb", null, null).isIdentical());
        assertTrue(ops.diff("a\n  b  ", "a\nb", true, null).isIdentical());
    }

    @Test
    void diff_shouldIgnoreCase_whenRequested() {
        assertFalse(ops.diff("Hello", "hello", null, null).isIdentical());
        assertTrue(ops.diff("Hello", "hello", null, true).isIdentical());
    }

    /** 忽略选项只影响比较，展示内容必须保留原文。 */
    @Test
    void diff_shouldKeepOriginalContent_whenIgnoringWhitespace() {
        DiffDevToolOps.DiffResult result = ops.diff("  a  ", "a", true, null);
        assertEquals("  a  ", result.getLines().get(0).getContent());
    }

    @Test
    void diff_shouldHandleEmptyInputs() {
        DiffDevToolOps.DiffResult result = ops.diff("", "a\nb", null, null);
        assertEquals(2, result.getAddedLines());
        assertEquals(0, result.getDeletedLines());
        assertTrue(ops.diff("", "", null, null).isIdentical());
    }

    @Test
    void diff_shouldTreatCrlfAsLineBreak() {
        assertTrue(ops.diff("a\r\nb", "a\nb", null, null).isIdentical());
    }

    @Test
    void diff_shouldRejectTooManyLines() {
        String huge = "x\n".repeat(1501);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.diff(huge, "y", null, null));
        assertTrue(ex.getMessage().contains("1500"));
    }

    @Test
    void diff_shouldRejectNullInput() {
        assertThrows(IllegalArgumentException.class, () -> ops.diff(null, "a", null, null));
    }
}
