package com.richard.fyoung.customerchannel.access.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedIdDeduplicator} 去重与 LRU 淘汰测试。
 * @author owlzhangfq@gmail.com
 */
class BoundedIdDeduplicatorTest {

    @Test
    void shouldReportFirstSeenThenDuplicate() {
        BoundedIdDeduplicator dedup = new BoundedIdDeduplicator(16);

        assertTrue(dedup.firstSeen("m1"), "首次出现");
        assertFalse(dedup.firstSeen("m1"), "重复出现");
        assertTrue(dedup.firstSeen("m2"), "不同 id 首次出现");
    }

    @Test
    void shouldTreatBlankIdAsAlwaysFirstSeen() {
        BoundedIdDeduplicator dedup = new BoundedIdDeduplicator(16);

        assertTrue(dedup.firstSeen(null));
        assertTrue(dedup.firstSeen(""));
        assertTrue(dedup.firstSeen("   "));
    }

    @Test
    void shouldEvictEldestBeyondCapacity() {
        BoundedIdDeduplicator dedup = new BoundedIdDeduplicator(2);

        assertTrue(dedup.firstSeen("a"));
        assertTrue(dedup.firstSeen("b"));
        // 容量 2，加入 c 淘汰最久未见的 a
        assertTrue(dedup.firstSeen("c"));
        // a 已被淘汰，再次出现视为首次
        assertTrue(dedup.firstSeen("a"), "被淘汰后再次出现应为首次");
        // b 仍在（且刚才 c/a 未访问 b，但 b 尚未越界淘汰前仍应记得——此处只断言 c 仍在）
        assertFalse(dedup.firstSeen("c"), "c 仍在缓存内");
    }
}
