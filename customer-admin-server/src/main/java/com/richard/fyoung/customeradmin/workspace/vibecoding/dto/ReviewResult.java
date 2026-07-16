package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 代码审查结果（需求文档 §4.2.2 输出 Schema）：结构化问题清单 + 一句话总述。
 *
 * <p>模型输出 JSON 解析失败时降级（需求文档 §4.2.3）：{@link #issues} 为空、{@link #summary}
 * 承载模型原文，前端仍可展示，不抛裸异常。</p>
 *
 * @param issues  按严重级别排布的问题清单（降级时为空列表）
 * @param summary 审查总述（降级时为模型原始文本）
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewResult(List<ReviewIssue> issues, String summary) {
}
