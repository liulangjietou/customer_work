package com.richard.fyoung.customerwork.capability.approval;

/**
 * 审批放行后的下游执行状态（与 {@link ApprovalStatus} 分层：前者是"人工决策"，后者是"决策生效"）。
 *
 * <p>只有 {@link ApprovalStatus#APPROVED} 的工单才会推进到 {@link #EXECUTED}/{@link #EXECUTE_FAILED}；
 * {@link ApprovalStatus#DENIED}/{@link ApprovalStatus#PENDING} 始终保持 {@link #NOT_APPLICABLE}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ExecutionStatus {

    /** 不适用（工单尚未被批准，或已被拒绝）。 */
    NOT_APPLICABLE,

    /** 某一实例已取得带 fencing token 的执行租约。 */
    EXECUTING,

    /** 已批准且下游回调（如实际打款）执行成功。 */
    EXECUTED,

    /** 已批准但下游回调执行失败——工单显示"已放行"但资金动作未真正生效，需人工核实或等待自动重试。 */
    EXECUTE_FAILED
}
