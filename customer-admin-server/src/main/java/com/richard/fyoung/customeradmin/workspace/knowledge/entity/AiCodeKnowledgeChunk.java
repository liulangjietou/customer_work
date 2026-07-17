package com.richard.fyoung.customeradmin.workspace.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码知识库分块与向量（P3-2）：一条 = 源码切出的一个分块 + 其 Embedding 向量（JSON 浮点数组）。
 * 降级实现：向量存 JSON 文本、相似度在应用层算；升级路径见 V23 迁移注释。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_code_knowledge_chunk")
public class AiCodeKnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long indexId;
    private String sourcePath;
    private Integer chunkIndex;
    private String symbol;
    private String lang;
    private String content;
    /** 向量：JSON 浮点数组字符串（如 {@code [0.1,0.2,...]}）。 */
    private String embedding;
    private Integer dimensions;
    /** 建库时间；实体不设 fill，由 DB 默认值填充。 */
    private LocalDateTime createTime;
}
