package com.richard.fyoung.customeradmin.workspace.knowledge.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeChunker} 单测：Java 方法级切分、边界识别、非 Java 按大小切、空白输入。
 * @author owlzhangfq@gmail.com
 */
class CodeChunkerTest {

    @Test
    void blankContentYieldsNoChunks() {
        assertTrue(CodeChunker.chunk("Foo.java", "   \n  ", 1500).isEmpty());
        assertTrue(CodeChunker.chunk("Foo.java", null, 1500).isEmpty());
    }

    @Test
    void javaSplitsByMethodBoundaries() {
        String java = "package a;\n\n"
            + "public class Foo {\n"
            + "    private int x;\n\n"
            + "    public int add(int a, int b) {\n"
            + "        return a + b;\n"
            + "    }\n\n"
            + "    public String hello() {\n"
            + "        return \"hi\";\n"
            + "    }\n"
            + "}\n";
        List<CodeChunker.Chunk> chunks = CodeChunker.chunk("Foo.java", java, 1500);
        // 期望切出：类头段（含 Foo/字段） + add 方法段 + hello 方法段
        List<String> symbols = chunks.stream().map(CodeChunker.Chunk::symbol).toList();
        assertTrue(symbols.contains("add"), "should have add method chunk, got " + symbols);
        assertTrue(symbols.contains("hello"), "should have hello method chunk, got " + symbols);
        // 序号连续从 0 起
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void detectBoundaryRecognizesTypeAndMethod() {
        assertEquals("Foo", CodeChunker.detectBoundary("public class Foo {"));
        assertEquals("Bar", CodeChunker.detectBoundary("interface Bar {"));
        assertEquals("compute", CodeChunker.detectBoundary("    public int compute(int a) {"));
        assertEquals("Foo", CodeChunker.detectBoundary("    public Foo(int a) {"));
    }

    @Test
    void detectBoundaryIgnoresControlFlowAndAnnotations() {
        assertNull(CodeChunker.detectBoundary("if (x > 0) {"));
        assertNull(CodeChunker.detectBoundary("for (int i = 0; i < n; i++) {"));
        assertNull(CodeChunker.detectBoundary("} catch (Exception e) {"));
        assertNull(CodeChunker.detectBoundary("@Override"));
        assertNull(CodeChunker.detectBoundary("// comment"));
        assertNull(CodeChunker.detectBoundary("new Runnable() {"));
        assertNull(CodeChunker.detectBoundary("return a + b;"));
    }

    @Test
    void nonJavaFileSplitsBySize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("line-").append(i).append('\n');
        }
        List<CodeChunker.Chunk> chunks = CodeChunker.chunk("readme.md", sb.toString(), 300);
        assertTrue(chunks.size() > 1, "large non-java file should split into multiple size chunks");
        // 非 Java 无符号切分
        assertNull(chunks.get(0).symbol());
        // 每块不超过上限（含少量行尾余量）
        for (CodeChunker.Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= 300 + 40, "chunk too large: " + chunk.content().length());
        }
    }

    @Test
    void splitBySizeRespectsLimit() {
        List<String> pieces = CodeChunker.splitBySize("a\nb\nc\nd\ne\nf\n", 4);
        assertFalse(pieces.isEmpty());
        for (String piece : pieces) {
            assertTrue(piece.length() <= 4 + 2);
        }
    }
}
