package com.richard.fyoung.customerwork.safety.sensitiveword;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 进程内敏感词表（默认实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全；构造时加载与 {@code customer-work-schema.sql} 一致的
 * <b>脱敏演示种子</b>（占位词，非真实违禁词），使 memory 模式下无需数据库即可演示拦截/打码/复核三种动作。
 * 以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link SensitiveWordStore} 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySensitiveWordStore implements SensitiveWordStore {

    private final ConcurrentHashMap<Long, SensitiveWord> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    public InMemorySensitiveWordStore() {
        for (SensitiveWord seed : SensitiveWordSeeds.demoWords()) {
            save(seed);
        }
    }

    @Override
    public List<SensitiveWord> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public Optional<List<SensitiveWord>> findEnabled() {
        // 进程内实现读取不会失败，恒返回 Optional.of（哪怕空 list）
        return Optional.of(store.values().stream()
            .filter(SensitiveWord::isEnabled)
            .collect(Collectors.toList()));
    }

    @Override
    public void save(SensitiveWord word) {
        if (word == null || word.getWord() == null || word.getWord().isEmpty()) {
            return;
        }
        Long id = word.getId() == null ? idGen.incrementAndGet() : word.getId();
        store.put(id, new SensitiveWord(id, word.getWord(), word.getCategory(), word.getAction(), word.isEnabled()));
    }
}
