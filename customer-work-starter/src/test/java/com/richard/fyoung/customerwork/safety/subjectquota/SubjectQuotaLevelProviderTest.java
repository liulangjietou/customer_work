package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 等级快照单测：读路径无锁、读失败保留旧快照、惰性刷新（给没有定时调度的宿主用）。
 * @author owlzhangfq@gmail.com
 */
class SubjectQuotaLevelProviderTest {

    private static final String TENANT = TenantContext.DEFAULT;

    /** 可控制"读成功/读失败"与"被读了几次"的探针 Store。 */
    private static final class ProbeStore implements SubjectQuotaLevelStore {
        private final AtomicInteger loads = new AtomicInteger();
        private volatile boolean available = true;
        private volatile List<SubjectQuotaLevel> levels = List.of();

        @Override
        public Optional<List<SubjectQuotaLevel>> findAllEnabled() {
            loads.incrementAndGet();
            return available ? Optional.of(levels) : Optional.empty();
        }

        @Override
        public List<SubjectQuotaLevel> findByTenant(String tenantId) {
            return levels;
        }

        @Override
        public void save(SubjectQuotaLevel level) {
            levels = List.of(level);
        }

        @Override
        public void delete(String tenantId, String levelCode) {
            levels = List.of();
        }
    }

    private static SubjectQuotaLevel level(String code, long tokenLimit) {
        return new SubjectQuotaLevel(null, TENANT, code, code, QuotaSubjectType.USER,
            1800, tokenLimit, 0, SubjectExceedAction.BLOCK, true, null);
    }

    @Test
    void find_shouldReturnSnapshotEntry() {
        ProbeStore store = new ProbeStore();
        store.save(level("free", 100));
        SubjectQuotaLevelProvider provider = new SubjectQuotaLevelProvider(store, false);

        assertNotNull(provider.find(TENANT, "free"));
        assertNull(provider.find(TENANT, "nope"), "没有的档返回 null，由调用方回落内置档");
        assertEquals(1, provider.size());
    }

    @Test
    void reload_shouldKeepSnapshot_whenStoreUnavailable() {
        ProbeStore store = new ProbeStore();
        store.save(level("free", 100));
        SubjectQuotaLevelProvider provider = new SubjectQuotaLevelProvider(store, false);

        store.available = false;
        assertEquals(false, provider.reload(), "读失败应返回 false");
        // 读不到等级就把所有人限死是自伤，故保留上一份快照而不是清空
        assertNotNull(provider.find(TENANT, "free"), "读取失败必须保留旧快照");
    }

    @Test
    void find_shouldNotReload_whenLazyRefreshDisabled() {
        ProbeStore store = new ProbeStore();
        SubjectQuotaLevelProvider provider = new SubjectQuotaLevelProvider(store, false);
        int afterConstruct = store.loads.get();

        provider.find(TENANT, "free");
        provider.find(TENANT, "free");
        assertEquals(afterConstruct, store.loads.get(), "关闭惰性刷新时读路径不得产生查询");
    }

    @Test
    void find_shouldReloadLazily_whenIntervalElapsed() throws Exception {
        ProbeStore store = new ProbeStore();
        // 间隔 1ms：admin 侧真实配置是 60 秒，这里只验证"到点会重载"这件事
        SubjectQuotaLevelProvider provider = new SubjectQuotaLevelProvider(store, false, 1L);
        int afterConstruct = store.loads.get();

        store.save(level("vip", 999));
        Thread.sleep(5L);
        SubjectQuotaLevel found = provider.find(TENANT, "vip");

        assertNotNull(found, "惰性刷新后应能读到新增的档");
        // admin 刻意不开 @EnableScheduling，快照只能靠读路径更新；不重载的话后台改完档永远不生效
        assertEquals(true, store.loads.get() > afterConstruct, "到点后读路径应触发一次重载");
    }
}
