package com.richard.fyoung.customeradmin.workspace.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码知识库索引（P3-2）：一个索引对应一次对某源码目录的向量化，聚合其所有分块。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_code_knowledge_index")
public class AiCodeKnowledgeIndex {

    /** 状态：构建中。 */
    public static final String STATUS_BUILDING = "BUILDING";
    /** 状态：就绪可检索。 */
    public static final String STATUS_READY = "READY";
    /** 状态：构建失败。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String indexName;
    private String sourcePath;
    private String embeddingModel;
    private Integer dimensions;
    /** 已入库分块数（构建进度）。 */
    private Integer chunkCount;
    /** 状态：{@link #STATUS_BUILDING} / {@link #STATUS_READY} / {@link #STATUS_FAILED}。 */
    private String status;
    private String message;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
