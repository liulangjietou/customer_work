package com.richard.fyoung.customerwork.data.calllog;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentReplayCaptureTest {

    @Test
    void bind_shouldExposeCaptureThroughRuntimeContext() {
        RuntimeContext context = RuntimeContext.empty();
        AgentReplayCapture capture = new AgentReplayCapture();

        assertNull(AgentReplayCapture.from(null));
        assertNull(AgentReplayCapture.from(context));

        AgentReplayCapture.bind(context, capture);

        assertSame(capture, AgentReplayCapture.from(context));
    }

    @Test
    void snapshot_shouldCaptureParametersReferencesAndRedactedToolFacts() {
        AgentReplayCapture capture = new AgentReplayCapture();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("model-v1");
        Msg user = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("private question").build()).build();
        ToolSchema schema = ToolSchema.builder().name("order_query")
            .description("query order").parameters(Map.of("type", "object")).build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.2).topP(0.8)
            .maxTokens(256).seed(7L)
            .additionalBodyParams(Map.of("apiKey", "must-not-persist", "beam", 2)).build();
        capture.recordModelCall(new ModelCallInput(List.of(user), List.of(schema), options, model));

        capture.recordRag("service-agent", "private question",
            "knowledge_base=faq doc_id=doc-1 chunk_id=chunk-2 score=0.91\n正文", false);

        ToolUseBlock call = new ToolUseBlock("call-1", "order_query",
            Map.of("orderId", "A-100", "accessToken", "must-not-persist"));
        AgentReplayCapture.ToolBatchCapture batch = capture.beginTools(
            new ActingInput(List.of(call)), ignored -> AgentCallKind.MCP);
        batch.onEvent(new ToolResultTextDeltaEvent("reply-1", "call-1", "order_query", "tool output"));
        batch.onEvent(new ToolResultEndEvent("reply-1", "call-1", "order_query", ToolResultState.SUCCESS));
        batch.complete("SUCCESS", null);

        AgentReplaySnapshot snapshot = capture.snapshot();
        assertEquals(1, snapshot.modelCalls().size());
        assertEquals(0.2, snapshot.modelCalls().get(0).parameters().temperature());
        assertEquals(List.of("beam", "apiKey").stream().sorted().toList(),
            snapshot.modelCalls().get(0).parameters().additionalBodyParamNames());
        assertFalse(snapshot.modelCalls().get(0).inputSha256().contains("private question"));
        assertEquals("HIT", snapshot.ragRetrievals().get(0).status());
        assertEquals("doc-1", snapshot.ragRetrievals().get(0).references().get(0).documentId());
        assertEquals("MCP", snapshot.toolCalls().get(0).kind());
        assertTrue(snapshot.toolCalls().get(0).inputShape().contains("[REDACTED]"));
        assertFalse(snapshot.toolCalls().get(0).inputShape().contains("must-not-persist"));
        assertNotEquals(snapshot.toolCalls().get(0).inputSha256(),
            snapshot.toolCalls().get(0).resultSha256());
        assertEquals("SUCCESS", snapshot.toolCalls().get(0).resultState());
    }
}
