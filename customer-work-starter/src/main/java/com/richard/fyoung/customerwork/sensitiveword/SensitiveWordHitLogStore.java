package com.richard.fyoung.customerwork.sensitiveword;

import java.util.List;

/**
 * 敏感词命中日志存储 SPI（持久化扩展点，照既有 Store SPI 模式）。
 *
 * <p>默认 {@link InMemorySensitiveWordHitLogStore}（进程内环形缓冲，离线可测）；
 * {@code sensitive-word.hit-log.store-mode=jdbc} 时落 {@link MybatisSensitiveWordHitLogStore}
 * （{@code cw_sensitive_word_hit_log} 表），供后台命中看板查询。</p>
 *
 * <p><b>写失败只吞不抛</b>：命中日志是旁路观测数据，落库失败绝不能影响对话主链路的拦截决策——
 * 这与词表读取的 fail-closed 是两件事，别混。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordHitLogStore {

    /** 保存一条命中记录（由异步 Sink 在后台线程调用）。 */
    void save(SensitiveWordHitRecord record);

    /** 最近若干条命中记录（新的在前），供 memory 模式自查与单测断言。 */
    List<SensitiveWordHitRecord> findRecent(int limit);
}
