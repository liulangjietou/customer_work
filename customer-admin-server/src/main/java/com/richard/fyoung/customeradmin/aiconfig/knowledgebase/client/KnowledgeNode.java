package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import java.math.BigDecimal;

/**
 * 一条召回片段（对应外部接口响应里的 {@code data.nodes[]}）。
 *
 * @param kbName  召回来源知识库名称（多库合并后据此标注来源）
 * @param content 片段正文
 * @param score   相关度分数（外部服务的 rerank 分数，实测量级 0.1x）
 * @param docId   文档 ID
 * @param chunkId 分块 ID
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeNode(String kbName, String content, BigDecimal score, String docId, String chunkId) {
}
