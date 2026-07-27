package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.ToolSourceInfo;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.calllog.AgentCallSessionType;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatService} 单测：重点覆盖 {@code toChunks} 的事件分流逻辑——
 * ① REASONING 事件里 {@link ThinkingBlock} 内容归"思考过程"、{@link TextBlock} 内容才是真正在
 *   增量流出的可见回答正文（早期实现混为一谈，导致回答文本被误判成思考过程、只能靠一次性到达的
 *   AGENT_RESULT 看最终答案）；
 * ② AGENT_RESULT 是一次性完整文本，已经通过 REASONING 流出过正文时要丢弃它，避免重复；
 * ③ TOOL_RESULT / 纯工具调用请求的 REASONING 事件——消息体不含 {@link TextBlock}，
 *   {@code Msg#getTextContent()} 直接拿到空字符串，不额外处理的话前端在"决定调用工具"和
 *   "等工具返回"这两段时间会看起来像卡住；
 * ④ 每轮对话固定以 {@link ChatNodeKind#THINKING_START}/{@link ChatNodeKind#THINKING_END} 收尾；
 * ⑤ 每轮 ReAct 迭代第一次产出内容前补一条 {@link ChatNodeKind#MODEL_CALL}，TOOL_RESULT 后重置；
 * ⑥ 工具调用按 {@link ToolSourceInfo} 分类成 SKILL/MCP/内置三种节点类型。
 * @author owlzhangfq@gmail.com
 */
class ChatServiceTest {

    private AgentInstanceCache agentInstanceCache;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private ChatHistoryCache historyCache;
    private AgentMemorySyncService memorySyncService;
    private ChatAttachmentService chatAttachmentService;
    private ChatService chatService;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        agentInstanceCache = mock(AgentInstanceCache.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        historyCache = mock(ChatHistoryCache.class);
        memorySyncService = mock(AgentMemorySyncService.class);
        chatAttachmentService = mock(ChatAttachmentService.class);
        // ExecutionModeRegistry / PlanConfirmationService 用真实实例（进程内内存、无外部依赖）：
        // 未指定模式 + 空通道时行为等价于改造前，不影响本测试聚焦的事件分流断言。
        chatService = new ChatService(agentInstanceCache, agentInstanceFactory, historyCache, memorySyncService,
            new ExecutionModeRegistry(), new PlanConfirmationService(), chatAttachmentService);

        agent = mock(ReActAgent.class);
        when(agentInstanceCache.getOrBuild("coder")).thenReturn(agent);
        when(agentInstanceFactory.contextFor(anyString(), anyString())).thenReturn(mock(RuntimeContext.class));
        when(agentInstanceFactory.toolSourceFor(anyString())).thenReturn(ToolSourceInfo.EMPTY);
    }

    private Msg toolUseOnlyMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT)
            .content(new ToolUseBlock("call-1", "OA考勤查询", Map.of("date", "today")))
            .build();
    }

    private Msg toolResultMsg(String output) {
        return Msg.builder().role(MsgRole.TOOL)
            .content(new ToolResultBlock("call-1", "OA考勤查询", List.of(TextBlock.builder().text(output).build()), null, null))
            .build();
    }

    private List<ChatStreamChunk> stream(String message) {
        return chatService.chatStream("coder", "s1", message).collectList().block();
    }

    private void assertKinds(List<ChatStreamChunk> chunks, ChatNodeKind... expected) {
        assertEquals(expected.length, chunks.size(), "chunk 数量不符，实际=" + chunks);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], chunks.get(i).kind(), "第 " + i + " 个 chunk kind 不符，实际=" + chunks);
        }
    }

    @Test
    void chatStream_shouldAlwaysBracketWithThinkingStartAndEnd() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        List<ChatStreamChunk> chunks = stream("你好");

        assertEquals(ChatNodeKind.THINKING_START, chunks.get(0).kind());
        assertEquals(ChatNodeKind.THINKING_END, chunks.get(chunks.size() - 1).kind());
    }

    @Test
    void chatStream_shouldSurfaceToolUseRequest_whenReasoningMessageHasNoText() {
        Msg toolUseMsg = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, toolUseMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.TOOL_BUILTIN, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains("OA考勤查询"));
    }

    @Test
    void chatStream_shouldSurfaceToolResultText() {
        Msg resultMsg = toolResultMsg("今日出勤：09:02 打卡");
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.TOOL_RESULT, resultMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(1).text().contains("今日出勤：09:02 打卡"));
    }

    @Test
    void chatStream_shouldSuppressPlaceholderFragmentNames_andDedupeRepeatedToolUseChunks() {
        // 模拟 incremental(true) 下框架按原始分片吐 ToolUseBlock 的真实场景：先来一个占位符片段
        // （框架内部聚合前的过渡态，名字是 "__fragment__"），再来两个已经解析出真实工具名的重复片段
        // （同一个工具调用在参数流式拼接过程中触发了多次事件）——前端应该只看到一条提示，不出现
        // "__fragment__" 这种内部占位符。
        Msg fragmentMsg = Msg.builder().role(MsgRole.ASSISTANT)
            .content(new ToolUseBlock(null, "__fragment__", Map.of())).build();
        Msg toolUseMsg1 = toolUseOnlyMsg();
        Msg toolUseMsg2 = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, fragmentMsg, false),
                new Event(EventType.REASONING, toolUseMsg1, false),
                new Event(EventType.REASONING, toolUseMsg2, false)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.TOOL_BUILTIN, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains("OA考勤查询"));
        assertTrue(!chunks.get(2).text().contains("__fragment__"));
    }

    @Test
    void chatStream_shouldReAnnounceSameTool_afterPriorToolResultArrived() {
        // 工具真正返回过一次之后，哪怕紧接着又调用同一个工具，也应该重新提示一次
        // （不是"这个工具名这辈子只提示一次"，而是"这一轮调用内不重复刷屏"），且要重新记一条
        // "调用大模型"——工具返回后模型必然会被重新调用一轮。
        Msg toolUseMsg1 = toolUseOnlyMsg();
        Msg resultMsg = toolResultMsg("今日出勤：09:02 打卡");
        Msg toolUseMsg2 = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, toolUseMsg1, false),
                new Event(EventType.TOOL_RESULT, resultMsg, false),
                new Event(EventType.REASONING, toolUseMsg2, false)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL, ChatNodeKind.TOOL_BUILTIN,
            ChatNodeKind.TOOL_RESULT, ChatNodeKind.MODEL_CALL, ChatNodeKind.TOOL_BUILTIN, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains("OA考勤查询"));
        assertTrue(chunks.get(3).text().contains("今日出勤：09:02 打卡"));
        assertTrue(chunks.get(5).text().contains("OA考勤查询"));
    }

    @Test
    void chatStream_finalAgentResult_shouldBeAnswerKind() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你今天已打卡").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你今天已打卡", chunks.get(1).text());
    }

    @Test
    void chatStream_reasoningTextBlock_shouldStreamAsAnswerKind_notThinking() {
        // EventType.REASONING 官方文档写明支持增量（"同一条消息 id 触发多次事件"），真正逐字流出来的
        // 可见回答文本走的是这里的 TextBlock，不是 AGENT_RESULT（那个是一次性的完整最终文本）。
        // 同一轮迭代内第二次产出内容不应该再补一条 MODEL_CALL。
        Msg delta1 = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好，").build();
        Msg delta2 = Msg.builder().role(MsgRole.ASSISTANT).textContent("我是智能体").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, delta1, false),
                new Event(EventType.REASONING, delta2, false)));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.ANSWER, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你好，", chunks.get(2).text());
        assertEquals("我是智能体", chunks.get(3).text());
    }

    @Test
    void chatStream_reasoningThinkingBlock_shouldExtractDelta_whenProviderSendsCumulativeText() {
        // 复现真实线上问题：某些 DeepSeek 风格推理模型的 reasoning_content 不是"这次新增的分片"，
        // 而是"从头到现在的累积全量文本"；早期实现原样转发每个值、前端再无脑 += 拼接，导致重叠
        // 部分被重复拼接一遍（界面上同一句话连续出现两次）。这里第二个值以第一个值为前缀，
        // 只应该把净增量部分（"Let me check..."）当作 chunk 发出去，不能把第一段再发一遍。
        Msg cumulative1 = Msg.builder().role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking("No attendance info found.").build()).build();
        Msg cumulative2 = Msg.builder().role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking("No attendance info found.Let me check the code.").build()).build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, cumulative1, false),
                new Event(EventType.REASONING, cumulative2, false)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.THINKING, ChatNodeKind.THINKING, ChatNodeKind.THINKING_END);
        assertEquals("No attendance info found.", chunks.get(2).text());
        assertEquals("Let me check the code.", chunks.get(3).text());
    }

    @Test
    void chatStream_reasoningThinkingBlock_shouldStayInThinkingBucket() {
        Msg thinkingMsg = Msg.builder().role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking("用户想知道天气，我需要先确认城市").build()).build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, thinkingMsg, false)));

        List<ChatStreamChunk> chunks = stream("今天天气怎么样");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.THINKING, ChatNodeKind.THINKING_END);
        assertEquals("用户想知道天气，我需要先确认城市", chunks.get(2).text());
    }

    @Test
    void chatStream_agentResult_shouldBeSuppressed_whenAnswerAlreadyStreamedViaReasoning() {
        // 已经通过 REASONING 增量流出过正文的情况下，结尾的 AGENT_RESULT（完整重复文本）应该被丢弃，
        // 否则前端会先看到逐字流出的答案，结束时又整段重复一遍。
        Msg delta = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, delta, false),
                new Event(EventType.AGENT_RESULT, finalMsg, true)));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你好", chunks.get(2).text());
    }

    @Test
    void chatStream_shouldClassifyToolCall_bySkillSource() {
        when(agentInstanceFactory.toolSourceFor("coder"))
            .thenReturn(new ToolSourceInfo(Set.of("OA考勤查询"), Set.of()));
        Msg toolUseMsg = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, toolUseMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.TOOL_SKILL, ChatNodeKind.THINKING_END);
    }

    @Test
    void chatStream_shouldClassifyToolCall_byMcpSource() {
        when(agentInstanceFactory.toolSourceFor("coder"))
            .thenReturn(new ToolSourceInfo(Set.of(), Set.of("OA考勤查询")));
        Msg toolUseMsg = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, toolUseMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.TOOL_MCP, ChatNodeKind.THINKING_END);
    }

    @Test
    void chatStream_onError_shouldStillEmitThinkingEnd_beforeFallbackAnswer() {
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.error(new RuntimeException("model down")));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.THINKING_END, ChatNodeKind.ANSWER);
    }

    @Test
    void chatStream_shouldFallBackGracefully_whenAgentStreamThrowsSynchronously() {
        // 复现真实场景：HarnessAgent.stream(...) 在沙箱资源获取失败时（如 docker 容器创建超时）是
        // 方法调用本身同步抛异常，不是返回一个 error Flux。如果 streamEvents(...) 没有包 Flux.defer，
        // 这个异常会在 chatStream(...) 方法体执行期间直接向外抛，onErrorResume 完全没机会接管，
        // 前端会收不到任何 SSE 事件（连接挂起）。用 thenThrow（而非 thenReturn(Flux.error(...))）
        // 模拟这种"同步抛"场景，断言依然能拿到完整的兜底话术，而不是让异常直接冒穿。
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenThrow(new RuntimeException("docker run timed out for image: maven:3.9-eclipse-temurin-17"));

        List<ChatStreamChunk> chunks = stream("写一个 Fibonacci.java");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.THINKING_END, ChatNodeKind.ANSWER);
    }

    // ===== 附件绑定（对话附件预览：随消息发送把附件绑定到本条用户消息 Msg.id）=====

    @Test
    void chatStream_withAttachmentIds_shouldBindToMessage_inRequestThread() {
        // attachmentIds 非空 → 请求线程同步段（订阅前）调 bindToMessage：sessionId 用归一值，messageId 为本条用户消息 id。
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("已收到附件").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));
        List<String> ids = List.of("att-1", "att-2");

        chatService.chatStreamWithAttachments("coder", "s1", "看下这个附件", null, null, ids).collectList().block();

        verify(chatAttachmentService).bindToMessage(eq("coder"), eq("s1"), anyString(), eq(ids));
    }

    @Test
    void chatStream_emptySession_shouldBindWithDefaultSession() {
        // sessionId 空 → 归一成 "default"，与历史读取/Plan 通道口径一致。
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("ok").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));
        List<String> ids = List.of("att-1");

        chatService.chatStreamWithAttachments("coder", "", "看下这个附件", null, null, ids).collectList().block();

        verify(chatAttachmentService).bindToMessage(eq("coder"), eq("default"), anyString(), eq(ids));
    }

    @Test
    void chatStream_withoutAttachmentIds_shouldNotBind() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        stream("你好");

        verify(chatAttachmentService, never()).bindToMessage(anyString(), anyString(), anyString(), any());
    }

    // ===== 子 Agent 事件流透传（harness spawn 出的子 Agent 经 SubagentEventBus 直推父 sink）=====

    /** 构造一个直接子级（depth=1）的 EventSource：path=main/doc-writer，展示名 DocWriter。 */
    private EventSource subSource() {
        return EventSource.builder()
            .path("main/doc-writer").agentId("doc-writer").agentName("DocWriter").depth(1).build();
    }

    private Msg thinkingMsg(String thinking) {
        return Msg.builder().role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking(thinking).build()).build();
    }

    @Test
    void chatStream_mainChunks_shouldCarryNullSourceAndSubagentName() {
        // 回归：source=null 的父 Agent 事件，产出 chunk 的 source/subagentName 必须为 null（前端据此判定"非子 Agent"）。
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你今天已打卡").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        for (ChatStreamChunk chunk : chunks) {
            assertNull(chunk.source(), "父 Agent 片段 source 应为 null，实际=" + chunk);
            assertNull(chunk.subagentName(), "父 Agent 片段 subagentName 应为 null，实际=" + chunk);
        }
    }

    @Test
    void chatStream_subagentReasoningAndToolResult_shouldStampSource_andPrependSubagentStart() {
        // 带 source 的 REASONING（思考）+ TOOL_RESULT：首事件前补一条 SUBAGENT_START，
        // 后续 chunk 均带 source（调用链 path）与 subagentName（展示名）。
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, thinkingMsg("分析文档结构"), false, subSource()),
                new Event(EventType.TOOL_RESULT, toolResultMsg("已生成大纲"), false, subSource())));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.THINKING, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        // SUBAGENT_START 文本为展示名
        assertEquals("DocWriter", chunks.get(1).text());
        // 子 Agent 片段带 source/subagentName
        for (int i = 1; i <= 3; i++) {
            assertEquals("main/doc-writer", chunks.get(i).source(), "第 " + i + " 个 chunk source 不符，实际=" + chunks);
            assertEquals("DocWriter", chunks.get(i).subagentName());
        }
        assertTrue(chunks.get(2).text().contains("分析文档结构"));
        assertTrue(chunks.get(3).text().contains("已生成大纲"));
        // 父 Agent 框架节点（THINKING_START/END）source 仍为 null
        assertNull(chunks.get(0).source());
        assertNull(chunks.get(4).source());
    }

    @Test
    void chatStream_subagentAgentResult_shouldBeSubagentResultKind_notAnswer() {
        // 带 source 的 AGENT_RESULT → SUBAGENT_RESULT（子 Agent 最终文本），绝不走父 Agent 的 ANSWER 链路。
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("文档已生成完毕").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true, subSource())));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.SUBAGENT_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("文档已生成完毕", chunks.get(2).text());
        assertEquals("main/doc-writer", chunks.get(2).source());
        assertEquals("DocWriter", chunks.get(2).subagentName());
    }

    @Test
    void chatStream_interleavedParentAndChild_shouldDedupeDeltaIndependently() {
        // 父子交错推流：两边都是累积型全量文本（后值以前值为前缀）。若父子共用一份去重状态，
        // 父的第二次全量会被子的全量"顶掉"、前缀判断失效，导致重复内容整段重发。这里断言父子各自
        // 只发出净增量（父 A→B、子 X→Y），证明 per-source 状态隔离生效。
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, thinkingMsg("A"), false),
                new Event(EventType.REASONING, thinkingMsg("X"), false, subSource()),
                new Event(EventType.REASONING, thinkingMsg("AB"), false),
                new Event(EventType.REASONING, thinkingMsg("XY"), false, subSource())));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START,
            ChatNodeKind.MODEL_CALL, ChatNodeKind.THINKING,  // 父：MODEL_CALL + THINKING("A")
            ChatNodeKind.SUBAGENT_START, ChatNodeKind.THINKING, // 子：SUBAGENT_START + THINKING("X")
            ChatNodeKind.THINKING,                            // 父：THINKING("B")，无新 MODEL_CALL
            ChatNodeKind.THINKING,                            // 子：THINKING("Y")
            ChatNodeKind.THINKING_END);
        assertEquals("A", chunks.get(2).text());
        assertNull(chunks.get(2).source());
        assertEquals("X", chunks.get(4).text());
        assertEquals("main/doc-writer", chunks.get(4).source());
        assertEquals("B", chunks.get(5).text());
        assertNull(chunks.get(5).source());
        assertEquals("Y", chunks.get(6).text());
        assertEquals("main/doc-writer", chunks.get(6).source());
    }

    @Test
    void chatStream_subagentUnknownEventType_shouldBeIgnored_afterSubagentStart() {
        // 子 Agent 全量事件绕过父流过滤，可能出现父流本不会有的类型（如 SUMMARY）——应被忽略不抛异常，
        // 仅保留首次出现补的 SUBAGENT_START；随后真正的 REASONING 正常产出。
        Msg summaryMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("到达最大迭代").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.SUMMARY, summaryMsg, true, subSource()),
                new Event(EventType.REASONING, thinkingMsg("继续分析"), false, subSource())));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.THINKING, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains("继续分析"));
    }

    // ===== 模型用量聚合（审计需求 §5.3：token 数）=====

    @Test
    void chatStream_shouldAggregateUsageAcrossMessages_andDedupeByMessageId() {
        // 同一 messageId 的增量事件重复携带 usage（后到为累计值，应覆盖而非累加），
        // 不同 messageId 各算一次——总量 = msg-1 最终值(10+20) + msg-2(5+7)
        Msg first = Msg.builder().id("msg-1").role(MsgRole.ASSISTANT).textContent("part")
            .usage(new ChatUsage(3, 4, 0.1)).build();
        Msg firstFinal = Msg.builder().id("msg-1").role(MsgRole.ASSISTANT).textContent("part2")
            .usage(new ChatUsage(10, 20, 0.2)).build();
        Msg second = Msg.builder().id("msg-2").role(MsgRole.ASSISTANT).textContent("done")
            .usage(new ChatUsage(5, 7, 0.1)).build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, first, false),
                new Event(EventType.REASONING, firstFinal, false),
                new Event(EventType.AGENT_RESULT, second, true)));

        AtomicReference<ChatUsage> observed = new AtomicReference<>();
        chatService.chatStream("coder", "s1", "你好", observed::set).collectList().block();

        assertEquals(15, observed.get().getInputTokens());
        assertEquals(27, observed.get().getOutputTokens());
        assertEquals(42, observed.get().getTotalTokens());
    }

    @Test
    void chatStream_shouldObserveNullUsage_whenNoMessageCarriesUsage() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        AtomicReference<ChatUsage> observed = new AtomicReference<>(new ChatUsage(1, 1, 0));
        chatService.chatStream("coder", "s1", "你好", observed::set).collectList().block();

        assertNull(observed.get(), "无任何用量信息时应回调 null，区分“没有用量”与“用了 0 token”");
    }

    // ---- 知识库自动检索注入 ----

    /** 捕获真正送进 Agent 的用户消息文本。 */
    @SuppressWarnings("unchecked")
    private String capturedUserText() {
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).stream(captor.capture(), any(StreamOptions.class), any(RuntimeContext.class));
        return captor.getValue().get(0).getTextContent();
    }

    private void stubEmptyStream() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("好的").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));
    }

    /**
     * 送进 Agent（→ 进 AgentState → 进用户可见历史）的用户消息必须是<b>原文</b>：知识库召回内容
     * 已改由 {@code KnowledgeRetrievalMiddleware} 在推理阶段挂成瞬态消息，不再拼进这条消息文本。
     * 这条断言就是"用户历史里绝不出现 &lt;retrieved_knowledge&gt;"的守门测试。
     */
    @Test
    void chatStream_shouldSendRawUserTextToAgent_withoutAnyInjection() {
        stubEmptyStream();

        chatService.chatStream("coder", "s1", "公积金怎么提取").collectList().block();

        String sent = capturedUserText();
        assertEquals("公积金怎么提取", sent);
        assertFalse(sent.contains("<retrieved_knowledge>"),
            "召回内容绝不能拼进用户消息——那会随 AgentState 持久化并回显给用户，且每轮重发累积 token");
    }

    /** VibeCoding 链路同理：送进 Agent 的仍是"路径指引 + 提问"原文，不得被检索结果污染。 */
    @Test
    void chatStream_shouldNotAlterVibeCodingDirectiveText() {
        stubEmptyStream();
        String directivePrefixed = "[VibeCoding指引-local] 本次对话的会话目录为: sessions/s1/\n\n写一个冒泡排序";
        AgentCallMeta callMeta = new AgentCallMeta("req-1", "admin", "coder", "编码助手",
            AgentCallSessionType.VIBE_CODING, "写一个冒泡排序");

        chatService.chatStream("coder", "s1", directivePrefixed, null, callMeta).collectList().block();

        assertEquals(directivePrefixed, capturedUserText());
    }
}
