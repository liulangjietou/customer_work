package com.richard.fyoung.customerwork.data.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 受管知识库向量分片（{@code cw_knowledge_chunk}）。
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_knowledge_chunk")
public class KnowledgeChunkDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long kbVersionId;

    /** 文档修订 ID：同时是检索分区键与 ACL 归属。 */
    private Long docRevisionId;

    private Integer chunkIndex;

    private String content;

    /** 定长 float32 向量，大端字节序，编解码走 {@code VectorCodec}。 */
    private byte[] embedding;

    private Integer dimensions;

    private String aclMode;

    private String externalId;

    /** 引用的历史修订标题；不由当前版本或正文推测。 */
    private String documentTitle;

    private String sourceVersion;

    private Long createdAtMs;

    private Long updatedAtMs;
}
