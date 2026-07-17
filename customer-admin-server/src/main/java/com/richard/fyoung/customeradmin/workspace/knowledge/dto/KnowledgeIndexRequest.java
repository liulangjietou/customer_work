package com.richard.fyoung.customeradmin.workspace.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 构建/重建代码知识库索引请求（P3-2）。
 * @param indexName  索引名（唯一，用户可读标识；已存在则重建）
 * @param sourcePath 源码目录/文件路径（必须落在 {@code admin.knowledge.allowed-roots} 白名单下）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeIndexRequest(
    @NotBlank(message = "索引名不能为空") String indexName,
    @NotBlank(message = "源码路径不能为空") String sourcePath) {
}
