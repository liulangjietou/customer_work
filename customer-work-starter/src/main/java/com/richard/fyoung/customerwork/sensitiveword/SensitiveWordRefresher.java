package com.richard.fyoung.customerwork.sensitiveword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;

/**
 * 敏感词词表定时刷新器：轮询版本指纹，<b>变了才重建</b>自动机。
 *
 * <p><b>为什么需要它</b>：{@link SensitiveWordFilter} 只在构造时 {@code reload()} 一次，后台运营在
 * {@code cw_sensitive_word} 表增删改词之后，已经跑起来的客服进程不会感知。轮询是这里最省心的生效方式——
 * 后台不需要知道任何客服实例的地址，多实例天然各自收敛到同一份词表，实例重启也不会丢状态；
 * 代价只是最多一个轮询周期的生效延迟。</p>
 *
 * <p><b>指纹而非全量重建</b>：每轮先取 {@link SensitiveWordStore#fingerprint()}（JDBC 实现是一条单行聚合
 * SQL），与上轮比对，不变就直接返回——稳态下每分钟只有一次极轻的 SQL，不会反复重建 AC 自动机。</p>
 *
 * <p><b>三条边界</b>（都是安全语义，不是防御式冗余）：</p>
 * <ol>
 *   <li>指纹读失败（{@code Optional.empty()}）→ 本轮跳过，保留当前词表，不推进已记录指纹；</li>
 *   <li>过滤器处于 fail-closed 哨兵态（启动期读库失败，正在拦截一切）→ <b>无条件重建</b>，
 *       不看指纹，这样 DB 恢复后能尽快自动脱离"拦截一切"状态，不必等人重启进程；</li>
 *   <li>重建失败（{@link SensitiveWordFilter#reload()} 返回 false）→ 不推进已记录指纹，下一轮继续重试。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordRefresher {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordRefresher.class);

    private final SensitiveWordStore store;
    private final SensitiveWordFilter filter;
    private final boolean enabled;

    /** 上一次成功重建时对应的词表指纹（null 表示尚未记录过）。 */
    private volatile String lastFingerprint;

    public SensitiveWordRefresher(SensitiveWordStore store, SensitiveWordFilter filter, boolean enabled) {
        this.store = store;
        this.filter = filter;
        this.enabled = enabled;
        // 构造期把当前指纹记为基线：Filter 构造时已 reload 过一次，避免刷新器第一轮做无谓重建
        this.lastFingerprint = store.fingerprint().orElse(null);
    }

    /** 定时轮询入口；间隔由 {@code customer-work.sensitive-word.refresh-interval-ms} 决定（默认 60s）。 */
    @Scheduled(fixedDelayString = "${customer-work.sensitive-word.refresh-interval-ms:60000}")
    public void scheduledRefresh() {
        if (!enabled) {
            return;
        }
        refreshOnce();
    }

    /**
     * 执行一轮刷新（抽出以便单测与后台"立即生效"复用）。
     *
     * @return 是否真的重建了自动机
     */
    public boolean refreshOnce() {
        if (filter.isFailClosed()) {
            // 哨兵态：正在拦截一切，不看指纹直接重试，DB 一恢复就自动脱离
            boolean recovered = filter.reload();
            if (recovered) {
                this.lastFingerprint = store.fingerprint().orElse(null);
                log.info("[SENSITIVE] recovered from fail-closed sentinel, patterns={}", filter.patternCount());
            }
            return recovered;
        }
        Optional<String> current = store.fingerprint();
        if (current.isEmpty()) {
            // 指纹读失败：保留当前词表，不推进指纹，下一轮重试
            log.error("[SENSITIVE] fingerprint probe failed, skip this round, code={}", "SENSITIVE-REFRESH-PROBE-FAIL");
            return false;
        }
        String fingerprint = current.get();
        if (fingerprint.equals(lastFingerprint)) {
            return false;
        }
        if (!filter.reload()) {
            // 指纹变了但重建失败：不推进指纹，下一轮继续重试
            return false;
        }
        this.lastFingerprint = fingerprint;
        log.info("[SENSITIVE] word table changed, matcher refreshed, fingerprint={}, patterns={}",
            fingerprint, filter.patternCount());
        return true;
    }

    /** 当前记录的词表指纹（观测 / 单测）。 */
    public String currentFingerprint() {
        return lastFingerprint;
    }
}
