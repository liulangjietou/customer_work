package com.richard.fyoung.customeradmin.aiconfig.channel.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道绑定列表/详情视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ChannelBindingVO {
    private Long id;
    private String channelCode;
    private Long agentId;
    /** 冗余回显智能体名称（免前端二次查询）。 */
    private String agentName;
    private Integer status;
    /** 最近一次可靠发布任务 ID；门禁详情、重评和紧急豁免都以它为入口。 */
    private String publishTaskId;
    /** 最近一次可靠发布状态：PENDING/PROCESSING/BLOCKED/PUBLISHED/PARTIAL/APPLIED/FAILED。 */
    private String publishStatus;
    /** 最近一次评测门禁状态：NOT_REQUIRED/PENDING/PASSED/BLOCKED/OVERRIDDEN。 */
    private String publishGateStatus;
    private String publishRevision;
    private String publishLastError;
    private Long publishUpdatedAtMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
