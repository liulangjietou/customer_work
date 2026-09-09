package com.richard.fyoung.customerwork.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 任务清单里的一项：本轮要办的一件事及其状态。
 *
 * <p><b>为什么要回传给用户</b>：多步任务（"既要退货又要改地址"）在智能体这边是几轮工具调用，
 * 在用户那边是一段沉默的等待。他不知道办到哪一步了，也不知道自己提的第二件事有没有被记住——
 * 而客服场景里"是不是被落下了"恰恰是最容易引发追问和投诉的疑虑。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "任务清单项")
public record TaskPlanItem(
        @Schema(description = "要办的事", example = "提交退货申请") String content,
        @Schema(description = "状态：pending / in_progress / completed", example = "in_progress") String status,
        @Schema(description = "优先级", example = "high") String priority) {
}
