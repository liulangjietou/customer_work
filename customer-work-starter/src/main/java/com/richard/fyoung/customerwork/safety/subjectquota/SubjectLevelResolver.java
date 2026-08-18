package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把一个主体解析成它适用的那一档额度。
 *
 * <p>三步：定等级编码 → 查库里的等级快照 → 查不到回落配置里的内置档。</p>
 *
 * <p><b>等级编码怎么来</b>：登录用户查绑定（{@link SubjectLevelBinding}，落在 {@code cw_user.level_code}），
 * 没绑定就用默认档；匿名与接入方没有"个人档位"可言，各自走配置指定的一档。</p>
 *
 * <p><b>为什么内置档不是可有可无的兜底</b>：它让功能在"还没建等级表 / 还没配任何一档"时就能生效。
 * 否则开关一开却什么都不限，运维只能从"为什么没限住"倒查到"原来还要先去建数据"。</p>
 * @author owlzhangfq@gmail.com
 */
public class SubjectLevelResolver {

    private final SubjectQuotaLevelProvider levelProvider;
    private final SubjectLevelBinding levelBinding;
    private final SubjectQuotaProperties properties;

    /** 用户等级绑定的本地缓存：限流判定在每个请求的热路径上，不能每次都去查用户表。 */
    private final Map<String, CachedLevel> bindingCache = new ConcurrentHashMap<>();

    public SubjectLevelResolver(SubjectQuotaLevelProvider levelProvider,
                                SubjectLevelBinding levelBinding,
                                SubjectQuotaProperties properties) {
        this.levelProvider = levelProvider;
        this.levelBinding = levelBinding;
        this.properties = properties;
    }

    /**
     * 解析主体适用的等级。
     *
     * @return 适用等级；无任何可用配置时返回 {@code null}（= 该主体不受限）
     */
    public SubjectQuotaLevel resolve(QuotaSubject subject) {
        if (subject == null) {
            return null;
        }
        String tenantId = currentTenant();
        String levelCode = levelCodeOf(subject);
        if (levelCode == null || levelCode.isBlank()) {
            return null;
        }
        SubjectQuotaLevel configured = levelProvider.find(tenantId, levelCode);
        if (configured != null) {
            return configured;
        }
        // 该租户没配这一档：回落平台默认租户的同名等级，再回落内置档。
        // 中间这一跳让"平台统一配一次、各租户不必重复配"成立，同时保留租户覆盖的能力。
        SubjectQuotaLevel platformLevel = levelProvider.find(TenantContext.DEFAULT, levelCode);
        return platformLevel != null ? platformLevel : builtin(tenantId, levelCode);
    }

    /** 主体对应的等级编码（用户查绑定，其余按类型取配置档）。 */
    private String levelCodeOf(QuotaSubject subject) {
        return switch (subject.type()) {
            // 两类登录用户共用一套绑定查询：实现方按部署侧决定查哪张用户表
            // （客服端查 cw_user、后台查 sys_user），解析逻辑本身不需要知道这个区别
            case USER -> boundLevelOf(subject.id()).orElse(properties.getDefaultUserLevel());
            case ADMIN_USER -> boundLevelOf(subject.id()).orElse(properties.getDefaultAdminLevel());
            case IP -> properties.getAnonymousLevel();
            case API_KEY -> properties.getApiKeyLevel();
        };
    }

    /** 带 TTL 的绑定查询；缓存未命中才真正查库。 */
    private Optional<String> boundLevelOf(String userId) {
        long now = System.currentTimeMillis();
        CachedLevel cached = bindingCache.get(userId);
        if (cached != null && cached.expireAtMs > now) {
            return Optional.ofNullable(cached.levelCode);
        }
        // 缓存里"查过但没绑定"也要记（levelCode 为 null），否则未绑定的用户每次都会打一次库
        String resolved = levelBinding == null ? null : levelBinding.levelCodeOf(userId).orElse(null);
        if (bindingCache.size() > properties.getLevelCacheMaxSize()) {
            bindingCache.entrySet().removeIf(entry -> entry.getValue().expireAtMs <= now);
        }
        bindingCache.put(userId, new CachedLevel(resolved, now + properties.getLevelCacheTtlMs()));
        return Optional.ofNullable(resolved);
    }

    /** 后台改完等级绑定后主动失效，让新档位立刻生效而不必等 TTL。 */
    public void evictBinding(String userId) {
        if (userId != null) {
            bindingCache.remove(userId);
        }
    }

    /** 把配置里的内置档翻成领域对象；没有同名内置档则返回 null（= 不受限）。 */
    private SubjectQuotaLevel builtin(String tenantId, String levelCode) {
        Map<String, SubjectQuotaProperties.Level> builtins = properties.getBuiltinLevels();
        if (builtins == null) {
            return null;
        }
        SubjectQuotaProperties.Level level = builtins.get(levelCode);
        if (level == null) {
            return null;
        }
        return new SubjectQuotaLevel(null, tenantId, levelCode,
            level.getLevelName() == null ? levelCode : level.getLevelName(),
            QuotaSubjectType.parse(level.getSubjectType()),
            level.getWindowSeconds(),
            level.getTokenLimit(),
            level.getRequestLimit(),
            SubjectExceedAction.parse(level.getExceedAction()),
            true,
            "builtin");
    }

    /** 当前租户；无上下文（如内部任务、未开多租户）时按默认租户算。 */
    private static String currentTenant() {
        String tenantId = TenantContext.get();
        return tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
    }

    /** 缓存项：{@code levelCode} 允许为 null，表示"查过，确实没绑定"。 */
    private record CachedLevel(String levelCode, long expireAtMs) {
    }
}
