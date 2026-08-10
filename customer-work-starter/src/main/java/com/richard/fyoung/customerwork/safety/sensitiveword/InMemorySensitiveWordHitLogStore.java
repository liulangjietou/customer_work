package com.richard.fyoung.customerwork.safety.sensitiveword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 进程内命中日志（默认实现）：固定容量环形缓冲，满了丢最旧的。
 *
 * <p>命中日志是持续增长的流水，进程内实现必须有界，否则长跑必然吃满堆——所以这里不是"简化版"，
 * 而是刻意的有界语义：memory 模式只保证"最近 N 条可查"，要长期留存与统计就切 {@code store-mode=jdbc}。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemorySensitiveWordHitLogStore implements SensitiveWordHitLogStore {

    /** 环形缓冲容量：够运营现场看一段时间的命中，又不至于占内存。 */
    private static final int CAPACITY = 500;

    private final Deque<SensitiveWordHitRecord> buffer = new ArrayDeque<>(CAPACITY);

    @Override
    public synchronized void save(SensitiveWordHitRecord record) {
        if (record == null) {
            return;
        }
        if (buffer.size() >= CAPACITY) {
            buffer.removeLast();
        }
        buffer.addFirst(record);
    }

    @Override
    public synchronized List<SensitiveWordHitRecord> findRecent(int limit) {
        List<SensitiveWordHitRecord> result = new ArrayList<>(Math.min(limit, buffer.size()));
        for (SensitiveWordHitRecord record : buffer) {
            if (result.size() >= limit) {
                break;
            }
            result.add(record);
        }
        return result;
    }
}
