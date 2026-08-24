package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 知识库不可变版本：冻结检索连接参数与一次文档快照。已有行只读，不提供更新入口。 */
@Data
@TableName("ai_knowledge_base_version")
public class AiKnowledgeBaseVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long knowledgeBaseId;
    private Integer versionNo;
    private String baseUrl;
    private String appId;
    private String apiKey;
    private String contentType;
    private String extraHeaders;
    private Integer topN;
    private BigDecimal scoreThreshold;
    private String checkpoint;
    private String snapshotHash;
    private Integer documentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private String changeNote;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
