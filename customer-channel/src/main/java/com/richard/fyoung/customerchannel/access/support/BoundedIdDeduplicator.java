package com.richard.fyoung.customerchannel.access.support;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界 LRU 消息去重器（线程安全）。
 *
 * <p>用于防止渠道回调重试导致同一条消息被重复投递到管道（如微信 5 秒未应答会重试最多 3 次）。
 * 基于访问序 {@link LinkedHashMap} 实现固定容量的 LRU：超过容量时淘汰最久未见的 id。空/空白 id
 * 一律视为「首次出现」（不参与去重，交由上层按业务决定），避免误吞无 id 的消息。</p>
 * @author owlzhangfq@gmail.com
 */
public class BoundedIdDeduplicator {

    /** 占位值（只用 key 集合，value 无意义）。 */
    private static final Object PRESENT = new Object();

    private final Map<String, Object> seen;

    public BoundedIdDeduplicator(int capacity) {
        int cap = capacity <= 0 ? 1 : capacity;
        this.seen = Collections.synchronizedMap(new LinkedHashMap<String, Object>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > cap;
            }
        });
    }

    /**
     * 记录并判定 id 是否首次出现。
     *
     * @param id 消息唯一标识
     * @return {@code true} 表示首次出现（应处理）；{@code false} 表示重复（应跳过）
     */
    public boolean firstSeen(String id) {
        if (!StringUtils.hasText(id)) {
            return true;
        }
        // put 返回旧值：null 说明本次是首次
        return seen.put(id, PRESENT) == null;
    }
}
