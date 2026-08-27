package com.richard.fyoung.customeradmin.auth.email;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内邮箱验证码存储：单实例部署与 Redis 不可用时的默认实现。
 *
 * <p>多副本部署下会出现"在 A 实例发码、到 B 实例核验"而核验不过的情况，故仅作降级；
 * 对外实例应保证 Redis 可用，由 {@link RedissonEmailVerificationStore} 接管。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryEmailVerificationStore implements EmailVerificationStore {

    /** 驻留上限，防止异常流量把内存吃光；发码本身已被限流，正常不会接近这个量级。 */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, EmailVerificationCode> entries = new ConcurrentHashMap<>();

    @Override
    public void save(String email, EmailVerificationCode code) {
        purgeExpired();
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(email)) {
            // 超限时丢弃新的而不是清空旧的：清空会让正在填表的人全部失败
            return;
        }
        entries.put(email, code);
    }

    @Override
    public EmailVerificationCode get(String email) {
        EmailVerificationCode code = entries.get(email);
        if (code == null) {
            return null;
        }
        if (code.remainingSeconds(System.currentTimeMillis()) <= 0) {
            entries.remove(email);
            return null;
        }
        return code;
    }

    @Override
    public void invalidate(String email) {
        entries.remove(email);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().remainingSeconds(now) <= 0);
    }
}
