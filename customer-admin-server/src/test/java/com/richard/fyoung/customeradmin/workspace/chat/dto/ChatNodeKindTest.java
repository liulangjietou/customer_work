package com.richard.fyoung.customeradmin.workspace.chat.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ChatNodeKind#sseEventName()} 事件名映射规则单测。
 * @author owlzhangfq@gmail.com
 */
class ChatNodeKindTest {

    @Test
    void answer_shouldMapToMessageEvent() {
        assertEquals("message", ChatNodeKind.ANSWER.sseEventName());
    }

    @Test
    void fileChange_shouldMapToDedicatedEvent_notNodePrefixed() {
        assertEquals("file_change", ChatNodeKind.FILE_CHANGE.sseEventName());
    }

    @Test
    void otherKinds_shouldMapToNodePrefixedEvent() {
        assertEquals("node:thinking", ChatNodeKind.THINKING.sseEventName());
        assertEquals("node:tool_result", ChatNodeKind.TOOL_RESULT.sseEventName());
        assertEquals("node:model_call", ChatNodeKind.MODEL_CALL.sseEventName());
    }
}
