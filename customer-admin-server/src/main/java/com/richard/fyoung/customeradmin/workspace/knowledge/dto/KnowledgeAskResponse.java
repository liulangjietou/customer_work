package com.richard.fyoung.customeradmin.workspace.knowledge.dto;

import java.util.List;

/**
 * 代码知识库问答结果（P3-2）：模型回答 + 引用来源（文件路径 + 符号 + 相似度）。
 * @param answer    检索增强后的自然语言回答
 * @param citations 回答所依据的检索命中项（出处标注）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeAskResponse(String answer, List<KnowledgeSearchHit> citations) {
}
