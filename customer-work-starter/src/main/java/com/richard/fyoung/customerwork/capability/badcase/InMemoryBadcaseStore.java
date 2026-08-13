package com.richard.fyoung.customerwork.capability.badcase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 badcase 存储（默认实现，离线可测）。
 *
 * <p>重启即清空待筛队列，仅适合单测与本地试跑；生产切 {@code badcase.store-mode=jdbc}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryBadcaseStore implements BadcaseStore {

    /** 时间倒序：最新的 badcase 排在最前，运营先看最近翻的车。 */
    private static final Comparator<Badcase> NEWEST_FIRST =
        Comparator.comparingLong(Badcase::getCreatedAtMs).reversed();

    private final Map<String, Badcase> badcases = new ConcurrentHashMap<>();

    @Override
    public void save(Badcase badcase) {
        if (badcase == null || badcase.getId() == null) {
            return;
        }
        badcases.put(badcase.getId(), badcase);
    }

    @Override
    public Optional<Badcase> find(String id) {
        return Optional.ofNullable(badcases.get(id));
    }

    @Override
    public List<Badcase> query(BadcaseQuery query) {
        List<Badcase> matched = filter(query.status(), query.source());
        matched.sort(NEWEST_FIRST);
        int from = Math.min(Math.max(query.offset(), 0), matched.size());
        int to = Math.min(from + Math.max(query.limit(), 0), matched.size());
        return List.copyOf(matched.subList(from, to));
    }

    @Override
    public long count(BadcaseStatus status, BadcaseSource source) {
        return filter(status, source).size();
    }

    /** null 条件表示不限，与 SQL 侧的动态 WHERE 语义保持一致。 */
    private List<Badcase> filter(BadcaseStatus status, BadcaseSource source) {
        List<Badcase> matched = new ArrayList<>();
        for (Badcase badcase : badcases.values()) {
            if (status != null && badcase.getStatus() != status) {
                continue;
            }
            if (source != null && badcase.getSource() != source) {
                continue;
            }
            matched.add(badcase);
        }
        return matched;
    }
}
