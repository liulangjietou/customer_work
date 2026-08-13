package com.richard.fyoung.customerwork.capability.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内提示词版本存储（默认实现）。
 * @author owlzhangfq@gmail.com
 */
public class InMemoryPromptVersionStore implements PromptVersionStore {

    private static final Comparator<PromptVersion> NEWEST_FIRST =
        Comparator.comparingLong(PromptVersion::capturedAtMs).reversed();

    private final Map<String, PromptVersion> versions = new ConcurrentHashMap<>();

    @Override
    public void record(PromptVersion version) {
        if (version == null || version.fingerprint().isEmpty()) {
            return;
        }
        // putIfAbsent 而非 put：重启或多副本会重复观测到同一版，保留最早那次才是"这版何时上线"
        versions.putIfAbsent(version.fingerprint(), version);
    }

    @Override
    public Optional<PromptVersion> find(String fingerprint) {
        return Optional.ofNullable(versions.get(fingerprint));
    }

    @Override
    public List<PromptVersion> findRecent(int limit) {
        List<PromptVersion> all = new ArrayList<>(versions.values());
        all.sort(NEWEST_FIRST);
        return List.copyOf(all.subList(0, Math.min(Math.max(limit, 0), all.size())));
    }
}
