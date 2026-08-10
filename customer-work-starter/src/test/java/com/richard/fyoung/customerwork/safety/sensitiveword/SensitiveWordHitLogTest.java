package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命中日志单测：记录构造（词/类目/片段截断）、进程内环形缓冲有界、异步 Sink 投递。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordHitLogTest {

    private SensitiveWordFilter filter() {
        return new SensitiveWordFilter(new InMemorySensitiveWordStore(), '*', SensitiveWordAction.BLOCK);
    }

    @Test
    void record_shouldCarryHitWordsAndCategories() {
        SensitiveWordFilterResult result = filter().check("你好测试敏感词A再见");

        SensitiveWordHitRecord record = SensitiveWordHitRecord.of(SensitiveWordHitDirection.INBOUND,
            result, "agent-x", "sess-1", "user-1", 64);

        assertEquals(SensitiveWordAction.BLOCK, record.action());
        assertTrue(record.words().contains("测试敏感词A"), "命中词应落在记录里");
        assertTrue(record.categories().contains("CUSTOM"));
        assertEquals("sess-1", record.sessionId());
        assertEquals("agent-x", record.agentName());
    }

    @Test
    void record_shouldTruncateSnippet() {
        String longText = "测试敏感词A" + "填充".repeat(100);
        SensitiveWordFilterResult result = filter().check(longText);

        SensitiveWordHitRecord record = SensitiveWordHitRecord.of(SensitiveWordHitDirection.INBOUND,
            result, "agent-x", null, null, 10);

        assertEquals(10, record.snippet().length(), "片段应按配置截断");
    }

    @Test
    void record_shouldDropSnippet_whenMaxLengthNotPositive() {
        SensitiveWordFilterResult result = filter().check("测试敏感词A");

        SensitiveWordHitRecord record = SensitiveWordHitRecord.of(SensitiveWordHitDirection.OUTBOUND,
            result, "agent-x", null, null, 0);

        assertNull(record.snippet(), "片段长度上限非正表示不留存原文");
    }

    @Test
    void inMemoryStore_shouldBeBounded() {
        InMemorySensitiveWordHitLogStore store = new InMemorySensitiveWordHitLogStore();
        for (int i = 0; i < 600; i++) {
            store.save(new SensitiveWordHitRecord(SensitiveWordHitDirection.INBOUND, SensitiveWordAction.BLOCK,
                List.of("w" + i), List.of("CUSTOM"), "agent", "sess", "user", "snippet", i));
        }

        List<SensitiveWordHitRecord> recent = store.findRecent(1000);

        assertEquals(500, recent.size(), "进程内实现必须有界，否则长跑会吃满堆");
        assertEquals("w599", recent.get(0).words().get(0), "最新的在最前");
    }

    @Test
    void asyncSink_shouldDeliverToStore() throws InterruptedException {
        InMemorySensitiveWordHitLogStore store = new InMemorySensitiveWordHitLogStore();
        AsyncSensitiveWordHitSink sink = new AsyncSensitiveWordHitSink(store, 16);

        sink.emit(new SensitiveWordHitRecord(SensitiveWordHitDirection.INBOUND, SensitiveWordAction.MASK,
            List.of("竞品XX"), List.of("COMPETITOR"), "agent", "sess", "user", "片段", 1L));

        // 异步落库：给后台线程一点时间
        for (int i = 0; i < 50 && store.findRecent(1).isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertEquals(1, store.findRecent(10).size());
        sink.destroy();
    }
}
