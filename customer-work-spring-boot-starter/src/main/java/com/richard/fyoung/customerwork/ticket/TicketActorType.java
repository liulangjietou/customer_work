package com.richard.fyoung.customerwork.ticket;

/**
 * 工单事件 / 消息的动作发起方类型：用户 / 坐席 / 机器人 / 系统。
 *
 * <p>被 {@link TicketEvent} 与 {@code ChatMessage} 复用，标识每一次状态流转或每一条消息的来源。</p>
 * @author owlzhangfq@gmail.com
 */
public enum TicketActorType {

    /** 终端用户。 */
    USER,

    /** 人工坐席。 */
    AGENT,

    /** AI 机器人。 */
    BOT,

    /** 系统（SLA 巡检 / 自动流转等）。 */
    SYSTEM
}
