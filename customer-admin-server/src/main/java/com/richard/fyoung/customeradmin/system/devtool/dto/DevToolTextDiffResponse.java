package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 文本比对响应。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolTextDiffResponse {

    /** 两段文本是否完全一致（按当前忽略选项判定）。 */
    private boolean identical;

    /** 新增行数。 */
    private int addedLines;

    /** 删除行数。 */
    private int deletedLines;

    /** 结果总行数（截断前）。 */
    private int totalLines;

    /** 是否因差异过多被截断。 */
    private boolean truncated;

    /** 逐行结果。 */
    private List<Line> lines;

    /**
     * 比对结果中的一行。行号从 1 起；该侧不存在此行时为 -1。
     */
    @Data
    @AllArgsConstructor
    public static class Line {

        /** 类型：EQUAL / INSERT / DELETE。 */
        private String type;

        /** 在原文本中的行号，新增行为 -1。 */
        private int oldLineNo;

        /** 在新文本中的行号，删除行为 -1。 */
        private int newLineNo;

        /** 行内容（原文，未受忽略选项影响）。 */
        private String content;
    }
}
