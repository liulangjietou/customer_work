package com.richard.fyoung.customeradmin.auth.guard;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * Redis 验证码存储：多副本部署下"取图"与"校验"可能落在不同实例，必须共享。
 *
 * <p>Redis 故障时降级到进程内实现而不是放行——验证码是防滥用能力，
 * 不能因为基础设施抖动就整体消失（与项目内限流/配额的降级方向一致）。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class RedissonCaptchaStore implements CaptchaStore {

    private static final String KEY_PREFIX = "cw:admin:captcha:";

    private final RedissonClient redisson;
    private final CaptchaStore fallback;

    public RedissonCaptchaStore(RedissonClient redisson, CaptchaStore fallback) {
        this.redisson = redisson;
        this.fallback = fallback;
    }

    @Override
    public void save(String captchaId, String answer, int ttlSeconds) {
        try {
            RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + captchaId);
            bucket.set(answer, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.error("captcha save failed, fallback to in-process, code={}, captchaId={}",
                "AUTH-CAPTCHA-SAVE-FAIL", captchaId, e);
            fallback.save(captchaId, answer, ttlSeconds);
        }
    }

    @Override
    public String consume(String captchaId) {
        try {
            RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + captchaId);
            return bucket.getAndDelete();
        } catch (Exception e) {
            log.error("captcha consume failed, fallback to in-process, code={}, captchaId={}",
                "AUTH-CAPTCHA-READ-FAIL", captchaId, e);
            return fallback.consume(captchaId);
        }
    }
}
