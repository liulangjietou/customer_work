package com.richard.fyoung.customerwork.core.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Harness 分层记忆存储（{@code customer-work.harness.memory-store-mode=memory} 时装配）。
 *
 * <p>离线可测，但重启即清空、多副本各存各的——这正是引入本 SPI 要解决的问题，
 * 生产请用默认的 {@link MybatisHarnessMemoryStore}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryHarnessMemoryStore implements HarnessMemoryStore {

    private final Map<String, String> memories = new ConcurrentHashMap<>();

    @Override
    public Optional<String> load(String scopeId) {
        return Optional.ofNullable(memories.get(scopeId));
    }

    @Override
    public void save(String scopeId, String content) {
        if (content == null) {
            memories.remove(scopeId);
            return;
        }
        memories.put(scopeId, content);
    }

    @Override
    public void delete(String scopeId) {
        memories.remove(scopeId);
    }
}
