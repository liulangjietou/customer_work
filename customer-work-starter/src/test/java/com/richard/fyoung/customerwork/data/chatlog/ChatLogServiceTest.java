package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天日志服务单测（内存存储）：messageId 生成、会话/工单双维度历史、beforeId 游标翻页与 limit。
 * @author owlzhangfq@gmail.com
 */
class ChatLogServiceTest {

    private ChatLogService service;

    @BeforeEach
    void setUp() {
        service = new ChatLogService(new InMemoryChatMessageStore());
    }

    @Test
    void append_shouldGenerateMessageIdAndAutoIncrementId() {
        ChatMessage m = service.append("s1", "TK-1", TicketActorType.USER, "u1", "你好");
        assertTrue(m.messageId().startsWith("MSG-"));
        assertTrue(m.id() > 0);
        assertEquals("你好", m.content());
    }

    @Test
    void history_shouldSplitBySessionAndTicketDimension() {
        service.append("s1", "TK-1", TicketActorType.USER, "u1", "工单内消息");
        service.append("s1", null, TicketActorType.BOT, null, "纯AI消息");

        List<ChatMessage> bySession = service.historyBySession("s1", null, 10);
        assertEquals(2, bySession.size(), "会话维度含全部消息");

        List<ChatMessage> byTicket = service.historyByTicket("TK-1", null, 10);
        assertEquals(1, byTicket.size(), "工单维度仅含关联该工单的消息");
        assertEquals("工单内消息", byTicket.get(0).content());
    }

    @Test
    void history_shouldReturnAscendingByIdWithLimit() {
        for (int i = 1; i <= 5; i++) {
            service.append("s1", null, TicketActorType.USER, "u1", "msg" + i);
        }

        List<ChatMessage> latest2 = service.historyBySession("s1", null, 2);
        assertEquals(2, latest2.size());
        assertEquals("msg4", latest2.get(0).content(), "取最新两条并按 id 升序返回");
        assertEquals("msg5", latest2.get(1).content());
    }

    @Test
    void history_beforeIdCursor_shouldPageBackwards() {
        for (int i = 1; i <= 5; i++) {
            service.append("s1", null, TicketActorType.USER, "u1", "msg" + i);
        }
        // 第一页最新两条 id=4,5；游标 beforeId=4 取更早两条 id=2,3
        List<ChatMessage> older = service.historyBySession("s1", 4L, 2);
        assertEquals(2, older.size());
        assertEquals("msg2", older.get(0).content());
        assertEquals("msg3", older.get(1).content());
    }
}
