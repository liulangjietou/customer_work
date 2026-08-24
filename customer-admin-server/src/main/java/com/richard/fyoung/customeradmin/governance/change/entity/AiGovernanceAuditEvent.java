package com.richard.fyoung.customeradmin.governance.change.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 追加写、按请求串联哈希的治理审计事件。 */
@Data
@TableName("ai_governance_audit_event")
public class AiGovernanceAuditEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String requestId;
    private Integer sequenceNo;
    private String eventType;
    private Long actorId;
    private String actorName;
    private String payloadHash;
    private String detail;
    private String previousHash;
    private String eventHash;
    private LocalDateTime retentionUntil;
    private LocalDateTime createTime;
}
