package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ScheduledTaskVO {
    private Long id;
    private String taskCode;
    private String taskName;
    private Long agentId;
    /** 联查智能体名，方便前端展示；智能体被删除等情况下可能为 null。 */
    private String agentName;
    private String prompt;
    private Boolean enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
