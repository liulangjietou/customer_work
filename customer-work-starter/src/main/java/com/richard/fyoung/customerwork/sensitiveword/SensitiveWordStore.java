package com.richard.fyoung.customerwork.sensitiveword;

import java.util.List;
import java.util.Optional;

/**
 * 敏感词表存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemorySensitiveWordStore}（进程内、带演示种子，离线可测）；生产可切
 * {@link MybatisSensitiveWordStore}（{@code sensitive-word.store-mode=jdbc}），或下游声明同类型 Bean 覆盖。
 * {@link SensitiveWordFilter} 通过本 SPI 拉取启用词表构建自动机，词表变更后调用 {@code reload()} 热重建。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordStore {

    /** 全部词（含停用）。 */
    List<SensitiveWord> findAll();

    /**
     * 仅启用的词（构建自动机用）。
     *
     * <p><b>成败必须可区分</b>（fail-closed 契约的关键）：{@code Optional.of(list)} 表示<b>读取成功</b>
     * （空 list 也是合法的"没配词"）；{@code Optional.empty()} 表示<b>读取失败</b>（DB 不可达等）。
     * {@link SensitiveWordFilter#reload()} 据此分流——绝不把"读失败"当成"没词"而静默放行。</p>
     */
    Optional<List<SensitiveWord>> findEnabled();

    /** 保存（新建或更新）一个词。 */
    void save(SensitiveWord word);
}
