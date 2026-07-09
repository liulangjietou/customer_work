package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
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
 *   "等工具返回"这两段时间会看起来像卡住。
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

    @Test
    void chatStream_shouldSurfaceToolUseRequest_whenReasoningMessageHasNoText() {
        Msg toolUseMsg = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, toolUseMsg, true)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "查一下我的考勤").collectList().block();

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).reasoning());
        assertTrue(chunks.get(0).text().contains("OA考勤查询"));
    }

    @Test
    void chatStream_shouldSurfaceToolResultText() {
        Msg resultMsg = toolResultMsg("今日出勤：09:02 打卡");
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.TOOL_RESULT, resultMsg, true)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "查一下我的考勤").collectList().block();

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).reasoning());
        assertTrue(chunks.get(0).text().contains("今日出勤：09:02 打卡"));
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

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "查一下我的考勤").collectList().block();

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("OA考勤查询"));
        assertTrue(!chunks.get(0).text().contains("__fragment__"));
    }

    @Test
    void chatStream_shouldReAnnounceSameTool_afterPriorToolResultArrived() {
        // 工具真正返回过一次之后，哪怕紧接着又调用同一个工具，也应该重新提示一次
        // （不是"这个工具名这辈子只提示一次"，而是"这一轮调用内不重复刷屏"）。
        Msg toolUseMsg1 = toolUseOnlyMsg();
        Msg resultMsg = toolResultMsg("今日出勤：09:02 打卡");
        Msg toolUseMsg2 = toolUseOnlyMsg();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, toolUseMsg1, false),
                new Event(EventType.TOOL_RESULT, resultMsg, false),
                new Event(EventType.REASONING, toolUseMsg2, false)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "查一下我的考勤").collectList().block();

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).text().contains("正在调用工具「OA考勤查询」"));
        assertTrue(chunks.get(1).text().contains("今日出勤：09:02 打卡"));
        assertTrue(chunks.get(2).text().contains("正在调用工具「OA考勤查询」"));
    }

    @Test
    void chatStream_finalAgentResult_shouldNotBeMarkedAsReasoning() {
        Msg finalMsg = Msg.builder().role(MsgRole.ASSISTANT).textContent("你今天已打卡").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, finalMsg, true)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "查一下我的考勤").collectList().block();

        assertEquals(1, chunks.size());
        assertEquals(false, chunks.get(0).reasoning());
        assertEquals("你今天已打卡", chunks.get(0).text());
    }

    @Test
    void chatStream_reasoningTextBlock_shouldStreamAsVisibleAnswer_notAsThinking() {
        // EventType.REASONING 官方文档写明支持增量（"同一条消息 id 触发多次事件"），真正逐字流出来的
        // 可见回答文本走的是这里的 TextBlock，不是 AGENT_RESULT（那个是一次性的完整最终文本）。
        Msg delta1 = Msg.builder().role(MsgRole.ASSISTANT).textContent("你好，").build();
        Msg delta2 = Msg.builder().role(MsgRole.ASSISTANT).textContent("我是智能体").build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(
                new Event(EventType.REASONING, delta1, false),
                new Event(EventType.REASONING, delta2, false)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "你好").collectList().block();

        assertEquals(2, chunks.size());
        assertEquals(false, chunks.get(0).reasoning());
        assertEquals("你好，", chunks.get(0).text());
        assertEquals(false, chunks.get(1).reasoning());
        assertEquals("我是智能体", chunks.get(1).text());
    }

    @Test
    void chatStream_reasoningThinkingBlock_shouldStayInThinkingBucket() {
        Msg thinkingMsg = Msg.builder().role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking("用户想知道天气，我需要先确认城市").build()).build();
        when(agent.stream(any(List.class), any(StreamOptions.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(new Event(EventType.REASONING, thinkingMsg, false)));

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "今天天气怎么样").collectList().block();

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).reasoning());
        assertEquals("用户想知道天气，我需要先确认城市", chunks.get(0).text());
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

        List<ChatStreamChunk> chunks = chatService.chatStream("coder", "s1", "你好").collectList().block();

        assertEquals(1, chunks.size());
        assertEquals("你好", chunks.get(0).text());
    }
}
