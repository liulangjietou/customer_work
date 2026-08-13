package com.richard.fyoung.customerwork.capability.knowledgegap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识盲区持久化对象（贫血数据袋）：与 {@code cw_knowledge_gap} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap}。
 * 主键用问题哈希：问题可能很长，直接做键会撞上索引长度限制（同 {@code cw_harness_memory} 的手法）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_knowledge_gap")
public class KnowledgeGapDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String questionHash;

    /** 问题原文（截断保存）——运营要看的就是这个。 */
    private String question;

    private String scopeId;

    /** 累计未命中次数：排行依据，越大越该优先补。 */
    private Long missCount;

    private Long firstSeenAtMs;
    private Long lastSeenAtMs;
}
