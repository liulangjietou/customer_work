package com.richard.fyoung.customeradmin.workspace.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatStreamChunk#sseData()} 编码契约测试（与前端 parseChatStreamPayload 的
 * "先按 JSON 解析、无 text 字段回退纯文本"解析逻辑配对）。
 * @author owlzhangfq@gmail.com
 */
class ChatStreamChunkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sseData_shouldReturnPlainText_forMainAgentChunk() {
        // 父 Agent 片段保持纯文本，即使正文本身长得像 JSON 也原样透传（向后兼容）
        ChatStreamChunk chunk = new ChatStreamChunk(ChatNodeKind.ANSWER, "{\"looksLike\":\"json\"}");

        assertEquals("{\"looksLike\":\"json\"}", chunk.sseData());
    }

    @Test
    void sseData_shouldReturnJsonEnvelope_forSubagentChunk() throws Exception {
        ChatStreamChunk chunk = new ChatStreamChunk(ChatNodeKind.THINKING, "思考增量", "main/doc-writer", "DocWriter");

        JsonNode node = MAPPER.readTree(chunk.sseData());

        assertEquals("思考增量", node.get("text").asText());
        assertEquals("main/doc-writer", node.get("source").asText());
        assertEquals("DocWriter", node.get("subagentName").asText());
        assertTrue(node.has("kind"));
    }

    @Test
    void sseData_shouldTolerateNullSubagentName() throws Exception {
        // subagentName 允许为空（EventSource.getAgentName 与 getAgentId 都拿不到时），编码不能抛异常
        ChatStreamChunk chunk = new ChatStreamChunk(ChatNodeKind.SUBAGENT_START, "sub", "main/sub", null);

        JsonNode node = MAPPER.readTree(chunk.sseData());

        assertEquals("main/sub", node.get("source").asText());
        assertTrue(node.get("subagentName").isNull());
    }
}
