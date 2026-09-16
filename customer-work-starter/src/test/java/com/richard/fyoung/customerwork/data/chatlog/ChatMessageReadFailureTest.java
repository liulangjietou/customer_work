package com.richard.fyoung.customerwork.data.chatlog;

import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原存储测试仅验证正常往返，未覆盖连接故障被伪装成空记录。 */
class ChatMessageReadFailureTest {
    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final MybatisChatMessageStore store = new MybatisChatMessageStore(mapper);

    @Test
    void receiptFailure_shouldRemainADataAccessFailure() {
        when(mapper.findByMessageId("request-1")).thenThrow(new IllegalStateException("connection unavailable"));
        assertThrows(DataAccessException.class, () -> store.findByMessageId("request-1"));
    }

    @Test
    void sessionHistoryFailure_shouldNotReturnAnEmptyPage() {
        when(mapper.findBySessionPage("session-1", null, 50)).thenThrow(new IllegalStateException("connection unavailable"));
        assertThrows(DataAccessException.class, () -> store.findBySession("session-1", null, 50));
    }

    @Test
    void ticketHistoryFailure_shouldNotReturnAnEmptyPage() {
        when(mapper.findByTicketPage("ticket-1", null, 50)).thenThrow(new IllegalStateException("connection unavailable"));
        assertThrows(DataAccessException.class, () -> store.findByTicket("ticket-1", null, 50));
    }
}
