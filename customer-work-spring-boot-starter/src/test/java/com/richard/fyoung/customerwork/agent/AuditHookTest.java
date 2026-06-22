package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 合规审计 Hook 单测：开启时把工具调用/最终决策/异常写入 AuditSink，入参按配置脱敏。
 * @author owlzhangfq@gmail.com
 */
class AuditHookTest {

    /** 捕获型审计落地，便于断言。 */
    static class CapturingSink implements AuditSink {
        final List<Map<String, Object>> records = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        @Override public void record(String type, Map<String, Object> fields) {
            types.add(type);
            records.add(new LinkedHashMap<>(fields));
        }
    }

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    private AuditHook hook(boolean enabled, boolean maskArgs, CapturingSink sink) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getAudit().setEnabled(enabled);
        props.getHooks().getAudit().setMaskArgs(maskArgs);
        return new AuditHook(props, sink, new SensitiveDataMasker(props));
    }

    @Test
    void shouldAuditToolCall_withMaskedArgs() {
        CapturingSink sink = new CapturingSink();
        AuditHook hook = hook(true, true, sink);
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund", Map.of("phone", "13800138000"));
        ToolResultBlock result = new ToolResultBlock("c1", "submitRefund",
            TextBlock.builder().text("待人工确认").build());

        hook.onEvent(new PostActingEvent(agent(), new Toolkit(), use, result)).block();

        assertEquals(List.of("tool-call"), sink.types);
        Map<String, Object> rec = sink.records.get(0);
        assertEquals("submitRefund", rec.get("tool"));
        assertTrue(rec.get("args").toString().contains("***"), "入参应脱敏: " + rec.get("args"));
    }

    @Test
    void shouldAuditFinalAnswerAndError() {
        CapturingSink sink = new CapturingSink();
        AuditHook hook = hook(true, true, sink);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).name("bot").textContent("已受理").build();

        hook.onEvent(new PostCallEvent(agent(), reply)).block();
        hook.onEvent(new ErrorEvent(agent(), new RuntimeException("boom"))).block();

        assertEquals(List.of("final-answer", "error"), sink.types);
        assertEquals("已受理", sink.records.get(0).get("answer"));
        assertEquals("boom", sink.records.get(1).get("error"));
    }

    @Test
    void shouldNotAudit_whenDisabled() {
        CapturingSink sink = new CapturingSink();
        AuditHook hook = hook(false, true, sink);
        hook.onEvent(new ErrorEvent(agent(), new RuntimeException("x"))).block();
        assertTrue(sink.types.isEmpty());
    }

    @Test
    void shouldKeepArgsRaw_whenMaskArgsOff() {
        CapturingSink sink = new CapturingSink();
        AuditHook hook = hook(true, false, sink);
        ToolUseBlock use = new ToolUseBlock("c1", "queryOrder", Map.of("orderId", "20260613001"));
        ToolResultBlock result = new ToolResultBlock("c1", "queryOrder",
            TextBlock.builder().text("ok").build());
        hook.onEvent(new PostActingEvent(agent(), new Toolkit(), use, result)).block();
        assertTrue(sink.records.get(0).get("args").toString().contains("20260613001"));
    }

    @Test
    void priority_shouldRunBeforeMasking() {
        assertEquals(850, hook(true, true, new CapturingSink()).priority());
    }
}
