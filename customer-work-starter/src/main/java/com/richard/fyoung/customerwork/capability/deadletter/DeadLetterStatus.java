package com.richard.fyoung.customerwork.capability.deadletter;

/**
 * 死信状态。
 * @author owlzhangfq@gmail.com
 */
public enum DeadLetterStatus {

    /** 待重投：还在重试窗口内。 */
    PENDING,

    /** 已被某个实例租约认领；租约过期后可由其他实例接管。 */
    PROCESSING,

    /** 重投成功：留档不删，用来回答"这单最后到底成没成"。 */
    SUCCEEDED,

    /**
     * 已放弃：重试次数耗尽，需人工介入。
     *
     * <p>耗尽后<b>不静默丢弃</b>——那正是现在"只记 error"的问题所在。
     * 保留终态记录，运营才能捞出来手工补。</p>
     */
    ABANDONED
}
