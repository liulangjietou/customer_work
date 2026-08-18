package com.richard.fyoung.customerwork.safety.subjectquota;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内命中记录（默认实现）。
 *
 * <p>有界环形：只保留最近若干条，超出即丢最老的。命中记录是可丢的观测数据，
 * 为它把内存吃满才是真的事故；要长期留存就把 {@code store-mode} 切成 jdbc。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySubjectQuotaHitStore implements SubjectQuotaHitStore {

    /** 保留的最大记录数：够撑起一次排障回看，又不至于占多少内存。 */
    private static final int MAX_RECORDS = 2000;

    private final Deque<SubjectQuotaHit> records = new ArrayDeque<>();

    @Override
    public synchronized void record(SubjectQuotaHit hit) {
        records.addLast(hit);
        while (records.size() > MAX_RECORDS) {
            records.removeFirst();
        }
    }

    @Override
    public synchronized List<SubjectQuotaHit> findRecent(String tenantId, long sinceMs, int limit) {
        List<SubjectQuotaHit> matched = new ArrayList<>();
        for (SubjectQuotaHit hit : records) {
            if (hit.tenantId().equals(tenantId) && hit.createdAtMs() >= sinceMs) {
                matched.add(hit);
            }
        }
        matched.sort(Comparator.comparingLong(SubjectQuotaHit::createdAtMs).reversed());
        return matched.size() > limit ? matched.subList(0, limit) : matched;
    }

    @Override
    public synchronized List<SubjectQuotaHitRank> rank(String tenantId, long sinceMs, int limit) {
        Map<String, SubjectQuotaHitRank> grouped = new LinkedHashMap<>();
        for (SubjectQuotaHit hit : records) {
            if (!hit.tenantId().equals(tenantId) || hit.createdAtMs() < sinceMs) {
                continue;
            }
            SubjectQuotaHitRank row = grouped.computeIfAbsent(
                hit.subjectType().name() + '\n' + hit.subjectId(), key -> newRank(hit));
            row.setHitCount(row.getHitCount() + 1);
            row.setLastHitAtMs(Math.max(row.getLastHitAtMs(), hit.createdAtMs()));
        }
        List<SubjectQuotaHitRank> ranked = new ArrayList<>(grouped.values());
        ranked.sort(Comparator.comparingLong(SubjectQuotaHitRank::getHitCount).reversed());
        return ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    }

    private static SubjectQuotaHitRank newRank(SubjectQuotaHit hit) {
        SubjectQuotaHitRank row = new SubjectQuotaHitRank();
        row.setSubjectType(hit.subjectType().name());
        row.setSubjectId(hit.subjectId());
        row.setLevelCode(hit.levelCode());
        row.setHitCount(0L);
        row.setLastHitAtMs(0L);
        return row;
    }
}
