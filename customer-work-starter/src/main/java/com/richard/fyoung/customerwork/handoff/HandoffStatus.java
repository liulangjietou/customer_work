package com.richard.fyoung.customerwork.handoff;

/**
 * 人机切换工单状态机：PENDING（待坐席接单）→（claim）CLAIMED（坐席已接单处理中）→
 * （resolve）RESOLVED（处理完毕，回收给 AI 续接），终态不可再变。
 * @author owlzhangfq@gmail.com
 */
public enum HandoffStatus {

    /** 待坐席接单（AI 已转出，尚无人处理）。 */
    PENDING,

    /** 已被某坐席接单，处理中。 */
    CLAIMED,

    /** 坐席处理完毕，会话可回收给 AI 续接（终态）。 */
    RESOLVED
}
