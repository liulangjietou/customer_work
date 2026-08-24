package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import java.math.BigDecimal;

/** 同步质量低于文档源门槛；事务必须回滚且 checkpoint 不得推进。 */
public class KnowledgeQualityGateException extends RuntimeException {

    private final int activeDocumentCount;
    private final int duplicateContentCount;
    private final BigDecimal qualityScore;

    public KnowledgeQualityGateException(int activeDocumentCount,
                                         int duplicateContentCount,
                                         BigDecimal qualityScore,
                                         BigDecimal threshold) {
        super("知识质量分 " + qualityScore + " 低于门槛 " + threshold);
        this.activeDocumentCount = activeDocumentCount;
        this.duplicateContentCount = duplicateContentCount;
        this.qualityScore = qualityScore;
    }

    public int getActiveDocumentCount() {
        return activeDocumentCount;
    }

    public int getDuplicateContentCount() {
        return duplicateContentCount;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }
}
