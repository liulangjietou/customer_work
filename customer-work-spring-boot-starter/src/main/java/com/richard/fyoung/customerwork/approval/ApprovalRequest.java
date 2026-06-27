package com.richard.fyoung.customerwork.approval;

import lombok.Getter;
import lombok.Setter;

/**
 * 人工审批单（充血：自带状态流转方法，非纯数据袋）。
 *
 * <p>标识与诉求字段不可变；状态与决策字段随人工决策流转。状态机由 {@link #approve}/{@link #deny}
 * 在 PENDING 终态校验下推进，避免重复决策。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
public class ApprovalRequest {

    private final String id;
    private final ApprovalType type;
    private final String sessionId;
    private final String orderId;
    private final String amount;
    private final String reason;
    private final long createdAtMs;

    @Setter
    private volatile ApprovalStatus status = ApprovalStatus.PENDING;
    private volatile String operator;
    private volatile String decisionNote;
    private volatile long decidedAtMs;

    public ApprovalRequest(String id, ApprovalType type, String sessionId,
                           String orderId, String amount, String reason, long createdAtMs) {
        this.id = id;
        this.type = type;
        this.sessionId = sessionId;
        this.orderId = orderId;
        this.amount = amount;
        this.reason = reason;
        this.createdAtMs = createdAtMs;
    }

    /** 人工放行：仅 PENDING 可推进，否则 fast-fail 拒绝重复决策。 */
    public void approve(String operator, String note, long whenMs) {
        decide(ApprovalStatus.APPROVED, operator, note, whenMs);
    }

    /** 人工拒绝：仅 PENDING 可推进，否则 fast-fail 拒绝重复决策。 */
    public void deny(String operator, String note, long whenMs) {
        decide(ApprovalStatus.DENIED, operator, note, whenMs);
    }

    private void decide(ApprovalStatus target, String operator, String note, long whenMs) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("approval already decided: id=" + id + ", status=" + status);
        }
        this.status = target;
        this.operator = operator;
        this.decisionNote = note;
        this.decidedAtMs = whenMs;
    }
}
