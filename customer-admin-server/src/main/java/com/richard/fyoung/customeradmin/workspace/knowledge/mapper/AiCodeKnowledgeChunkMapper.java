package com.richard.fyoung.customeradmin.workspace.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeChunk;

/**
 * 代码知识库分块 Mapper（无自定义 SQL；相似度检索在应用层做，见 KnowledgeService）。
 * @author owlzhangfq@gmail.com
 */
public interface AiCodeKnowledgeChunkMapper extends BaseMapper<AiCodeKnowledgeChunk> {
}
