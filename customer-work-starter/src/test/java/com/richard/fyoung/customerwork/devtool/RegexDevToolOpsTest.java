package com.richard.fyoung.customerwork.devtool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RegexDevToolOps} 单测：匹配计数/分组、flags、截断保护与非法输入。
 * @author owlzhangfq@gmail.com
 */
class RegexDevToolOpsTest {

    private final RegexDevToolOps ops = new RegexDevToolOps();

    @Test
    void test_shouldCountAndCaptureMatches() {
        RegexDevToolOps.RegexResult r = ops.test("\\d+", "a1b22c333", null);
        assertEquals(3, r.getMatchCount());
        assertFalse(r.isTruncated());
        assertEquals("1", r.getMatches().get(0).getValue());
        assertEquals("22", r.getMatches().get(1).getValue());
        assertEquals("333", r.getMatches().get(2).getValue());
        assertEquals(1, r.getMatches().get(0).getStart());
    }

    @Test
    void test_shouldReturnCaptureGroups() {
        RegexDevToolOps.RegexResult r = ops.test("(\\w)(\\d)", "a1b2", null);
        assertEquals(2, r.getMatchCount());
        assertEquals(2, r.getMatches().get(0).getGroups().size());
        assertEquals("a", r.getMatches().get(0).getGroups().get(0));
        assertEquals("1", r.getMatches().get(0).getGroups().get(1));
    }

    @Test
    void test_shouldApplyCaseInsensitiveFlag() {
        RegexDevToolOps.RegexResult r = ops.test("abc", "ABC", "i");
        assertEquals(1, r.getMatchCount());
    }

    @Test
    void test_shouldTruncateAtOneHundredMatches() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append('a');
        }
        RegexDevToolOps.RegexResult r = ops.test(".", sb.toString(), null);
        assertEquals(150, r.getMatchCount());
        assertTrue(r.isTruncated());
        assertEquals(100, r.getMatches().size());
    }

    @Test
    void test_shouldRejectInvalidPattern() {
        assertThrows(IllegalArgumentException.class, () -> ops.test("(unclosed", "x", null));
    }

    @Test
    void test_shouldRejectUnsupportedFlag() {
        assertThrows(IllegalArgumentException.class, () -> ops.test("a", "a", "x"));
    }

    @Test
    void test_shouldRejectBlankPattern() {
        assertThrows(IllegalArgumentException.class, () -> ops.test("  ", "text", null));
    }
}
