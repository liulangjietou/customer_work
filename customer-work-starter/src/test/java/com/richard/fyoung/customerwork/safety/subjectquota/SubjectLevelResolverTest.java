package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 等级解析单测：绑定优先、按主体类型取配置档、租户覆盖平台档、回落内置档、绑定缓存与失效。
 * @author owlzhangfq@gmail.com
 */
class SubjectLevelResolverTest {

    private static final String TENANT = "acme";

    private final InMemorySubjectQuotaLevelStore store = new InMemorySubjectQuotaLevelStore();
    private final SubjectQuotaProperties properties = new SubjectQuotaProperties();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SubjectLevelResolver resolver(SubjectLevelBinding binding) {
        return new SubjectLevelResolver(new SubjectQuotaLevelProvider(store, false), binding, properties);
    }

    private void saveLevel(String tenantId, String levelCode, long tokenLimit) {
        store.save(new SubjectQuotaLevel(null, tenantId, levelCode, levelCode, QuotaSubjectType.USER,
            1800, tokenLimit, 0, SubjectExceedAction.BLOCK, true, null));
    }

    @Test
    void resolve_shouldPreferBoundLevel_forUser() {
        TenantContext.set(TENANT);
        saveLevel(TENANT, "vip", 999);
        SubjectQuotaLevel level = resolver(userId -> Optional.of("vip")).resolve(QuotaSubject.user("U-1"));
        assertEquals("vip", level.levelCode(), "用户绑定的等级优先于默认档");
    }

    @Test
    void resolve_shouldFallBackToDefaultUserLevel_whenNotBound() {
        TenantContext.set(TENANT);
        saveLevel(TENANT, "free", 111);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1"));
        assertEquals("free", level.levelCode(), "未绑定的用户走配置里的默认档");
    }

    @Test
    void resolve_shouldUseAnonymousLevel_forIpSubject() {
        TenantContext.set(TENANT);
        SubjectQuotaLevel level = resolver(userId -> Optional.of("vip")).resolve(QuotaSubject.ip("1.2.3.4"));
        assertEquals("anonymous", level.levelCode(), "匿名主体不看用户绑定，走匿名档");
    }

    @Test
    void resolve_shouldUseApiKeyLevel_forApiKeySubject() {
        TenantContext.set(TENANT);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.apiKey("sk-abc"));
        assertEquals("api-key", level.levelCode());
    }

    @Test
    void resolve_shouldPreferTenantLevel_overPlatformLevel() {
        TenantContext.set(TENANT);
        saveLevel(TenantContext.DEFAULT, "free", 100);
        saveLevel(TENANT, "free", 500);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1"));
        assertEquals(500L, level.tokenLimit(), "租户自己配的那一档优先于平台默认租户的同名档");
    }

    @Test
    void resolve_shouldFallBackToPlatformLevel_whenTenantHasNone() {
        TenantContext.set(TENANT);
        saveLevel(TenantContext.DEFAULT, "free", 100);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1"));
        assertEquals(100L, level.tokenLimit(), "租户没配时回落平台默认租户，免去每个租户重复配一遍");
    }

    @Test
    void resolve_shouldFallBackToBuiltinLevel_whenStoreEmpty() {
        TenantContext.set(TENANT);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1"));
        assertNotNull(level, "库里没有任何等级时必须回落内置档，否则开关一开却什么都不限");
        assertEquals("free", level.levelCode());
        assertEquals(50000L, level.tokenLimit(), "内置档数值须与出厂种子一致");
    }

    @Test
    void resolve_shouldReturnNull_whenNoBuiltinMatches() {
        TenantContext.set(TENANT);
        properties.setBuiltinLevels(new HashMap<>());
        assertNull(resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1")),
            "既没配也没内置档时返回 null（= 该主体不受限）");
    }

    @Test
    void resolve_shouldCacheBinding_untilEvicted() {
        TenantContext.set(TENANT);
        saveLevel(TENANT, "vip", 1);
        AtomicInteger lookups = new AtomicInteger();
        SubjectLevelResolver resolver = resolver(userId -> {
            lookups.incrementAndGet();
            return Optional.of("vip");
        });

        resolver.resolve(QuotaSubject.user("U-1"));
        resolver.resolve(QuotaSubject.user("U-1"));
        assertEquals(1, lookups.get(), "绑定查询必须走缓存——它在每个请求的热路径上");

        resolver.evictBinding("U-1");
        resolver.resolve(QuotaSubject.user("U-1"));
        assertEquals(2, lookups.get(), "主动失效后应重新查一次");
    }

    @Test
    void resolve_shouldCacheAbsentBinding() {
        TenantContext.set(TENANT);
        AtomicInteger lookups = new AtomicInteger();
        SubjectLevelResolver resolver = resolver(userId -> {
            lookups.incrementAndGet();
            return Optional.empty();
        });

        resolver.resolve(QuotaSubject.user("U-1"));
        resolver.resolve(QuotaSubject.user("U-1"));
        // "查过但没绑定"也要缓存，否则未绑定的用户每个请求都打一次库——那是绝大多数用户
        assertEquals(1, lookups.get(), "未绑定的结果同样要缓存");
    }

    @Test
    void resolve_shouldUseDefaultTenant_whenContextMissing() {
        saveLevel(TenantContext.DEFAULT, "free", 66);
        Map<String, SubjectQuotaProperties.Level> none = new HashMap<>();
        properties.setBuiltinLevels(none);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.user("U-1"));
        assertEquals(66L, level.tokenLimit(), "没有租户上下文时按默认租户算，而不是直接不限");
    }

    @Test
    void resolve_shouldUseAdminDefaultLevel_forAdminSubject() {
        TenantContext.set(TENANT);
        store.save(new SubjectQuotaLevel(null, TENANT, "admin-default", "后台用户",
            QuotaSubjectType.ADMIN_USER, 3600, 2000000L, 200, SubjectExceedAction.BLOCK, true, null));
        // 后台用户不看客服端那份绑定（binding 由部署侧决定查哪张用户表），未绑定时走 admin 默认档
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.adminUser("7"));
        assertEquals("admin-default", level.levelCode());
        assertEquals(3600, level.effectiveWindowSeconds(), "后台窗口是 1 小时，与 C 端的 30 分钟不同");
    }

    @Test
    void resolve_shouldPreferBoundLevel_forAdminSubject() {
        TenantContext.set(TENANT);
        store.save(new SubjectQuotaLevel(null, TENANT, "admin-power", "后台高配",
            QuotaSubjectType.ADMIN_USER, 3600, 9L, 9, SubjectExceedAction.BLOCK, true, null));
        SubjectQuotaLevel level = resolver(userId -> Optional.of("admin-power")).resolve(QuotaSubject.adminUser("7"));
        assertEquals("admin-power", level.levelCode(), "单独提档的后台账号优先走绑定");
    }

    @Test
    void resolve_shouldFallBackToBuiltinAdminLevel() {
        TenantContext.set(TENANT);
        SubjectQuotaLevel level = resolver(userId -> Optional.empty()).resolve(QuotaSubject.adminUser("7"));
        assertNotNull(level, "库里没配也要有内置的后台档，否则开关一开却什么都不限");
        assertEquals("admin-default", level.levelCode());
        assertEquals(2000000L, level.tokenLimit(), "内置档数值须与出厂种子一致");
    }
}
