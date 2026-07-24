package com.richard.fyoung.customerwork.calllog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分段耗时中间件单测：MODEL/TOOL/MCP/SKILL 分段采集与归类、答案采集、失败判定、异常不打断主链路、开关直通。
 * @author owlzhangfq@gmail.com
 */
class AgentCallTimingMiddlewareTest {

    private final Agent agent = mock(Agent.class);
    private final Model model = mock(Model.class);

    private CustomerWorkProperties enabledProps() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getCallLog().setEnabled(true);
        return props;
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("tenantA").sessionId("sess-1").build();
    }

    private Msg msg(MsgRole role, String text) {
        return Msg.builder().role(role).name(role.name().toLowerCase())
            .content(TextBlock.builder().text(text).build()).build();
    }

    /** onAgent 内嵌 model + tool + mcp 三段，验证完整采集链路。 */
    @Test
    void onAgent_shouldCollectSegmentsAnswerAndEmitRecord() {
        when(agent.getName()).thenReturn("客服Agent");
        when(model.getModelName()).thenReturn("qwen-max");

        ToolKindRegistry registry = new ToolKindRegistry();
        registry.registerMcpTools(List.of("mcp_weather"));
        AtomicReference<AgentCallRecord> captured = new AtomicReference<>();
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), registry, captured::set);

        RuntimeContext ctx = ctx();
        Function<AgentInput, Flux<AgentEvent>> inner = ai -> {
            Flux<AgentEvent> modelFlux = mw.onModelCall(agent, ctx,
                new ModelCallInput(List.of(), List.of(), null, model),
                mi -> Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "thinking"))));
            Flux<AgentEvent> toolFlux = mw.onActing(agent, ctx,
                new ActingInput(List.of(new ToolUseBlock("t1", "queryOrder", Map.of()))),
                act -> Flux.just(new ToolResultEndEvent("r", "t1", "queryOrder", ToolResultState.SUCCESS)));
            Flux<AgentEvent> mcpFlux = mw.onActing(agent, ctx,
                new ActingInput(List.of(new ToolUseBlock("t2", "mcp_weather", Map.of()))),
                act -> Flux.just(new ToolResultEndEvent("r", "t2", "mcp_weather", ToolResultState.SUCCESS)));
            return Flux.concat(modelFlux, toolFlux, mcpFlux,
                Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "最终回答"))));
        };

        mw.onAgent(agent, ctx, new AgentInput(List.of(msg(MsgRole.USER, "你好"))), inner).blockLast();

        AgentCallRecord record = captured.get();
        assertNotNull(record, "应组装并下沉记录");
        assertEquals(3, record.segmentCount(), "MODEL + TOOL + MCP 三段");
        assertEquals("最终回答", record.answer(), "从 AGENT_RESULT 采集最终回答");
        assertEquals("你好", record.question(), "meta 缺失时问题回退首条用户消息");
        assertEquals("tenantA", record.username(), "username 回退 ctx.userId");
        assertEquals("客服Agent", record.agentName());
        assertTrue(record.success());
        // 三类各一段
        long model = record.segments().stream().filter(s -> s.kind() == AgentCallKind.MODEL).count();
        long tool = record.segments().stream().filter(s -> s.kind() == AgentCallKind.TOOL).count();
        long mcp = record.segments().stream().filter(s -> s.kind() == AgentCallKind.MCP).count();
        assertEquals(1, model);
        assertEquals(1, tool);
        assertEquals(1, mcp);
        assertEquals("qwen-max", record.segments().stream()
            .filter(s -> s.kind() == AgentCallKind.MODEL).findFirst().orElseThrow().name());
    }

    /** onModelCall 采集 ModelCallEndEvent 携带的 ChatUsage：MODEL 段挂 token，请求级汇总求和。 */
    @Test
    void onModelCall_shouldCaptureTokensFromModelCallEndEvent() {
        when(agent.getName()).thenReturn("客服Agent");
        when(model.getModelName()).thenReturn("qwen-max");

        AtomicReference<AgentCallRecord> captured = new AtomicReference<>();
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), captured::set);

        RuntimeContext ctx = ctx();
        Function<AgentInput, Flux<AgentEvent>> inner = ai -> {
            // 模型调用流末尾回放带 usage 的 ModelCallEndEvent（与框架真实行为一致）
            Flux<AgentEvent> modelFlux = mw.onModelCall(agent, ctx,
                new ModelCallInput(List.of(), List.of(), null, model),
                mi -> Flux.just(
                    new AgentResultEvent(msg(MsgRole.ASSISTANT, "thinking")),
                    new ModelCallEndEvent("reply-1", new ChatUsage(128, 32, 0.0))));
            return Flux.concat(modelFlux,
                Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "最终回答"))));
        };

        mw.onAgent(agent, ctx, new AgentInput(List.of(msg(MsgRole.USER, "你好"))), inner).blockLast();

        AgentCallRecord record = captured.get();
        assertNotNull(record);
        AgentCallSegment modelSeg = record.segments().stream()
            .filter(s -> s.kind() == AgentCallKind.MODEL).findFirst().orElseThrow();
        assertEquals(128L, modelSeg.inputTokens(), "MODEL 段输入 token 取自 ChatUsage");
        assertEquals(32L, modelSeg.outputTokens(), "MODEL 段输出 token 取自 ChatUsage");
        assertEquals(128L, record.inputTokens(), "请求级输入 token 汇总");
        assertEquals(32L, record.outputTokens(), "请求级输出 token 汇总");
        assertEquals(160L, record.totalTokens(), "请求级总 token = 输入 + 输出");
    }

    /** onActing 工具结果为 ERROR 时段判失败并记错误信息。 */
    @Test
    void onActing_toolResultError_shouldMarkSegmentFailed() {
        ToolKindRegistry registry = new ToolKindRegistry();
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), registry, r -> { });

        RuntimeContext ctx = ctx();
        AgentCallCollector collector = new AgentCallCollector();
        ctx.put(AgentCallCollector.class, collector);

        mw.onActing(agent, ctx, new ActingInput(List.of(new ToolUseBlock("t1", "queryOrder", Map.of()))),
            act -> Flux.just(new ToolResultEndEvent("r", "t1", "queryOrder", ToolResultState.ERROR))).blockLast();

        AgentCallRecord record = collector.toRecord("req", "u", "u", "a", "a", "s",
            AgentCallSessionType.CHAT, "q", System.currentTimeMillis(), true, null);
        assertEquals(1, record.segmentCount());
        assertFalse(record.segments().get(0).success(), "ERROR 状态段判失败");
        assertNotNull(record.segments().get(0).errorMsg());
    }

    /** onActing 上游抛异常走 onError 分支，段判失败且异常不外泄（本 hook 只读透传）。 */
    @Test
    void onActing_upstreamError_shouldRecordFailureAndNotSwallowMainError() {
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), r -> { });
        RuntimeContext ctx = ctx();
        AgentCallCollector collector = new AgentCallCollector();
        ctx.put(AgentCallCollector.class, collector);

        RuntimeException boom = new RuntimeException("tool boom");
        AtomicReference<Throwable> seen = new AtomicReference<>();
        mw.onActing(agent, ctx, new ActingInput(List.of(new ToolUseBlock("t1", "queryOrder", Map.of()))),
                act -> Flux.<AgentEvent>error(boom))
            .doOnError(seen::set)
            .onErrorResume(e -> Flux.empty())
            .blockLast();

        assertEquals(boom, seen.get(), "主链路错误信号原样透传");
        List<AgentCallSegment> segs = collector.toRecord("req", "u", "u", "a", "a", "s",
            AgentCallSessionType.CHAT, "q", System.currentTimeMillis(), false, "x").segments();
        assertEquals(1, segs.size());
        assertFalse(segs.get(0).success());
    }

    /** Sink 抛异常不得打断主对话流（防御式兜底收敛在中间件）。 */
    @Test
    void onAgent_sinkThrows_shouldNotBreakMainFlux() {
        AgentCallRecordSink throwing = r -> {
            throw new RuntimeException("sink boom");
        };
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), throwing);

        AgentEvent last = mw.onAgent(agent, ctx(), new AgentInput(List.of(msg(MsgRole.USER, "hi"))),
            ai -> Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "ok")))).blockLast();

        assertNotNull(last, "Sink 异常被中间件吞掉，主链路照常产出事件");
    }

    /** 子 agent 复用同一 RuntimeContext 再次进入 onAgent：不覆盖父采集器、不另发记录，分段归入父请求。 */
    @Test
    void onAgent_nestedSameContext_shouldAttributeSegmentsToParentAndEmitOnce() {
        when(agent.getName()).thenReturn("父Agent");
        when(model.getModelName()).thenReturn("qwen-max");

        List<AgentCallRecord> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), emitted::add);

        RuntimeContext ctx = ctx();
        // 内层：模拟子 agent 在同一 ctx 上再次进入 onAgent，并在其中发生一次模型调用
        Function<AgentInput, Flux<AgentEvent>> subAgentBody = ai -> mw.onModelCall(agent, ctx,
            new ModelCallInput(List.of(), List.of(), null, model),
            mi -> Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "sub thinking"))));
        Function<AgentInput, Flux<AgentEvent>> outerBody = ai -> Flux.concat(
            mw.onAgent(agent, ctx, new AgentInput(List.of(msg(MsgRole.USER, "sub task"))), subAgentBody),
            Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "父回答"))));

        mw.onAgent(agent, ctx, new AgentInput(List.of(msg(MsgRole.USER, "父问题"))), outerBody).blockLast();

        assertEquals(1, emitted.size(), "嵌套调用只由父级结算一次，不重复下沉");
        AgentCallRecord record = emitted.get(0);
        assertEquals("父问题", record.question(), "记录归属父请求");
        assertEquals("父回答", record.answer(), "答案取父级最终 AGENT_RESULT");
        assertEquals(1, record.segments().stream().filter(s -> s.kind() == AgentCallKind.MODEL).count(),
            "子 agent 的模型分段归入父请求采集器");
    }

    /** 开关关闭时全部 hook 直通，不采集、不下沉。 */
    @Test
    void disabled_shouldPassThroughAndNotEmit() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getCallLog().setEnabled(false);
        AtomicReference<AgentCallRecord> captured = new AtomicReference<>();
        AgentCallTimingMiddleware mw = new AgentCallTimingMiddleware(props, new ToolKindRegistry(), captured::set);

        Long count = mw.onAgent(agent, ctx(), new AgentInput(List.of(msg(MsgRole.USER, "hi"))),
            ai -> Flux.just(new AgentResultEvent(msg(MsgRole.ASSISTANT, "ok")))).count().block();

        assertTrue(count != null && count == 1, "事件透传不丢失");
        assertNull(captured.get(), "关闭时不下沉记录");
    }
}
