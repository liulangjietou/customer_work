package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS 帧构造单测：system 帧新旧两种格式的序列化契约（带会话标识 / 不带标识的向后兼容格式）。
 * @author owlzhangfq@gmail.com
 */
class WsFrameTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void system_withSessionContext_shouldCarrySessionIdAndTicketId() throws Exception {
        WsFrame frame = WsFrame.system("正在为您转接人工客服", "u1001:conv-1", "TK-1");
        JsonNode data = mapper.readTree(mapper.writeValueAsString(frame)).get("data");
        assertEquals("正在为您转接人工客服", data.get("content").asText());
        assertEquals("u1001:conv-1", data.get("sessionId").asText());
        assertEquals("TK-1", data.get("ticketId").asText());
        assertTrue(data.has("ts"), "ts 必须存在");
    }

    @Test
    void system_legacyOneArg_shouldKeepOldWireFormatWithoutIds() throws Exception {
        WsFrame frame = WsFrame.system("排队中");
        JsonNode root = mapper.readTree(mapper.writeValueAsString(frame));
        assertEquals(WsFrame.TYPE_SYSTEM, root.get("type").asText());
        JsonNode data = root.get("data");
        assertEquals("排队中", data.get("content").asText());
        // 旧格式契约：无会话上下文时不输出 sessionId/ticketId 键，与历史线上帧完全一致
        assertFalse(data.has("sessionId"));
        assertFalse(data.has("ticketId"));
        assertTrue(data.has("ts"));
    }

    @Test
    void system_nullIds_shouldOmitKeysInsteadOfNullValues() throws Exception {
        WsFrame frame = WsFrame.system("提示", null, null);
        JsonNode data = mapper.readTree(mapper.writeValueAsString(frame)).get("data");
        assertFalse(data.has("sessionId"), "空标识不应以 null 值输出");
        assertFalse(data.has("ticketId"), "空标识不应以 null 值输出");
    }

    @Test
    void chatDone_shouldCarryUnifiedTerminalEnvelopeAndPersistedMessageScope() throws Exception {
        ChatTerminalEnvelope terminal = new ChatTerminalEnvelope("MSG-9", "MODEL_STOP",
            new ChatUsageSnapshot(8, 2, 1, 10, 0.3), "trace-9");

        WsFrame frame = WsFrame.chatDone(terminal, "u1:conv-1", "TK-1", "您好", 123L);
        JsonNode data = mapper.readTree(mapper.writeValueAsString(frame)).get("data");

        assertEquals("MSG-9", data.get("messageId").asText());
        assertEquals("MODEL_STOP", data.get("finishReason").asText());
        assertEquals(10, data.get("usage").get("totalTokens").asInt());
        assertEquals("trace-9", data.get("traceId").asText());
        assertEquals("u1:conv-1", data.get("sessionId").asText());
        assertEquals("TK-1", data.get("ticketId").asText());
        assertEquals("您好", data.get("content").asText());
    }
}
