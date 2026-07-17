package com.richard.fyoung.customerwork.approval;

/**
 * 审批类型（人工介入闭环，对应「Human-in-the-Loop」）。
 *
 * <p>与框架 Permission ASK（工具调用层的声明式闸门）互补：本枚举用于<b>应用层动作审批</b>
 * ——涉及资金 / 高风险动作生成待人工确认单，由人工坐席 approve 后才真正执行。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ApprovalType {

    /** 退款打款（资金安全红线：只生成工单，人工放行后执行）。 */
    REFUND,

    /** 转人工坐席。 */
    TRANSFER_HUMAN
}
