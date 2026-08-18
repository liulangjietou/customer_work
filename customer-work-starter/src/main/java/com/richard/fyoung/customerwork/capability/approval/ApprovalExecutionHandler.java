package com.richard.fyoung.customerwork.capability.approval;

/**
 * 审批通过后的真实副作用执行 SPI。
 *
 * <p>资金系统实现必须持久化消费 {@link ApprovalExecutionContext#idempotencyKey()}，同一键重复调用只能产生
 * 一次资金动作。项目不提供空实现；未装配时审批会保留为 EXECUTE_FAILED，绝不会标记成已执行。</p>
 */
@FunctionalInterface
public interface ApprovalExecutionHandler {

    void execute(ApprovalExecutionContext context) throws Exception;
}
