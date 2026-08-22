package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户级评测发布门禁策略。 */
@Data
@TableName("ai_eval_release_gate_policy")
public class EvalGatePolicyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String evalType;
    private Integer enabled;
    private Double minPrimaryMetric;
    private Double minSecondaryMetric;
    private Double maxPrimaryRegression;
    private Double maxSecondaryRegression;
    private String criticalCaseIdsJson;
    private String judgeErrorPolicy;
    private Integer requireArtifactMatch;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
