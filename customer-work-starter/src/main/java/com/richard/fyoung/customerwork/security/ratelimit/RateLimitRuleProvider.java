package com.richard.fyoung.customerwork.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 限流规则快照持有者 + 定时刷新器：热路径无锁读，后台改规则后自动生效。
 *
 * <p><b>快照语义</b>：规则列表在刷新时整体替换（{@code volatile} 引用原子切换），
 * {@link #match(String)} 只读引用、不加锁——每个请求的匹配都发生在限流过滤器的热路径上，
 * 这里加锁等于给全部流量串行化。读到的永远是某个完整快照（旧的或新的），不会读到半构建态。</p>
 *
 * <p><b>fail-open</b>：读规则失败时保留上次快照；从未加载成功则快照为空，过滤器随即回退到 yml
 * 全局兜底配置。限流读不到规则绝不能"限死一切"——那是自伤，与敏感词的 fail-closed 方向相反。</p>
 * @author owlzhangfq@gmail.com
 */
public class RateLimitRuleProvider {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRuleProvider.class);

    private final RateLimitRuleStore store;
    private final boolean refreshEnabled;

    /** 当前生效的规则快照（已按优先级升序排好，匹配时首个命中即止）。 */
    private volatile List<RateLimitRule> snapshot = List.of();
    /** 上一次成功换快照时对应的规则指纹。 */
    private volatile String lastFingerprint;

    public RateLimitRuleProvider(RateLimitRuleStore store, boolean refreshEnabled) {
        this.store = store;
        this.refreshEnabled = refreshEnabled;
        reload();
    }

    /** 定时轮询入口；间隔由 {@code customer-work.security.rate-limit.refresh-interval-ms} 决定（默认 60s）。 */
    @Scheduled(fixedDelayString = "${customer-work.security.rate-limit.refresh-interval-ms:60000}")
    public void scheduledRefresh() {
        if (!refreshEnabled) {
            return;
        }
        Optional<String> current = store.fingerprint();
        if (current.isEmpty()) {
            log.error("[RATELIMIT] rule fingerprint probe failed, keep current snapshot, code={}",
                "RATELIMIT-REFRESH-PROBE-FAIL");
            return;
        }
        if (current.get().equals(lastFingerprint)) {
            return;
        }
        if (reload()) {
            log.info("[RATELIMIT] rules changed, snapshot refreshed, rules={}", snapshot.size());
        }
    }

    /**
     * 从存储重新拉取启用规则并整体换快照（后台"立即生效"也复用本方法）。
     *
     * @return 是否加载成功；失败时保留原快照与原指纹，下一轮继续重试
     */
    public boolean reload() {
        Optional<List<RateLimitRule>> loaded = store.findEnabled();
        if (loaded.isEmpty()) {
            log.error("[RATELIMIT] rule reload failed, keep snapshot (rules={}), code={}",
                snapshot.size(), "RATELIMIT-RELOAD-FAIL");
            return false;
        }
        List<RateLimitRule> sorted = new ArrayList<>(loaded.get());
        sorted.sort(Comparator.comparingInt(RateLimitRule::priority));
        this.snapshot = List.copyOf(sorted);
        this.lastFingerprint = store.fingerprint().orElse(null);
        return true;
    }

    /**
     * 匹配路径命中的规则（优先级升序首匹配即止）。
     *
     * @return 命中的规则；无规则命中返回 {@code null}，由调用方回退全局兜底配置
     */
    public RateLimitRule match(String path) {
        for (RateLimitRule rule : snapshot) {
            if (rule.matches(path)) {
                return rule;
            }
        }
        return null;
    }

    /** 当前快照（观测 / 单测）。 */
    public List<RateLimitRule> snapshot() {
        return snapshot;
    }
}
