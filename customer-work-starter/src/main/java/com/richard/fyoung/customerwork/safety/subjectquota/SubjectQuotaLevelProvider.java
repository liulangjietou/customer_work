package com.richard.fyoung.customerwork.safety.subjectquota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 等级快照持有者 + 定时刷新器：热路径无锁读，后台改等级后自动生效。
 *
 * <p>语义与 {@code RateLimitRuleProvider} 一致：快照整体替换（volatile 引用原子切换），
 * 指纹没变就不拉全表；读失败保留上次快照。等级数量是"租户数 × 几档"这个量级，
 * 全量缓存在内存里完全放得下，因此不需要按需查库——限流判定在每个请求的热路径上，
 * 一次库查询就是一次不可接受的延迟。</p>
 * @author owlzhangfq@gmail.com
 */
public class SubjectQuotaLevelProvider {

    private static final Logger log = LoggerFactory.getLogger(SubjectQuotaLevelProvider.class);

    private final SubjectQuotaLevelStore store;
    private final boolean refreshEnabled;

    /** 当前快照：key = tenantId + '\n' + levelCode。 */
    private volatile Map<String, SubjectQuotaLevel> snapshot = Map.of();
    private volatile String lastFingerprint;

    public SubjectQuotaLevelProvider(SubjectQuotaLevelStore store, boolean refreshEnabled) {
        this.store = store;
        this.refreshEnabled = refreshEnabled;
        reload();
    }

    /** 定时轮询入口；间隔由 {@code customer-work.subject-quota.refresh-interval-ms} 决定（默认 60s）。 */
    @Scheduled(fixedDelayString = "${customer-work.subject-quota.refresh-interval-ms:60000}")
    public void scheduledRefresh() {
        if (!refreshEnabled) {
            return;
        }
        Optional<String> current = store.fingerprint();
        if (current.isEmpty()) {
            log.error("subject quota level fingerprint probe failed, keep current snapshot, code={}",
                "SQUOTA-REFRESH-PROBE-FAIL");
            return;
        }
        if (current.get().equals(lastFingerprint)) {
            return;
        }
        if (reload()) {
            log.info("subject quota levels changed, snapshot refreshed, levels={}", snapshot.size());
        }
    }

    /**
     * 重新加载并整体换快照（后台"立即生效"也复用本方法）。
     *
     * @return 是否加载成功；失败时保留原快照，下一轮继续重试
     */
    public boolean reload() {
        Optional<List<SubjectQuotaLevel>> loaded = store.findAllEnabled();
        if (loaded.isEmpty()) {
            log.error("subject quota level reload failed, keep snapshot (levels={}), code={}",
                snapshot.size(), "SQUOTA-RELOAD-FAIL");
            return false;
        }
        Map<String, SubjectQuotaLevel> next = new HashMap<>();
        for (SubjectQuotaLevel level : loaded.get()) {
            next.put(key(level.tenantId(), level.levelCode()), level);
        }
        this.snapshot = Map.copyOf(next);
        this.lastFingerprint = store.fingerprint().orElse(null);
        return true;
    }

    /** 取某租户的某一档；快照里没有返回 null，由调用方回落内置档。 */
    public SubjectQuotaLevel find(String tenantId, String levelCode) {
        if (tenantId == null || levelCode == null) {
            return null;
        }
        return snapshot.get(key(tenantId, levelCode));
    }

    /** 当前快照大小（观测 / 单测）。 */
    public int size() {
        return snapshot.size();
    }

    private static String key(String tenantId, String levelCode) {
        return tenantId + '\n' + levelCode;
    }
}
