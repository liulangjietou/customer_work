package com.richard.fyoung.customerwork.capability.badcase;

/**
 * badcase 的处理状态。
 *
 * <p>刻意<b>不</b>把"转知识库"和"转评测用例"做成两个互斥状态：一条 badcase 说明这个问题答错了，
 * 补知识是<b>治本</b>（下次能答对），加评测用例是<b>防复发</b>（下次答错能立刻发现），
 * 两件事该一起做。它们因此记在 {@code adoptedKnowledgeId}/{@code adoptedEvalCaseId} 两个字段上，
 * 本枚举只回答"这条还要不要人管"。</p>
 * @author owlzhangfq@gmail.com
 */
public enum BadcaseStatus {

    /** 待筛选：还没人看过。 */
    PENDING,

    /** 已处理：至少完成了一项回流（补知识 / 加评测用例）。 */
    RESOLVED,

    /** 已忽略：噪声反馈或误报质检，不值得回流，但保留记录以免被反复翻出来。 */
    IGNORED
}
