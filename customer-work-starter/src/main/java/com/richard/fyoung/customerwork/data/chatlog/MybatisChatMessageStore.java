package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.data.chatlog.entity.ChatMessageDO;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 聊天消息存储（{@code customer-work.chat-log.store-mode=jdbc} 时启用）。
 *
 * <p>写入 {@code cw_chat_message} 表，自增主键由 MyBatis 回填。历史查询用 {@code ORDER BY id DESC LIMIT}
 * 取最新一页后在内存翻转为升序（游标翻页）。建表由统一 Flyway 迁移负责，本类不建表。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisChatMessageStore implements ChatMessageStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisChatMessageStore.class);

    private final ChatMessageMapper chatMessageMapper;

    public MybatisChatMessageStore(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public ChatMessage append(ChatMessage message) {
        try {
            ChatMessageDO record = toDO(message);
            chatMessageMapper.insert(record);
            return message.withId(record.getId());
        } catch (Exception e) {
            log.error("chat message append failed, code={}, messageId={}",
                "CHATLOG-APPEND-FAIL", message.messageId(), e);
            throw new IllegalStateException("failed to append chat message: " + message.messageId(), e);
        }
    }

    @Override
    public List<ChatMessage> findBySession(String sessionId, Long beforeId, int limit) {
        try {
            List<ChatMessageDO> desc = chatMessageMapper.findBySessionPage(sessionId, beforeId, Math.max(limit, 0));
            return toAscList(desc);
        } catch (Exception e) {
            log.error("chat message query failed, code={}, dim={}, value={}",
                "CHATLOG-FINDBYSESSION-FAIL", "session_id", sessionId, e);
            return List.of();
        }
    }

    @Override
    public List<ChatMessage> findByTicket(String ticketId, Long beforeId, int limit) {
        try {
            List<ChatMessageDO> desc = chatMessageMapper.findByTicketPage(ticketId, beforeId, Math.max(limit, 0));
            return toAscList(desc);
        } catch (Exception e) {
            log.error("chat message query failed, code={}, dim={}, value={}",
                "CHATLOG-FINDBYTICKET-FAIL", "ticket_id", ticketId, e);
            return List.of();
        }
    }

    /** DESC 结果 → 领域对象并翻转为 id 升序返回（沿用旧游标翻页语义）。 */
    private List<ChatMessage> toAscList(List<ChatMessageDO> desc) {
        List<ChatMessage> list = desc.stream().map(this::toMessage).collect(Collectors.toList());
        Collections.reverse(list);
        return list;
    }

    private ChatMessageDO toDO(ChatMessage message) {
        ChatMessageDO record = new ChatMessageDO();
        record.setMessageId(message.messageId());
        record.setSessionId(message.sessionId());
        record.setTicketId(message.ticketId());
        record.setSenderType(message.senderType().name());
        record.setSenderId(message.senderId());
        record.setContent(message.content());
        record.setCreatedAtMs(message.createdAtMs());
        return record;
    }

    private ChatMessage toMessage(ChatMessageDO record) {
        return new ChatMessage(
            record.getId(),
            record.getMessageId(),
            record.getSessionId(),
            record.getTicketId(),
            TicketActorType.valueOf(record.getSenderType()),
            record.getSenderId(),
            record.getContent(),
            record.getCreatedAtMs() == null ? 0L : record.getCreatedAtMs());
    }
}
