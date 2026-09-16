package com.richard.fyoung.customerwork.capability.eval;

import java.time.Duration;

/** 一次评测共用的绝对截止时刻，答复和评分均消费剩余时间，不给每题重新分配总预算。 */
public record EvalExecutionDeadline(long expiresAtMs) {

    /** 在发起外部调用前计算可等待时长；已过期时不再订阅新的模型请求。 */
    public Duration limit(Duration operationTimeout) {
        long now = System.currentTimeMillis();
        if (expiresAtMs <= now) throw new IllegalStateException("evaluation execution deadline exceeded");
        return Duration.ofMillis(Math.min(expiresAtMs - now, operationTimeout.toMillis()));
    }
}
