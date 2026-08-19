package com.richard.fyoung.customerwork.data.ticket;

/**
 * 工单状态机：AI 服务中 → 请求转人工 → 坐席处理 → 用户确认 → 结案，可回流（reopen）。
 *
 * <p>相较 {@code HandoffStatus} 的三态极简闭环，本状态机覆盖完整客服工单生命周期：AI 自助、
 * 转人工排队、坐席受理（含挂起）、处理完毕待用户确认、确认结案与关闭，并支持已结案工单被
 * 重新打开回到排队态。合法流转由 {@link Ticket} 内各状态方法各自前置校验，非法流转 fast-fail。</p>
 * @author owlzhangfq@gmail.com
 */
public enum TicketStatus {

    /** AI 自助服务中（尚未转人工）。 */
    AI_SERVING,

    /** 已请求转人工，等待坐席接单（排队中）。 */
    WAITING_AGENT,

    /** 坐席已接单，处理中。 */
    PROCESSING,

    /** 坐席挂起（等待用户补充材料 / 第三方回复等）。 */
    ON_HOLD,

    /** 坐席已处理完毕，等待用户确认。 */
    WAITING_CONFIRM,

    /** 用户已确认解决。 */
    RESOLVED,

    /** 工单已关闭（终态，可 reopen 回流）。 */
    CLOSED;

    /**
     * 是否已结束（用户视角的"这次服务到此为止"）。
     *
     * <p>{@code RESOLVED} 与 {@code CLOSED} 都算：前者是用户确认解决、后者是关单，
     * 二者都意味着本次服务已给出结论，只是收尾方式不同。用户端据此转只读态、
     * 满意度邀请据此发出——判定收敛在这里一处，不要在各处再写
     * {@code == CLOSED || == RESOLVED}（前端 {@code isTicketEnded} 是同一份定义）。</p>
     *
     * <p>两者都可 reopen 回流，所以"终态"是就本次服务而言，不代表工单不可再动。</p>
     */
    public boolean isEnded() {
        return this == RESOLVED || this == CLOSED;
    }
}
