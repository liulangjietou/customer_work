package com.richard.fyoung.customerwork.capability.knowledgegap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 人工复核流水，只由复核事务追加，不提供修改或删除接口。 */
@Data
@TableName("cw_knowledge_gap_review")
public class KnowledgeGapReviewDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String scopeId;
    private String questionHash;
    private Long revision;
    private String previousCategory;
    private String previousPriority;
    private String category;
    private String priority;
    private String reason;
    private String reviewedBy;
    private Long reviewedAtMs;
    private Long signalCount;
}
