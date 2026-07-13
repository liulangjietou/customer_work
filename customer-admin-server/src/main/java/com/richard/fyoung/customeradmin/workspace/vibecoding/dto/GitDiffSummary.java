package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.util.List;

/**
 * 会话 workspace 相对基线的 git diff 摘要。
 *
 * @param summary      AI 生成的 1~3 句话变更摘要
 * @param changedFiles 变更文件相对路径清单
 * @author owlzhangfq@gmail.com
 */
public record GitDiffSummary(String summary, List<String> changedFiles) {
}
