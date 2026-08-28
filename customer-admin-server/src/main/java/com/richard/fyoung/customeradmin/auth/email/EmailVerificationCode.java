package com.richard.fyoung.customeradmin.auth.email;

/**
 * 一条待核验的邮箱验证码。
 *
 * <p>{@code attempts} 随在存储里，不另建计数键：验证码本身就是这次会话的全部状态，
 * 分成两个键会出现"码还在、次数没了"或反过来的错配，而且两个键的过期时间不可能严格一致。</p>
 *
 * @param code       明文验证码（只在服务端与用户邮箱之间存在）
 * @param attempts   已失败的核验次数
 * @param expireAtMs 绝对过期时刻，用于重写时保持原有有效期不被刷新
 */
public record EmailVerificationCode(String code, int attempts, long expireAtMs) {

    /** 失败一次后的新状态，过期时刻保持不变。 */
    public EmailVerificationCode withOneMoreFailure() {
        return new EmailVerificationCode(code, attempts + 1, expireAtMs);
    }

    /** 距离过期还剩多少秒；已过期返回 0。 */
    public int remainingSeconds(long nowMs) {
        long remaining = (expireAtMs - nowMs) / 1000L;
        return remaining <= 0 ? 0 : (int) remaining;
    }
}
