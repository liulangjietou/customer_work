package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import java.time.LocalDateTime;

/**
 * AI 代码审查任务视图（提交-轮询模型的轮询响应）：前端据 {@link #status} 决定是否继续轮询，
 * SUCCESS 时展示 {@link #result}，FAILED 时展示 {@link #errorMsg}。
 *
 * @param id         任务 id
 * @param status     状态：RUNNING/SUCCESS/FAILED
 * @param result     审查结果（仅 SUCCESS 时非空）
 * @param errorMsg   失败原因（仅 FAILED 时非空）
 * @param createTime 提交时间
 * @param finishTime 完成时间（RUNNING 时为空）
 * @author owlzhangfq@gmail.com
 */
public record ReviewTaskVO(
        Long id,
        String status,
        ReviewResult result,
        String errorMsg,
        LocalDateTime createTime,
        LocalDateTime finishTime) {
}
