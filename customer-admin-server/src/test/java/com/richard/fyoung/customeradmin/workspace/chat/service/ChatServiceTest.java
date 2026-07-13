package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.ToolSourceInfo;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    private ChatService chatService;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        agentInstanceCache = mock(AgentInstanceCache.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        historyCache = mock(ChatHistoryCache.class);
        chatService = new ChatService(agentInstanceCache, agentInstanceFactory, historyCache);

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
}
