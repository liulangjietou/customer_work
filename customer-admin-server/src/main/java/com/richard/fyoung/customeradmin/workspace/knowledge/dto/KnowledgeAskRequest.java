package com.richard.fyoung.customeradmin.workspace.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 代码知识库检索增强问答请求（P3-2）。
 * @param indexId  目标索引ID
 * @param question 自然语言问题
 * @param topK     检索召回条数（可空，空则用配置默认值）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeAskRequest(
    @NotNull(message = "索引ID不能为空") Long indexId,
    @NotBlank(message = "问题不能为空") String question,
    Integer topK) {
}
