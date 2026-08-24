package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

/**
 * 知识库下拉选项（智能体表单用）：只返回 status=1 且 testStatus=1 的知识库。
 *
 * @param id     知识库 ID
 * @param kbName 知识库名称
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeBaseOptionVO(Long id,
                                    String kbName,
                                    Long currentVersionId,
                                    Integer latestVersionNo,
                                    String freshnessStatus,
                                    String qualityStatus) {

    public KnowledgeBaseOptionVO(Long id, String kbName) {
        this(id, kbName, null, null, null, null);
    }
}
