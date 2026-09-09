package com.richard.fyoung.customerwork.infra.ws;

import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入站消息去重。
 *
 * @author owlzhangfq@gmail.com
 */
class InboundMessageDeduplicatorTest {

    private final InboundMessageDeduplicator dedup =
        new InboundMessageDeduplicator(new InMemoryWindowCounter(), 300);

    @Test
    @DisplayName("同一条消息重发只放行第一次")
    void deduplicatesRepeatedDelivery() {
        assertFalse(dedup.isDuplicate("u1", "msg-1"));
        assertTrue(dedup.isDuplicate("u1", "msg-1"));
        assertTrue(dedup.isDuplicate("u1", "msg-1"));
    }

    @Test
    @DisplayName("不同消息互不影响")
    void differentMessagesPass() {
        assertFalse(dedup.isDuplicate("u1", "msg-1"));
        assertFalse(dedup.isDuplicate("u1", "msg-2"));
    }

    /**
     * 键必须带用户。
     *
     * <p>客户端生成的 id 只在自己那边唯一，两个用户撞上同一个值不是不可能——
     * 不分用户的话，A 发过的消息会把 B 的同 id 消息直接吞掉，而 B 永远等不到回复。</p>
     */
    @Test
    @DisplayName("不同用户的相同消息标识不互相吞没")
    void keysAreScopedByUser() {
        assertFalse(dedup.isDuplicate("u1", "same-id"));
        assertFalse(dedup.isDuplicate("u2", "same-id"),
            "另一个用户的消息被当成重复丢掉了——去重键没有按用户隔离");
    }

    /**
     * 老客户端不带这个字段。
     *
     * <p>为此拒收会让升级服务端变成一次前端强制更新，而去重是增益不是安全边界。</p>
     */
    @Test
    @DisplayName("没有消息标识时一律放行")
    void passesWhenClientMsgIdMissing() {
        assertFalse(dedup.isDuplicate("u1", null));
        assertFalse(dedup.isDuplicate("u1", ""));
        assertFalse(dedup.isDuplicate("u1", "   "));
        assertFalse(dedup.isDuplicate("u1", null), "缺标识的消息之间不该互相去重");
    }

    @Test
    @DisplayName("缺用户标识时放行，不写入任何计数")
    void passesWhenSubjectMissing() {
        assertFalse(dedup.isDuplicate(null, "msg-1"));
        assertFalse(dedup.isDuplicate("", "msg-1"));
        assertFalse(dedup.isDuplicate("u1", "msg-1"), "前面的调用不该污染正常用户的计数");
    }

    /**
     * 计数器故障时 fail-open。
     *
     * <p>方向是刻意的：去重失效的代价是重复回答一次，而 fail-closed 会让计数器一挂
     * 全部消息都发不出去——那是把一个旁路增益变成了单点故障。</p>
     */
    @Test
    @DisplayName("计数器故障时按首次处理，不阻断消息")
    void failsOpenWhenCounterBroken() {
        WindowCounter broken = new InMemoryWindowCounter() {
            @Override
            public long increment(String key, long delta, int windowSeconds) {
                throw new IllegalStateException("counter down");
            }
        };

        assertFalse(new InboundMessageDeduplicator(broken, 300).isDuplicate("u1", "msg-1"));
    }

    @Test
    @DisplayName("窗口配置非法时退到最小窗口，不抛异常")
    void normalizesWindow() {
        InboundMessageDeduplicator zero = new InboundMessageDeduplicator(new InMemoryWindowCounter(), 0);

        assertFalse(zero.isDuplicate("u1", "msg-1"));
        assertTrue(zero.isDuplicate("u1", "msg-1"), "同一秒内的重发仍应被识别");
    }
}
