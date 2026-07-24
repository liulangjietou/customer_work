package com.richard.fyoung.customerwork.calllog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段耗时中间件「真实链路」集成测试——用真实 {@link ReActAgent}（离线 stub Model 驱动、真实工具执行、
 * 真实中间件链）走完整 call/stream，堵住此前"单测直调 hook 传非空 ctx"盖不住的缺陷。
 *
 * <p><b>缺陷背景</b>：admin 工作台与 8080 流式对话走框架<b>废弃</b>的
 * {@code ReActAgent.stream(msgs, options, ctx)}——该路径框架只把调用方 ctx 挂到 Reactor Context 的
 * {@code RUNTIME_CONTEXT_KEY} 上，{@code onAgent} 的 {@code ctx} 入参恒为 null（而 onModelCall/onActing
 * 用的是从该 key 解析出的真实 rc）。修复前果：onAgent 读不到 {@link AgentCallMeta}、采集器放进 null ctx
 * 后 onModelCall/onActing 取不到 → username/requestId/agentName 走兜底、sessionId 空、<b>分段全无</b>
 * （恰是用户实测的四个现象）。修复：onAgent 从 Reactor Context 兜底归一到同一 ctx。</p>
 *
 * <p>本测试正是沿废弃 stream 路径复现该缺陷：修复前四项断言全挂，修复后全绿。</p>
 * @author owlzhangfq@gmail.com
 */
class AgentCallTimingRealAgentIntegrationTest {

