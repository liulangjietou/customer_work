package com.richard.fyoung.customeradmin.governance.change.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** maker-checker 高风险变更事实。 */
@Data
@TableName("ai_governed_change_request")
public class AiGovernedChangeRequest {

    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String changeType;
    private String targetKey;
    private String payloadJson;
    private String payloadHash;
    private Long makerId;
    private String makerName;
    private Long checkerId;
    private String checkerName;
    private String status;
    private String decisionReason;
    private String resultJson;
    private String failureCode;
    private LocalDateTime expiresAt;
    private LocalDateTime decidedAt;
    private LocalDateTime executedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
