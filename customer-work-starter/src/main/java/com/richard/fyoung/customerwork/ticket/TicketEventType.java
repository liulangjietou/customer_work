package com.richard.fyoung.customerwork.ticket;

/**
 * 工单事件类型：与 {@link Ticket} 的每个状态流转方法一一对应，构成可审计的工单操作轨迹。
 * @author owlzhangfq@gmail.com
 */
public enum TicketEventType {

    /** 建单。 */
    CREATE,

    /** 请求转人工。 */
    REQUEST_HANDOFF,

    /** 撤销转人工。 */
    CANCEL_HANDOFF,

    /** 坐席接单。 */
    CLAIM,

    /** 挂起。 */
    HOLD,

    /** 恢复处理。 */
    RESUME,

    /** 转派（回队列或转其他坐席）。 */
    TRANSFER,

    /** 标记处理完毕（待用户确认）。 */
    MARK_RESOLVED,

    /** 用户确认解决。 */
    CONFIRM,

    /** 用户驳回（打回处理）。 */
    REJECT,

    /** 关闭工单。 */
    CLOSE,

    /** 强制关闭（空闲超时自动结束 / 用户强制结束，可从任意非 CLOSED 态直达）。 */
    FORCE_CLOSE,

    /** 重新打开。 */
    REOPEN,

    /** 优先级变更。 */
    PRIORITY_CHANGE,

    /** 分类变更。 */
    CATEGORY_CHANGE
}
