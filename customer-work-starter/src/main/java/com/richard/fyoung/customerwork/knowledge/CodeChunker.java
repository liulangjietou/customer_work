package com.richard.fyoung.customerwork.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分块器：把一个源码文件切成"类/方法级"分块，供 Embedding 与检索。纯函数，可离线单测。
 *
 * <p>降级取舍（不过度设计）：Java 文件按<b>类型声明 / 方法声明</b>做轻量边界切分（正则 + 简单启发式，
 * 不引入 AST 解析器）；非 Java 文件与超长分块按字符上限沿行边界切。切分粒度足够语义检索用即可，
 * 命中不到方法边界时自然回落为按大小切，不会漏内容。</p>
 * @author owlzhangfq@gmail.com
 */
public final class CodeChunker {

    private CodeChunker() {
    }

    /** 类型声明（class/interface/enum/record）边界，捕获类型名。 */
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");
    /** 合法 Java 标识符。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    /** 控制关键字：形似方法调用但不是方法声明，排除以免误判边界。 */
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
        "if", "for", "while", "switch", "catch", "try", "else", "do", "synchronized", "return", "throw");
    /** 头部段（package/import/类声明前）的符号名。 */
    private static final String HEADER_SYMBOL = "(header)";

    /** 一个分块：所属符号（类/方法名，可空）、同文件内序号、文本内容。 */
    public record Chunk(String symbol, int index, String content) {
    }

    /** 分段中间结构：符号 + 文本。 */
    private record Segment(String symbol, String text) {
    }

    /**
     * 把文件内容切成分块。空白内容返回空列表。
     *
     * @param relativePath 文件相对路径（据后缀判定是否走 Java 方法级切分）
     * @param content      文件文本
     * @param maxChars     单分块最大字符数
     */
    public static List<Chunk> chunk(String relativePath, String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int limit = Math.max(200, maxChars);
        List<Segment> segments = isJava(relativePath) ? javaSegments(content) : List.of(new Segment(null, content));

        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (Segment segment : segments) {
            for (String piece : splitBySize(segment.text(), limit)) {
                if (piece.isBlank()) {
                    continue;
                }
                chunks.add(new Chunk(segment.symbol(), index++, piece));
            }
        }
        return chunks;
    }

    private static boolean isJava(String relativePath) {
        return relativePath != null && relativePath.toLowerCase().endsWith(".java");
    }

    /**
     * Java 文件按类型/方法声明边界切段：首段为头部（package/import/类声明），此后每遇到一个声明边界
     * 起一个新段。未识别出任何边界时整体回落为单段（再由上层按大小切）。
     */
    static List<Segment> javaSegments(String content) {
        String[] lines = content.split("\n", -1);
        List<Segment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSymbol = HEADER_SYMBOL;
        for (String line : lines) {
            String boundarySymbol = detectBoundary(line);
            if (boundarySymbol != null && current.toString().isBlank()) {
                // 头部尚无实质内容：直接把当前段符号更新为该边界（避免产生空头部段）
                currentSymbol = boundarySymbol;
            } else if (boundarySymbol != null) {
                segments.add(new Segment(currentSymbol, current.toString()));
                current = new StringBuilder();
                currentSymbol = boundarySymbol;
            }
            current.append(line).append('\n');
        }
        if (!current.toString().isBlank()) {
            segments.add(new Segment(currentSymbol, current.toString()));
        }
        return segments.isEmpty() ? List.of(new Segment(null, content)) : segments;
    }

    /**
     * 判定一行是否为类型/方法声明边界，是则返回符号名，否则 null。轻量启发式：
     * 类型声明取 class/interface/enum/record 名；方法/构造声明要求行尾为 {@code {}、含 {@code (}、
     * {@code (} 前为合法标识符且非控制关键字、非 {@code new} 匿名类。
     */
    static String detectBoundary(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("@") || line.startsWith("//")
            || line.startsWith("*") || line.startsWith("/*")) {
            return null;
        }
        Matcher typeMatcher = TYPE_PATTERN.matcher(line);
        if (typeMatcher.find()) {
            return typeMatcher.group(2);
        }
        if (!line.endsWith("{")) {
            return null;
        }
        int paren = line.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String beforeParen = line.substring(0, paren).trim();
        String[] tokens = beforeParen.split("\\s+");
        if (tokens.length < 2) {
            return null;
        }
        String name = tokens[tokens.length - 1];
        String prev = tokens[tokens.length - 2];
        if (!IDENTIFIER.matcher(name).matches() || CONTROL_KEYWORDS.contains(name) || "new".equals(prev)) {
            return null;
        }
        return name;
    }

    /** 按字符上限沿行边界切分：尽量不切断行，单行超限时才硬切。 */
    static List<String> splitBySize(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= maxChars) {
            pieces.add(text);
            return pieces;
        }
        StringBuilder buffer = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.length() > maxChars) {
                // 单行本身超限：先冲掉缓冲，再把该行硬切成定长片段
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString());
                    buffer = new StringBuilder();
                }
                for (int i = 0; i < line.length(); i += maxChars) {
                    pieces.add(line.substring(i, Math.min(line.length(), i + maxChars)));
                }
                continue;
            }
            if (buffer.length() + line.length() + 1 > maxChars && buffer.length() > 0) {
                pieces.add(buffer.toString());
                buffer = new StringBuilder();
            }
            buffer.append(line).append('\n');
        }
        if (buffer.length() > 0) {
            pieces.add(buffer.toString());
        }
        return pieces;
    }
}
