package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识库 FAQ 持久化对象（贫血数据袋，映射 {@code cw_knowledge} 表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_knowledge")
public class KnowledgeDO {

    /** 知识条目自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 命中关键词（逗号分隔）。 */
    private String keyword;

    /** 条目标题。 */
    private String title;

    /** 条目内容。 */
    private String content;

    /** 来源标注。 */
    private String source;
}