    private static final String FINAL_REPLY = "您本月考勤正常，共出勤 21 天。";
    private static final String MODEL_NAME = "stub-oa-model";
    private static final String TOOL_NAME = "queryAttendance";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);

    /** 离线工具：真实注册进 Toolkit，被 ReAct 循环真实调用，产出 TOOL 分段。 */
    public static class AttendanceTools {
        @Tool(description = "查询指定日期的 OA 考勤记录。用户问考勤/打卡时调用。")
        public Mono<String> queryAttendance(
                @ToolParam(name = "date", description = "查询日期，如 'today'") String date) {
            return Mono.just("考勤(" + date + ")：09:02 打卡，无异常");
        }
    }

    /**
     * 离线 stub Model：第一次调用返回一次 {@code queryAttendance} 工具调用（驱动 onActing → TOOL 分段），
     * 之后返回纯文本最终回答（ReAct 收敛）。全程无网络，确定性。
     */
    private static Model toolCallingStubModel() {
        AtomicInteger calls = new AtomicInteger(0);
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                int n = calls.getAndIncrement();
                if (n == 0) {
                    ToolUseBlock toolUse = new ToolUseBlock("call-1", TOOL_NAME, Map.of("date", "today"));
                    return Flux.just(ChatResponse.builder()
                        .id(UUID.randomUUID().toString())
                        .content(List.of(toolUse))
                        .usage(new ChatUsage(1, 1, 0.0))
                        .finishReason("tool_calls")
                        .build());
                }
                ContentBlock text = TextBlock.builder().text(FINAL_REPLY).build();
                return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(text))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
            }

            @Override
            public String getModelName() {
                return MODEL_NAME;
            }
        };
    }

    private CustomerWorkProperties enabledProps() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getCallLog().setEnabled(true);
        return props;
    }

    private ReActAgent buildAgent(AgentCallTimingMiddleware middleware) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AttendanceTools());
        return ReActAgent.builder()
            .name("AdminAgent-oa-assistant") // 内部名，与用户实测的兜底显示名一致
            .sysPrompt("你是 OA 考勤助手，遇到考勤查询请调用工具后回答。")
            .model(toolCallingStubModel())
            .toolkit(toolkit)
            .maxIters(3)
            .middleware(middleware)
            .build();
    }

    private AgentCallMeta meta() {
        return new AgentCallMeta("req-oa-8848", "admin", "oa-assistant",
            "OA考勤助手", AgentCallSessionType.CHAT, "查一下我这个月的考勤");
    }

    private Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("查一下我这个月的考勤").build()).build();
    }

    /**
     * 缺陷复现主用例：沿框架废弃的 {@code stream(msgs, options, ctx)} 路径（admin/8080 流式对话实际走法），
     * 断言 meta 四要素（username/requestId/agentName/sessionId）与分段（MODEL + TOOL）全部正确落地。
     * 修复前：onAgent 拿到 null ctx → 四项断言全挂。
     */
    @Test
    @SuppressWarnings("deprecation")
    void deprecatedStreamPath_shouldBindMetaAndCollectSegments() {
        AtomicReference<AgentCallRecord> captured = new AtomicReference<>();
        AgentCallTimingMiddleware middleware =
            new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), captured::set);
        ReActAgent agent = buildAgent(middleware);

        RuntimeContext ctx = RuntimeContext.builder().userId("oa-assistant").sessionId("sess-oa-1").build();
        ctx.put(AgentCallMeta.class, meta());

        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
            .incremental(true)
            .build();

        // 废弃三参 stream：正是缺陷路径。走完整订阅。
        Long events = agent.stream(List.of(userMsg()), options, ctx).count().block(BLOCK_TIMEOUT);
        assertTrue(events != null && events > 0, "流式路径应至少产出一个事件");

        AgentCallRecord record = captured.get();
        assertNotNull(record, "应组装并下沉一条记录");
        // ① meta 四要素来自 RuntimeContext 里绑定的 AgentCallMeta（修复前全走兜底）
        assertEquals("admin", record.username(), "username 取自 meta，非 unknown");
        assertEquals("req-oa-8848", record.requestId(), "requestId 取自 meta，非 unknown");
        assertEquals("OA考勤助手", record.agentName(), "agentName 取自 meta 中文名，非内部名 AdminAgent-oa-assistant");
        assertEquals("sess-oa-1", record.sessionId(), "sessionId 取自 ctx，非空");
        // ② 分段：至少一段 MODEL + 至少一段 TOOL（修复前 segments 全无）
        long modelSegs = record.segments().stream().filter(s -> s.kind() == AgentCallKind.MODEL).count();
        long toolSegs = record.segments().stream().filter(s -> s.kind() == AgentCallKind.TOOL).count();
        assertTrue(modelSegs >= 1, "应含至少一段 MODEL 分段，实际=" + record.segments());
        assertTrue(toolSegs >= 1, "应含至少一段 TOOL 分段，实际=" + record.segments());
        assertEquals(MODEL_NAME, record.segments().stream()
            .filter(s -> s.kind() == AgentCallKind.MODEL).findFirst().orElseThrow().name(),
            "MODEL 分段名取模型名");
        assertTrue(TOOL_NAME.equals(record.segments().stream()
            .filter(s -> s.kind() == AgentCallKind.TOOL).findFirst().orElseThrow().name()),
            "TOOL 分段名取工具名");
        assertTrue(record.success(), "整次调用成功");
        // ③ token：stub Model 每次调用返回 ChatUsage(1,1)，两次模型调用 → 请求级 input=2/output=2/total=4，
        //    每个 MODEL 段各 input=1/output=1；工具段无 token
        record.segments().stream().filter(s -> s.kind() == AgentCallKind.MODEL).forEach(s -> {
            assertEquals(1L, s.inputTokens(), "MODEL 段输入 token 取自 stub usage");
            assertEquals(1L, s.outputTokens(), "MODEL 段输出 token 取自 stub usage");
        });
        assertNull(record.segments().stream().filter(s -> s.kind() == AgentCallKind.TOOL)
            .findFirst().orElseThrow().inputTokens(), "工具段无 token");
        long modelSegCount = record.segments().stream().filter(s -> s.kind() == AgentCallKind.MODEL).count();
        assertEquals(Long.valueOf(modelSegCount), record.inputTokens(), "请求级输入 token = 各 MODEL 段之和");
        assertEquals(Long.valueOf(modelSegCount), record.outputTokens(), "请求级输出 token = 各 MODEL 段之和");
        assertEquals(Long.valueOf(modelSegCount * 2), record.totalTokens(), "请求级总 token");
    }

    /**
     * 回归护栏：非废弃的 {@code streamEvents(msgs, ctx)} 路径（框架推荐 API）同样正确绑定 meta 与分段，
     * 确认修复未破坏 ctx 入参非空的正常路径。
     */
    @Test
    void streamEventsPath_shouldBindMetaAndCollectSegments() {
        AtomicReference<AgentCallRecord> captured = new AtomicReference<>();
        AgentCallTimingMiddleware middleware =
            new AgentCallTimingMiddleware(enabledProps(), new ToolKindRegistry(), captured::set);
        ReActAgent agent = buildAgent(middleware);

        RuntimeContext ctx = RuntimeContext.builder().userId("oa-assistant").sessionId("sess-oa-2").build();
        ctx.put(AgentCallMeta.class, meta());

        Flux<AgentEvent> flux = agent.streamEvents(List.of(userMsg()), ctx);
        Long events = flux.count().block(BLOCK_TIMEOUT);
        assertTrue(events != null && events > 0, "流式路径应至少产出一个事件");

        AgentCallRecord record = captured.get();
        assertNotNull(record, "应组装并下沉一条记录");
        assertEquals("admin", record.username());
        assertEquals("req-oa-8848", record.requestId());
        assertEquals("OA考勤助手", record.agentName());
        assertEquals("sess-oa-2", record.sessionId());
        assertTrue(record.segments().stream().anyMatch(s -> s.kind() == AgentCallKind.MODEL), "含 MODEL 分段");
        assertTrue(record.segments().stream().anyMatch(s -> s.kind() == AgentCallKind.TOOL), "含 TOOL 分段");
    }
}
