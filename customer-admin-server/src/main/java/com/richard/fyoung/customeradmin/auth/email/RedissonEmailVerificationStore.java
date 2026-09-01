package com.richard.fyoung.customeradmin.auth.email;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * Redis 邮箱验证码存储：多副本部署下"发码"与"核验"可能落在不同实例，必须共享。
 *
 * <p><b>重写时用剩余时间而不是原始 TTL</b>：核验失败要把失败次数写回去，如果按原始有效期
 * 重新 set，每错一次就把有效期续满一次——验证码会随着不断试错永不过期，正好方便暴力猜码。</p>
 *
 * <p>Redis 故障时降级进程内而不是放行：邮箱验证是注册的准入条件，
 * 不能因为基础设施抖动就整体消失（与项目内限流/配额的降级方向一致）。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class RedissonEmailVerificationStore implements EmailVerificationStore {

    private static final String KEY_PREFIX = "cw:admin:email-code:";

    private final RedissonClient redisson;
    private final EmailVerificationStore fallback;

    public RedissonEmailVerificationStore(RedissonClient redisson, EmailVerificationStore fallback) {
        this.redisson = redisson;
        this.fallback = fallback;
    }

    @Override
    public void save(EmailCodePurpose purpose, String email, EmailVerificationCode code) {
        int remaining = code.remainingSeconds(System.currentTimeMillis());
        if (remaining <= 0) {
            invalidate(purpose, email);
            return;
        }
        try {
            RBucket<EmailVerificationCode> bucket = redisson.getBucket(key(purpose, email));
            bucket.set(code, Duration.ofSeconds(remaining));
        } catch (Exception e) {
            log.error("email code save failed, fallback to in-process, code={}", "AUTH-EMAIL-CODE-SAVE-FAIL", e);
            fallback.save(purpose, email, code);
        }
    }

    @Override
    public EmailVerificationCode get(EmailCodePurpose purpose, String email) {
        try {
            RBucket<EmailVerificationCode> bucket = redisson.getBucket(key(purpose, email));
            EmailVerificationCode code = bucket.get();
            if (code != null && code.remainingSeconds(System.currentTimeMillis()) <= 0) {
                bucket.delete();
                return null;
            }
            return code;
        } catch (Exception e) {
            log.error("email code read failed, fallback to in-process, code={}", "AUTH-EMAIL-CODE-READ-FAIL", e);
            return fallback.get(purpose, email);
        }
    }

    @Override
    public void invalidate(EmailCodePurpose purpose, String email) {
        try {
            redisson.getBucket(key(purpose, email)).delete();
        } catch (Exception e) {
            log.error("email code invalidate failed, code={}", "AUTH-EMAIL-CODE-DELETE-FAIL", e);
        }
        fallback.invalidate(purpose, email);
    }

    /**
     * 用途与邮箱共同入键：调用方已把邮箱统一小写，且 Redis 键不进日志（验证码本身才是敏感物）。
     *
     * <p>键形状因加入用途段而变化，升级那一刻仍挂在旧键上的注册验证码会读不到，
     * 当事人需要重新获取一次。TTL 只有十分钟，这个一次性代价换的是两条链路的凭证不再互通。</p>
     */
    private String key(EmailCodePurpose purpose, String email) {
        return KEY_PREFIX + purpose.storageKey() + ":" + email;
    }
}
