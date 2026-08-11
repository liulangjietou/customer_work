package com.richard.fyoung.customerwork.devtool;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 文本比对纯函数集：行级 diff（基于最长公共子序列）。
 *
 * <p>典型用途是核对改配置前后、两个环境的 JSON/YAML 差异。为便于两端一致呈现，结果是结构化的
 * 行列表而非 unified diff 文本：每行带类型（相同/新增/删除）与两侧行号，页面直接渲染成左右对照，
 * 智能体也能逐行引用行号说明差异。</p>
 *
 * <p>无 Spring 依赖、无状态。LCS 是 O(n×m) 时空复杂度，故对行数设上限 fast-fail，避免大文件
 * 把内存打满——工具箱定位是"看配置差异"，不是通用 diff 引擎。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class DiffDevToolOps {

    /** 单侧行数上限（1500×1500 的 int 表约 9MB，足够覆盖配置文件比对场景）。 */
    private static final int MAX_LINES = 1500;

    /** 结果行数上限：差异过多时截断，防止撑爆 LLM 上下文与页面渲染。 */
    private static final int MAX_RESULT_LINES = 3000;

    /** 行号占位值（该侧不存在此行时）。 */
    private static final int NO_LINE = -1;

    /** 行类型。 */
    private static final String TYPE_EQUAL = "EQUAL";
    private static final String TYPE_INSERT = "INSERT";
    private static final String TYPE_DELETE = "DELETE";

    /**
     * 行级比对两段文本。
     *
     * @param oldText          原文本（允许空串）
     * @param newText          新文本（允许空串）
     * @param ignoreWhitespace 是否忽略行首尾空白差异，null 视为 false
     * @param ignoreCase       是否忽略大小写差异，null 视为 false
     * @return 比对结果
     */
    public DiffResult diff(String oldText, String newText, Boolean ignoreWhitespace, Boolean ignoreCase) {
        DevToolArgs.requireNonNull(oldText, "oldText");
        DevToolArgs.requireNonNull(newText, "newText");
        boolean trimSpace = Boolean.TRUE.equals(ignoreWhitespace);
        boolean foldCase = Boolean.TRUE.equals(ignoreCase);

        List<String> oldLines = splitLines(oldText, "oldText");
        List<String> newLines = splitLines(newText, "newText");
        // 比较用的归一化副本，展示仍用原文，避免"忽略空白"把用户的原始内容也改了
        List<String> oldKeys = normalizeAll(oldLines, trimSpace, foldCase);
        List<String> newKeys = normalizeAll(newLines, trimSpace, foldCase);

        List<DiffLine> lines = backtrack(buildLcsTable(oldKeys, newKeys), oldLines, newLines, oldKeys, newKeys);

        int added = 0;
        int deleted = 0;
        for (DiffLine line : lines) {
            if (TYPE_INSERT.equals(line.getType())) {
                added++;
            } else if (TYPE_DELETE.equals(line.getType())) {
                deleted++;
            }
        }
        boolean truncated = lines.size() > MAX_RESULT_LINES;
        return DiffResult.builder()
            .identical(added == 0 && deleted == 0)
            .addedLines(added)
            .deletedLines(deleted)
            .totalLines(lines.size())
            .truncated(truncated)
            .lines(truncated ? new ArrayList<>(lines.subList(0, MAX_RESULT_LINES)) : lines)
            .build();
    }

    /** 按行切分（兼容 \r\n / \r / \n），并做行数上限校验。空串视为 0 行。 */
    private List<String> splitLines(String text, String fieldName) {
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = text.split("\r\n|\r|\n", -1);
        if (parts.length > MAX_LINES) {
            throw new IllegalArgumentException(fieldName + " 行数超过上限 " + MAX_LINES
                + "，当前 " + parts.length + " 行（本工具面向配置/报文比对，超大文件请先截取关注片段）");
        }
        return List.of(parts);
    }

    /** 生成比较用的归一化行。 */
    private List<String> normalizeAll(List<String> lines, boolean trimSpace, boolean foldCase) {
        List<String> keys = new ArrayList<>(lines.size());
        for (String line : lines) {
            String key = trimSpace ? line.trim() : line;
            keys.add(foldCase ? key.toLowerCase(Locale.ROOT) : key);
        }
        return keys;
    }

    /** 构建 LCS 长度表：dp[i][j] = oldKeys 前 i 行与 newKeys 前 j 行的最长公共子序列长度。 */
    private int[][] buildLcsTable(List<String> oldKeys, List<String> newKeys) {
        int oldSize = oldKeys.size();
        int newSize = newKeys.size();
        int[][] dp = new int[oldSize + 1][newSize + 1];
        for (int i = 1; i <= oldSize; i++) {
            for (int j = 1; j <= newSize; j++) {
                if (oldKeys.get(i - 1).equals(newKeys.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    /**
     * 回溯 LCS 表生成行列表。
     *
     * <p>从表尾往前走再整体反转，保证同一位置上"删除"排在"新增"之前，读起来即"旧行改成了新行"。</p>
     */
    private List<DiffLine> backtrack(int[][] dp, List<String> oldLines, List<String> newLines,
                                     List<String> oldKeys, List<String> newKeys) {
        List<DiffLine> reversed = new ArrayList<>();
        int i = oldLines.size();
        int j = newLines.size();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldKeys.get(i - 1).equals(newKeys.get(j - 1))) {
                reversed.add(line(TYPE_EQUAL, i, j, oldLines.get(i - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                reversed.add(line(TYPE_INSERT, NO_LINE, j, newLines.get(j - 1)));
                j--;
            } else {
                reversed.add(line(TYPE_DELETE, i, NO_LINE, oldLines.get(i - 1)));
                i--;
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private DiffLine line(String type, int oldLineNo, int newLineNo, String content) {
        return DiffLine.builder().type(type).oldLineNo(oldLineNo).newLineNo(newLineNo).content(content).build();
    }

    /**
     * 比对结果。
     */
    @Getter
    @Builder
    public static class DiffResult {
        /** 两段文本是否完全一致（按当前忽略选项判定）。 */
        private final boolean identical;
        /** 新增行数。 */
        private final int addedLines;
        /** 删除行数。 */
        private final int deletedLines;
        /** 结果总行数（截断前）。 */
        private final int totalLines;
        /** 是否因差异过多被截断。 */
        private final boolean truncated;
        /** 逐行结果。 */
        private final List<DiffLine> lines;
    }

    /**
     * 比对结果中的一行。行号从 1 起；该侧不存在此行时为 -1。
     */
    @Getter
    @Builder
    public static class DiffLine {
        /** 类型：EQUAL(相同) / INSERT(新增) / DELETE(删除)。 */
        private final String type;
        /** 在原文本中的行号，新增行为 -1。 */
        private final int oldLineNo;
        /** 在新文本中的行号，删除行为 -1。 */
        private final int newLineNo;
        /** 行内容（原文，未受忽略选项影响）。 */
        private final String content;
    }
}
