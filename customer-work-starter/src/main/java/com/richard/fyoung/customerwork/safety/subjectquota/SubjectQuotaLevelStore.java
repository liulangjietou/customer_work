package com.richard.fyoung.customerwork.safety.subjectquota;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 配额等级存储 SPI（照既有 Store SPI 模式：接口 + InMemory 默认 + MyBatis-Plus 实现）。
 *
 * <p><b>读失败是 fail-open</b>：{@link #findAllEnabled()} 返回 empty 时
 * {@link SubjectQuotaLevelProvider} 保留上次快照，从未加载成功则回退配置文件里的内置档。
 * 与限流规则同向、与敏感词反向：读不到等级就把所有人限死是自伤，而漏放只是这段时间没限住。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SubjectQuotaLevelStore {

    /**
     * 全部启用的等级（跨租户，供快照加载）。
     *
     * <p>{@code Optional.of(list)} 表示读取成功（空 list 是合法的"没配等级"），
     * {@code Optional.empty()} 表示读取失败（库不可达等）。</p>
     */
    Optional<List<SubjectQuotaLevel>> findAllEnabled();

    /** 指定租户的全部等级（含停用），供后台展示。 */
    List<SubjectQuotaLevel> findByTenant(String tenantId);

    /** 保存或更新（按 tenantId + levelCode 唯一）。 */
    void save(SubjectQuotaLevel level);

    /** 删除某租户的某个等级。 */
    void delete(String tenantId, String levelCode);

    /** 等级版本指纹：供 Provider 判断是否需要换快照；{@code empty} 表示读取失败。 */
    default Optional<String> fingerprint() {
        return findAllEnabled().map(levels -> {
            List<String> items = new ArrayList<>(levels.size());
            for (SubjectQuotaLevel level : levels) {
                items.add(level.tenantId() + '|' + level.levelCode() + '|' + level.subjectType()
                    + '|' + level.windowSeconds() + '|' + level.tokenLimit()
                    + '|' + level.requestLimit() + '|' + level.exceedAction());
            }
            Collections.sort(items);
            return levels.size() + ":" + Integer.toHexString(String.join(";", items).hashCode());
        });
    }
}
