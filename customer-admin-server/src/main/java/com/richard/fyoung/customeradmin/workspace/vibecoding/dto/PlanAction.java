package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * {@code plan} SSE 事件里的单条高风险操作项（需求 §4.4.2 / §6.2）。
 *
 * @param type   操作类型：{@code DELETE}（删除文件）｜{@code RUN_COMMAND}（执行非只读/破坏性命令）｜
 *               {@code MODIFY_DEPENDENCY}（修改依赖/构建文件）｜{@code BATCH_MODIFY}（单轮批量修改超阈值）
 * @param target 操作目标：文件路径或命令文本（超长截断）
 * @param reason 命中该风险的原因（人类可读，用于卡片展示）
 * @author owlzhangfq@gmail.com
 */
public record PlanAction(String type, String target, String reason) {
}
