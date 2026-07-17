package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * AI 代码审查中的单条问题（需求文档 §4.2.2 输出 Schema）。字段直接对应模型输出的 JSON，
 * 用 {@link JsonIgnoreProperties} 容错模型多吐的未知字段（模型不完全守约时不整体解析失败）。
 *
 * @param severity   严重级别：{@code CRITICAL｜WARNING｜SUGGESTION}
 * @param file       问题所在文件相对路径
 * @param line       问题所在行号（模型给不出时为 {@code null}）
 * @param category   问题分类：{@code SECURITY｜PERFORMANCE｜READABILITY｜BUG｜STYLE}
 * @param message    问题描述
 * @param suggestion 修复建议
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewIssue(
        String severity,
        String file,
        Integer line,
        String category,
        String message,
        String suggestion) {
}
