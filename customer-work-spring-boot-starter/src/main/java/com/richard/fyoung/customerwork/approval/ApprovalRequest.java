package com.richard.fyoung.customerwork.approval;

import lombok.Getter;
import lombok.Setter;

/**
 * 人工审批单（充血：自带状态流转方法，非纯数据袋）。
 *
 * <p>标识与诉求字段不可变；状态与决策字段随人工决策流转。状态机由 {@link #approve}/{@link #deny}
 * 在 PENDING 终态校验下推进，避免重复决策。</p>
 *
 * <p><b>决策 vs 执行分层</b>：{@link #approve} 只推进"人工决策"（{@code status=APPROVED}）；
 * 决策生效（如实际打款）是否成功由 {@link #markExecuted}/{@link #markExecutionFailed} 另行推进
 * {@link #executionStatus}——避免下游回调失败被 approve 的 fast-fail 状态机悄悄吞掉。</p>
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

    private volatile ExecutionStatus executionStatus = ExecutionStatus.NOT_APPLICABLE;
    private volatile String executionFailureReason;
    private volatile int executionAttempts;

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

    /** 下游执行（如实际打款）成功：清除失败原因，累计执行次数。 */
    public void markExecuted() {
        this.executionAttempts++;
        this.executionStatus = ExecutionStatus.EXECUTED;
        this.executionFailureReason = null;
    }

    /** 下游执行失败：记录失败原因，累计执行次数，供巡检重试与告警。 */
    public void markExecutionFailed(String reason) {
        this.executionAttempts++;
        this.executionStatus = ExecutionStatus.EXECUTE_FAILED;
        this.executionFailureReason = reason;
    }

    /**
     * 供持久化存储层（如 {@link JdbcApprovalStore}）从数据源重建已有工单——跳过
     * {@link #approve}/{@link #deny} 的业务状态机校验（这不是一次新的人工决策，只是把已发生过的
     * 决策结果读回内存）。包级可见，仅供本包内的 {@link ApprovalStore} 实现使用。
     */
    static ApprovalRequest reconstruct(String id, ApprovalType type, String sessionId, String orderId,
                                       String amount, String reason, long createdAtMs,
                                       ApprovalStatus status, String operator, String decisionNote, long decidedAtMs,
                                       ExecutionStatus executionStatus, String executionFailureReason,
                                       int executionAttempts) {
        ApprovalRequest req = new ApprovalRequest(id, type, sessionId, orderId, amount, reason, createdAtMs);
        req.status = status;
        req.operator = operator;
        req.decisionNote = decisionNote;
        req.decidedAtMs = decidedAtMs;
        req.executionStatus = executionStatus;
        req.executionFailureReason = executionFailureReason;
        req.executionAttempts = executionAttempts;
        return req;
    }
}
