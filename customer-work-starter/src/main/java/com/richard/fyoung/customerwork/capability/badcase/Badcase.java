package com.richard.fyoung.customerwork.capability.badcase;

import lombok.Getter;

/**
 * badcase（充血：自带状态流转方法，非纯数据袋）。
 *
 * <p><b>为什么不直接用事实流水</b>：负反馈与质检失败早就写进 {@code cw_fact_log} 了，但那是 L3
 * 审计流水——只追加、不可变、永不改写。而"这条 badcase 处理了没有、转成了什么"是<b>有状态的运营工作流</b>，
 * 把状态塞进审计流水会破坏它的根本约定。故两者并存、各司其职：事实流水回答"当时发生了什么"，
 * 本实体回答"我们拿它做了什么"。</p>
 *
 * <p>{@link #userInput}/{@link #agentReply} 是筛选界面的立身之本——只给一个 messageId，
 * 运营根本无从判断该不该回流。这两个字段在记录时从聊天留痕回查补齐。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class Badcase {

    private final String id;
    private final BadcaseSource source;
    private final String sessionId;

    /** 被反馈的消息 ID；质检来源可能为空（质检针对的是一批回复）。 */
    private final String messageId;

    /** 用户问了什么（从聊天留痕回查；查不到为空）。 */
    private final String userInput;

    /** AI 答了什么（从聊天留痕回查；查不到为空）。 */
    private final String agentReply;

    /** 原始信号明细：点踩来源存用户留言，质检来源存扣分项与得分。 */
    private final String detail;

    private final long createdAtMs;

    private volatile BadcaseStatus status = BadcaseStatus.PENDING;

    /** 已回流成的知识条目 ID；空表示尚未补知识。 */
    private volatile Long adoptedKnowledgeId;

    /** 已回流成的评测用例编号；空表示尚未加评测用例。 */
    private volatile String adoptedEvalCaseId;

    private volatile String handledBy;
    private volatile long handledAtMs;

    /** 忽略原因；仅 {@link BadcaseStatus#IGNORED} 时有值。 */
    private volatile String ignoreReason;

    public Badcase(String id, BadcaseSource source, String sessionId, String messageId,
                   String userInput, String agentReply, String detail, long createdAtMs) {
        this.id = id;
        this.source = source;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.userInput = userInput;
        this.agentReply = agentReply;
        this.detail = detail;
        this.createdAtMs = createdAtMs;
    }

    /**
     * 采纳为知识库条目：补上答错的那块知识（治本）。
     *
     * <p>重复采纳直接拒绝——同一条 badcase 建两条知识条目，只会让知识库里多一条重复内容，
     * 而检索时重复内容会挤占本就有限的召回位。</p>
     */
    public void adoptAsKnowledge(Long knowledgeId, String operator, long whenMs) {
        if (adoptedKnowledgeId != null) {
            throw new IllegalStateException(
                "badcase already adopted as knowledge: id=" + id + ", knowledgeId=" + adoptedKnowledgeId);
        }
        this.adoptedKnowledgeId = knowledgeId;
        markHandled(operator, whenMs);
    }

    /**
     * 采纳为评测用例：把这次翻车固化成回归防护（防复发）。
     *
     * <p>与 {@link #adoptAsKnowledge} 互不排斥，一条 badcase 值得两件事都做。</p>
     */
    public void adoptAsEvalCase(String evalCaseId, String operator, long whenMs) {
        if (adoptedEvalCaseId != null) {
            throw new IllegalStateException(
                "badcase already adopted as eval case: id=" + id + ", caseId=" + adoptedEvalCaseId);
        }
        this.adoptedEvalCaseId = evalCaseId;
        markHandled(operator, whenMs);
    }

    /**
     * 忽略：噪声反馈或质检误报。
     *
     * <p>已处理过的不允许再忽略——那会让"已经补进知识库的内容"在记录上显示为不值得处理，
     * 后续复盘时对不上账。</p>
     */
    public void ignore(String operator, String reason, long whenMs) {
        if (status == BadcaseStatus.RESOLVED) {
            throw new IllegalStateException("badcase already resolved, cannot ignore: id=" + id);
        }
        this.status = BadcaseStatus.IGNORED;
        this.ignoreReason = reason;
        this.handledBy = operator;
        this.handledAtMs = whenMs;
    }

    /** 是否还需要人处理（筛选队列的过滤条件）。 */
    public boolean isPending() {
        return status == BadcaseStatus.PENDING;
    }

    /** 采纳动作共用的收尾：从忽略态被重新采纳也一并转正，运营改主意是合理的。 */
    private void markHandled(String operator, long whenMs) {
        this.status = BadcaseStatus.RESOLVED;
        this.ignoreReason = null;
        this.handledBy = operator;
        this.handledAtMs = whenMs;
    }

    /** 从存储还原时重建流转字段（仅供 Store 层使用，不表达业务动作）。 */
    public void restoreState(BadcaseStatus status, Long adoptedKnowledgeId, String adoptedEvalCaseId,
                             String handledBy, long handledAtMs, String ignoreReason) {
        this.status = status;
        this.adoptedKnowledgeId = adoptedKnowledgeId;
        this.adoptedEvalCaseId = adoptedEvalCaseId;
        this.handledBy = handledBy;
        this.handledAtMs = handledAtMs;
        this.ignoreReason = ignoreReason;
    }
}
