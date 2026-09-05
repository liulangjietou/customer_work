package com.richard.fyoung.customerwork.data.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 受管知识库版本在客服端的检索投影（{@code cw_knowledge_version}）。
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_knowledge_version")
public class KnowledgeVersionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long kbVersionId;

    private String kbCode;

    private String kbName;

    private Integer topN;

    private BigDecimal scoreThreshold;

    /** 该版本的向量维度：与查询向量维度不一致时检索必须失败，而不是算出一个无意义的分数。 */
    private Integer dimensions;

    private Integer chunkCount;

    private Long syncedAtMs;

    private Long createdAtMs;

    private Long updatedAtMs;
}
