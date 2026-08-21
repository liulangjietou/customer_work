package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内等级存储（默认实现）。
 *
 * <p>不预置任何等级：内置兜底档由 {@code SubjectQuotaProperties} 提供，两处都塞一份
 * 只会让"到底哪份生效"变成一个需要翻代码才能回答的问题。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySubjectQuotaLevelStore implements SubjectQuotaLevelStore {

    /** key = tenantId + '\n' + levelCode，用不可能出现在两者中的分隔符拼，避免拼接歧义。 */
    private final Map<String, SubjectQuotaLevel> levels = new ConcurrentHashMap<>();

    @Override
    public Optional<List<SubjectQuotaLevel>> findAllEnabled() {
        return Optional.of(levels.values().stream().filter(SubjectQuotaLevel::enabled).toList());
    }

    @Override
    public List<SubjectQuotaLevel> findByTenant(String tenantId) {
        return levels.values().stream()
            .filter(level -> TenantContext.sameTenant(level.tenantId(), tenantId))
            .toList();
    }

    @Override
    public void save(SubjectQuotaLevel level) {
        levels.put(key(level.tenantId(), level.levelCode()), level);
    }

    @Override
    public void delete(String tenantId, String levelCode) {
        levels.remove(key(tenantId, levelCode));
    }

    private static String key(String tenantId, String levelCode) {
        return TenantContext.normalizedTenantKey(tenantId) + '\n' + levelCode.toLowerCase(Locale.ROOT);
    }
}
