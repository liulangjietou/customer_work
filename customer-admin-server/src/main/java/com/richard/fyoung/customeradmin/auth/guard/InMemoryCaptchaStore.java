package com.richard.fyoung.customeradmin.auth.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内验证码存储：单实例部署与 Redis 不可用时的默认实现。
 *
 * <p>多副本部署下会出现"在 A 实例取图、到 B 实例校验"而校验不过的情况，
 * 因此仅作降级用；对外实例应保证 Redis 可用，由 {@link RedissonCaptchaStore} 接管。</p>
 *
 * <p>过期清理搭在读路径与写路径上，不另起线程：验证码的量级由注册频率决定，
 * 而注册本身已经被 IP 限流卡住，不会积累到需要后台清理的规模。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryCaptchaStore implements CaptchaStore {

    /** 单进程内允许驻留的验证码条数上限，防止异常流量把内存吃光。 */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(String captchaId, String answer, int ttlSeconds) {
        purgeExpired();
        if (entries.size() >= MAX_ENTRIES) {
            // 超限时丢弃新的而不是清空旧的：清空会让正在填表的人全部失败
            return;
        }
        entries.put(captchaId, new Entry(answer, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    @Override
    public String consume(String captchaId) {
        Entry entry = entries.remove(captchaId);
        if (entry == null || entry.expireAtMs() < System.currentTimeMillis()) {
            return null;
        }
        return entry.answer();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expireAtMs() < now);
    }

    private record Entry(String answer, long expireAtMs) {
    }
}
