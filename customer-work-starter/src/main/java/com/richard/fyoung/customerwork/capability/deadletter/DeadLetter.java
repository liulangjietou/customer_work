package com.richard.fyoung.customerwork.capability.deadletter;

import lombok.Getter;

/**
 * 死信：一次失败且值得重投的操作（充血：自带状态流转与退避计算）。
 *
 * <p>此前工具调用失败、主动通知发送失败都是"记一条 error 就完事"——业务量小时看不出来，
 * 量一上来就是实打实的丢单：用户以为退款申请提交了，实际下游根本没收到。</p>
 *
 * <p><b>退避必须是指数的</b>：下游多半是被打挂了或正在重启，固定间隔的密集重试只会把它按在地上，
 * 变成自己给自己制造的雪崩。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
public class DeadLetter {

    private final String id;

    /** 死信类型：决定由哪个 {@link DeadLetterHandler} 重投。 */
    private final String type;

    /** 重投所需的完整载荷（JSON）——必须自包含，重投时原始调用栈早已不在了。 */
    private final String payload;

    /** 关联业务标识（订单号/会话号等），供运营检索。 */
    private final String bizKey;

    private final long createdAtMs;

    private volatile DeadLetterStatus status = DeadLetterStatus.PENDING;
    private volatile int attempts;
    private volatile String lastError;
    private volatile long nextRetryAtMs;
    private volatile long finishedAtMs;

    public DeadLetter(String id, String type, String payload, String bizKey,
                      String lastError, long createdAtMs) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.bizKey = bizKey;
        this.lastError = lastError;
        this.createdAtMs = createdAtMs;
        this.nextRetryAtMs = createdAtMs;
    }

    /**
     * 一次重投失败：累计次数、记录错误、按指数退避排下一次。
     *
     * @param maxAttempts 上限；达到则转 {@link DeadLetterStatus#ABANDONED}
     * @param baseBackoffMs 基础退避毫秒
     */
    public void failAttempt(String error, int maxAttempts, long baseBackoffMs, long nowMs) {
        this.attempts++;
        this.lastError = error;
        if (attempts >= maxAttempts) {
            this.status = DeadLetterStatus.ABANDONED;
            this.finishedAtMs = nowMs;
            return;
        }
        this.nextRetryAtMs = nowMs + backoffMs(baseBackoffMs);
    }

    /** 重投成功。 */
    public void succeed(long nowMs) {
        this.status = DeadLetterStatus.SUCCEEDED;
        this.finishedAtMs = nowMs;
    }

    /**
     * 人工重置：把已放弃的重新放回待重投队列（运营确认下游恢复后手工触发）。
     *
     * <p>重置会清零重试次数——否则刚放回去就又立刻耗尽，等于没重置。</p>
     */
    public void reopen(long nowMs) {
        this.status = DeadLetterStatus.PENDING;
        this.attempts = 0;
        this.nextRetryAtMs = nowMs;
        this.finishedAtMs = 0L;
    }

    /** 是否到了可以重投的时刻。 */
    public boolean dueAt(long nowMs) {
        return status == DeadLetterStatus.PENDING && nextRetryAtMs <= nowMs;
    }

    /**
     * 指数退避：base * 2^attempts，封顶避免溢出成天文数字。
     *
     * <p>下游多半是被打挂了或正在重启，密集重试只会把它按在地上。</p>
     */
    private long backoffMs(long baseBackoffMs) {
        int shift = Math.min(attempts, MAX_BACKOFF_SHIFT);
        return baseBackoffMs * (1L << shift);
    }

    /** 退避指数上限：2^10 = 1024 倍，基础 30s 时约 8.5 小时，再久就该人工介入了。 */
    private static final int MAX_BACKOFF_SHIFT = 10;

    /** 从存储还原时重建流转字段（仅供 Store 层使用，不表达业务动作）。 */
    public void restoreState(DeadLetterStatus status, int attempts, String lastError,
                             long nextRetryAtMs, long finishedAtMs) {
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextRetryAtMs = nextRetryAtMs;
        this.finishedAtMs = finishedAtMs;
    }
}
