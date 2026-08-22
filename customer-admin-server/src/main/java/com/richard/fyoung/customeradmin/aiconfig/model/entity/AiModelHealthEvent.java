package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型部署健康事件；只追加，不覆盖历史。 */
@Data
@TableName("ai_model_health_event")
public class AiModelHealthEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long modelConfigId;
    private String source;
    private String probeKind;
    private String healthStatus;
    private Integer testStatus;
    private Long latencyMs;
    private String errorCategory;
    private String message;
    private LocalDateTime occurredAt;
    private LocalDateTime createTime;
}
