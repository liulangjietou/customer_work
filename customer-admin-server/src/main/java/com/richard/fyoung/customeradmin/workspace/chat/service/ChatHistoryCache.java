package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Optional;

/**
 * 历史对话列表的 Redis 读缓存：30 分钟内重复打开同一智能体的历史列表 / 重新打开同一次历史会话，
 * 直接命中缓存，不用每次都枚举 {@code AgentStateStore#listSessionIds} 再逐个反序列化整段
 * {@code AgentState}。权威数据源始终是 {@code MysqlAgentStateStore}——本类只是 {@link ChatHistoryService}
 * 前面的一层可丢弃缓存，任何 Redis 异常都在这里兜住退化为"未命中"，不允许缓存故障影响主流程
 * （{@code ChatHistoryService} 据此照常回源 MySQL）。
 *
 * <p>失效策略：写路径不变（仍是 {@code MysqlAgentStateStore} 每轮同步落库），{@link ChatService}
 * 在一轮对话流式完成后主动调用 {@link #evict} 清掉该智能体的会话列表缓存与这次会话的消息缓存，
 * 保证"新建会话"或结束一轮对话对页面来说立即可见，不必等 30 分钟 TTL 自然过期。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ChatHistoryCache {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCache.class);
    private static final String KEY_PREFIX = "admin:chat:";
    private static final int TTL_SECONDS = 30 * 60;

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHistoryCache(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public Optional<List<ChatSessionSummary>> getSessions(String agentCode) {
        return read(sessionsKey(agentCode), new TypeReference<List<ChatSessionSummary>>() {
        });
    }

    public void putSessions(String agentCode, List<ChatSessionSummary> sessions) {
        write(sessionsKey(agentCode), sessions);
    }

    public Optional<List<ChatMessageVO>> getMessages(String agentCode, String sessionId) {
        return read(messagesKey(agentCode, sessionId), new TypeReference<List<ChatMessageVO>>() {
        });
    }

    public void putMessages(String agentCode, String sessionId, List<ChatMessageVO> messages) {
        write(messagesKey(agentCode, sessionId), messages);
    }

    /** 一轮对话完成后调用：该智能体的会话列表摘要（预览/消息数/时间）与这次会话的消息内容都已过期。 */
    public void evict(String agentCode, String sessionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(sessionsKey(agentCode), messagesKey(agentCode, sessionId));
        } catch (Exception e) {
            log.error("[workspace] chat history cache evict failed, code={}, agentCode={}",
                "CHAT_HISTORY_CACHE_EVICT_ERROR", agentCode, e);
        }
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.error("[workspace] chat history cache read failed, code={}, key={}",
                "CHAT_HISTORY_CACHE_READ_ERROR", key, e);
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, TTL_SECONDS, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            log.error("[workspace] chat history cache write failed, code={}, key={}",
                "CHAT_HISTORY_CACHE_WRITE_ERROR", key, e);
        }
    }

    private String sessionsKey(String agentCode) {
        return KEY_PREFIX + "sessions:" + agentCode;
    }

    private String messagesKey(String agentCode, String sessionId) {
        return KEY_PREFIX + "messages:" + agentCode + ":" + sessionId;
    }
}
