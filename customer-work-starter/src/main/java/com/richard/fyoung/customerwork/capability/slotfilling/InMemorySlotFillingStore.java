package com.richard.fyoung.customerwork.capability.slotfilling;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内槽位收集进度存储（默认实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全。由 {@link SlotFillingConfig} 按
 * {@code slot-filling.store-mode} 装配（默认 memory）；下游声明自己的 {@link SlotFillingStore}
 * Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySlotFillingStore implements SlotFillingStore {

    private final ConcurrentHashMap<String, SlotFillingProgress> store = new ConcurrentHashMap<>();

    @Override
    public void save(String key, SlotFillingProgress progress) {
        if (key == null || progress == null) {
            return;
        }
        store.put(key, progress);
    }

    @Override
    public Optional<SlotFillingProgress> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public SlotFillingProgress findOrCreate(String key) {
        return store.computeIfAbsent(key, k -> new SlotFillingProgress());
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }
}
