package com.richard.fyoung.customerwork.security;

/**
 * 一条工具调用风险判定结果（{@link ToolCallRiskDetector} 的产出，不可变值对象）。
 *
 * @param type   风险类型（隐含严重度，见 {@link ToolCallRiskType}）
 * @param target 风险目标：文件路径 / 命令文本 / 批量修改的文件数，超长已截断，供人类可读展示
 * @param reason 命中原因（人类可读文案）
 * @author owlzhangfq@gmail.com
 */
public record ToolCallRisk(ToolCallRiskType type, String target, String reason) {
}
