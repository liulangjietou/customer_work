package com.richard.fyoung.customeradmin.workspace.task.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台任务展示对象。
 *
 * <p>列表接口里 {@link #result} 只放截断后的预览（任务结果可能是子智能体的整篇产出，
 * 一页十条全量返回足以让列表接口变成慢查询），全文走详情接口。{@link #resultTruncated}
 * 告诉前端"这条被截断了，点详情看全文"，避免前端靠长度猜。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentTaskVO {

    private Long id;
    private String taskId;
    private String parentAgentCode;
    private String subAgentId;
    private String parentSessionId;
    private String status;
    /** 结果文本；列表接口为预览，详情接口为全文。 */
    private String result;
    /** 列表接口里结果是否被截断（详情接口恒为 false）。 */
    private boolean resultTruncated;
    private String errorMessage;
    private Boolean cancelRequested;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 执行耗时（毫秒）：已开始且已结束时才有值，排队中/执行中为 null。 */
    private Long costMs;
}
