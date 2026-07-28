package com.richard.fyoung.customerwork.sensitiveword;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 词表版本指纹（供 {@link SensitiveWordRefresher} 判断"是否真的变了"，避免每轮无谓重建自动机）。
     *
     * <p>语义与 {@link #findEnabled()} 严格对齐：{@code Optional.of(fp)} 表示读取成功，{@code Optional.empty()}
     * 表示读取失败（DB 不可达等）——刷新器读失败时<b>不重建</b>，保留当前已加载的好词表。指纹只要满足
     * "内容变则指纹必变"，不要求全局唯一。</p>
     *
     * <p>默认实现按启用词内容算指纹（排序后拼串取 hash），进程内实现零成本、直接可用；JDBC 实现应覆写为
     * 一条轻量聚合 SQL，避免每轮把整张词表拉回进程。</p>
     */
    default Optional<String> fingerprint() {
        return findEnabled().map(words -> {
            // 排序保证指纹稳定：Map.values() 之类的迭代顺序不保证，不排序会导致指纹抖动、白白重建自动机
            List<String> items = new ArrayList<>(words.size());
            for (SensitiveWord w : words) {
                items.add(w.getWord() + '|' + w.getCategory() + '|' + w.getAction());
            }
            Collections.sort(items);
            return words.size() + ":" + Integer.toHexString(String.join(";", items).hashCode());
        });
    }
}
