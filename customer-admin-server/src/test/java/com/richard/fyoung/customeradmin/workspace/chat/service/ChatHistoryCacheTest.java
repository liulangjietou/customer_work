package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatHistoryCache} 单测：会话消息缓存的命中/未命中/写入/失效四种路径，以及 Redis 不可达时不抛异常
 * （缓存故障不能影响主流程，见类 Javadoc）。会话列表已改成 SQL 分页、不再走缓存。
 * @author owlzhangfq@gmail.com
 */
class ChatHistoryCacheTest {

    private JedisPool jedisPool;
    private Jedis jedis;
    private ChatHistoryCache cache;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        cache = new ChatHistoryCache(jedisPool);
    }

    @Test
    void getMessages_shouldReturnEmpty_whenCacheMiss() {
        when(jedis.get("admin:chat:messages:agent-1:s1")).thenReturn(null);

        assertTrue(cache.getMessages("agent-1", "s1").isEmpty());
    }

    @Test
    void putMessages_thenGetMessages_shouldRoundTrip() {
        List<ChatMessageVO> messages = List.of(new ChatMessageVO("user", "你好", "2026-07-08 12:00:00"));
        String[] stored = new String[1];
        when(jedis.setex(eq("admin:chat:messages:agent-1:s1"), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                stored[0] = invocation.getArgument(2);
                return "OK";
            });

        cache.putMessages("agent-1", "s1", messages);
        when(jedis.get("admin:chat:messages:agent-1:s1")).thenAnswer(invocation -> stored[0]);

        assertEquals(messages, cache.getMessages("agent-1", "s1").orElseThrow());
    }

    @Test
    void evict_shouldDeleteMessagesKey() {
        cache.evict("agent-1", "s1");

        verify(jedis).del("admin:chat:messages:agent-1:s1");
    }

    @Test
    void getMessages_shouldReturnEmpty_notThrow_whenRedisUnreachable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        assertTrue(cache.getMessages("agent-1", "s1").isEmpty());
    }

    @Test
    void evict_shouldNotThrow_whenRedisUnreachable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        cache.evict("agent-1", "s1");
    }
}
