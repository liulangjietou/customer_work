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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.calllog.AgentCallSessionType;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * {@link ChatService} 单测：重点覆盖 {@code toChunks} 对框架细粒度事件流（{@code streamEvents}）的分流——
 * ① {@code THINKING_BLOCK_DELTA} 归"思考过程"、{@code TEXT_BLOCK_DELTA} 才是可见回答正文；
 * ② {@code AGENT_RESULT} 只在本轮一个正文增量都没出现过时（非流式 provider）当兜底用一次；
 * ③ {@code TOOL_CALL_START} / {@code TOOL_RESULT_*} 让"决定调用工具"和"等工具返回"这两段时间也有节点，
 *    否则前端看起来像卡住；工具结果按 toolCallId 累积、在 {@code TOOL_RESULT_END} 一次性成文；
 * ④ 每轮对话固定以 {@link ChatNodeKind#THINKING_START}/{@link ChatNodeKind#THINKING_END} 收尾；
 * ⑤ {@link ChatNodeKind#MODEL_CALL} 一一对应框架的 {@code MODEL_CALL_START}，节点数即模型被调用次数；
 * ⑥ 工具调用按 {@link ToolSourceInfo} 分类成 SKILL/MCP/内置三种节点类型。
 *
 * <p><b>与迁移前的差异</b>：旧的 {@code stream(msgs, options, ctx)} 把增量与整段回放混在同一个
 * {@code REASONING} 事件里，消费侧得靠"新值是否以旧值为前缀"猜净增量；细粒度事件已经把两者拆成不同
 * 事件类型，那套猜测连同四份去重状态一起删了，对应的单测也随之改写（见
 * {@link #chatStream_shouldForwardDeltasVerbatim_withoutPrefixStripping}）。</p>
 * @author owlzhangfq@gmail.com
 */
class ChatServiceTest {

    /** 同一次模型调用内的事件共用的 replyId（框架每次调模型生成一个）。 */
    private static final String REPLY_ID = "reply-1";
    private static final String TOOL_CALL_ID = "call-1";
    private static final String TOOL_NAME = "OA考勤查询";
    /** 子 Agent 的来源 path（框架约定：{@code 父会话id/子agentId}）。 */
    private static final String SUB_SOURCE = "s1/doc-writer";
    private static final String SUB_NAME = "doc-writer";

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
        // 敏感词过滤与内容风控配置传 null provider：本测试聚焦事件分流，出站过滤整体跳过
        chatService = new ChatService(agentInstanceCache, agentInstanceFactory, historyCache, memorySyncService,
            new ExecutionModeRegistry(), new PlanConfirmationService(), chatAttachmentService, null, null);

        agent = mock(ReActAgent.class);
        when(agentInstanceCache.getOrBuild("coder")).thenReturn(agent);
        when(agentInstanceFactory.contextFor(anyString(), anyString())).thenReturn(mock(RuntimeContext.class));
        when(agentInstanceFactory.toolSourceFor(anyString())).thenReturn(ToolSourceInfo.EMPTY);
    }

    // ==================== 事件构造与订阅辅助 ====================

    @SuppressWarnings("unchecked")
    private void stubEvents(AgentEvent... events) {
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
            .thenReturn(Flux.just(events));
    }

    /** 一次工具调用的完整结果事件三连（非流式工具：框架在 END 前把整段输出当作一条 delta 发出）。 */
    private AgentEvent[] toolResultEvents(String output) {
        return new AgentEvent[] {
            new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME),
            new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, output),
            new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, ToolResultState.SUCCESS)
        };
    }

    private AgentEvent sourced(AgentEvent event) {
        return event.withSource(SUB_SOURCE);
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

    // ==================== 父 Agent 主链路 ====================

    @Test
    void chatStream_shouldAlwaysBracketWithThinkingStartAndEnd() {
        stubEvents(new AgentResultEvent(assistantMsg("你好")));

        List<ChatStreamChunk> chunks = stream("你好");

        assertEquals(ChatNodeKind.THINKING_START, chunks.get(0).kind());
        assertEquals(ChatNodeKind.THINKING_END, chunks.get(chunks.size() - 1).kind());
    }

    @Test
    void chatStream_shouldSurfaceToolCallRequest_soFrontendDoesNotLookStuck() {
        // 模型决定调工具、还没拿到结果的这段时间必须有节点，否则界面上什么都不动
        stubEvents(
            new ModelCallStartEvent(REPLY_ID),
            new ToolCallStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.TOOL_BUILTIN, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains(TOOL_NAME));
    }

    @Test
    void chatStream_shouldSurfaceToolResultText() {
        stubEvents(toolResultEvents("今日出勤：09:02 打卡"));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("工具「" + TOOL_NAME + "」返回：今日出勤：09:02 打卡", chunks.get(1).text());
    }

    @Test
    void chatStream_shouldConcatToolResultChunks_intoOneNode() {
        // 流式工具（如沙箱 execute）分多片吐结果：必须累积成一条完整文本再发，
        // 否则 TestReportParser 只能看到半截输出，解析不出 test_report。
        stubEvents(
            new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, "execute"),
            new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, "execute", "Exit code: 0\n"),
            new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, "execute", "BUILD SUCCESS"),
            new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, "execute", ToolResultState.SUCCESS));

        List<ChatStreamChunk> chunks = stream("跑一下测试");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("工具「execute」返回：Exit code: 0\nBUILD SUCCESS", chunks.get(1).text());
    }

    @Test
    void chatStream_toolResultWithoutAnyTextDelta_shouldStillEmitNode() {
        // 工具没有文本输出（只有二进制块或干脆没输出）：仍要发一条节点，不能让前端停在"等工具返回"
        stubEvents(
            new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME),
            new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, ToolResultState.SUCCESS));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("工具「" + TOOL_NAME + "」返回：(无文本结果)", chunks.get(1).text());
    }

    @Test
    void chatStream_shouldReAnnounceSameTool_inNextRound_andCountEachModelCall() {
        // 工具返回后模型必然被再调一轮：MODEL_CALL 节点数 = 模型实际被调用次数；
        // 同一个工具在新一轮里被再次调用（新的 toolCallId）也要重新提示一次。
        stubEvents(concat(
            new AgentEvent[] {
                new ModelCallStartEvent(REPLY_ID),
                new ToolCallStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME)
            },
            toolResultEvents("今日出勤：09:02 打卡"),
            new AgentEvent[] {
                new ModelCallStartEvent("reply-2"),
                new ToolCallStartEvent("reply-2", "call-2", TOOL_NAME)
            }));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL, ChatNodeKind.TOOL_BUILTIN,
            ChatNodeKind.TOOL_RESULT, ChatNodeKind.MODEL_CALL, ChatNodeKind.TOOL_BUILTIN, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains(TOOL_NAME));
        assertTrue(chunks.get(3).text().contains("今日出勤：09:02 打卡"));
        assertTrue(chunks.get(5).text().contains(TOOL_NAME));
    }

    @Test
    void chatStream_finalAgentResult_shouldBeAnswerKind_whenNothingStreamed() {
        // 非流式 provider 兜底：一个正文增量都没出现过时，用最终结果补一次全文
        stubEvents(new AgentResultEvent(assistantMsg("你今天已打卡")));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你今天已打卡", chunks.get(1).text());
    }

    @Test
    void chatStream_textBlockDelta_shouldStreamAsAnswerKind_notThinking() {
        // 逐字流出来的可见回答文本走 TEXT_BLOCK_DELTA，不是 AGENT_RESULT（那是一次性的完整最终文本）
        stubEvents(
            new ModelCallStartEvent(REPLY_ID),
            new TextBlockDeltaEvent(REPLY_ID, "text", "你好，"),
            new TextBlockDeltaEvent(REPLY_ID, "text", "我是智能体"));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.ANSWER, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你好，", chunks.get(2).text());
        assertEquals("我是智能体", chunks.get(3).text());
    }

    @Test
    void chatStream_shouldForwardDeltasVerbatim_withoutPrefixStripping() {
        // 迁移前的行为契约变更：旧路径的 REASONING 事件既发增量又发整段回放，消费侧靠"新值以旧值为前缀"
        // 猜净增量，代价是模型真吐出"abc"→"abcdef"这种前缀关系的<b>独立</b>分片时会被误截。细粒度事件
        // 的 TEXT_BLOCK_DELTA 定义上就是净增量（框架自己也是 append 累积它来还原全文），这里断言原样透传。
        stubEvents(
            new TextBlockDeltaEvent(REPLY_ID, "text", "abc"),
            new TextBlockDeltaEvent(REPLY_ID, "text", "abcdef"));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START,
            ChatNodeKind.ANSWER, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("abc", chunks.get(1).text());
        assertEquals("abcdef", chunks.get(2).text());
    }

    @Test
    void chatStream_thinkingBlockDelta_shouldStayInThinkingBucket() {
        stubEvents(
            new ModelCallStartEvent(REPLY_ID),
            new ThinkingBlockDeltaEvent(REPLY_ID, "thinking", "用户想知道天气，我需要先确认城市"));

        List<ChatStreamChunk> chunks = stream("今天天气怎么样");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.MODEL_CALL,
            ChatNodeKind.THINKING, ChatNodeKind.THINKING_END);
        assertEquals("用户想知道天气，我需要先确认城市", chunks.get(2).text());
    }

    @Test
    void chatStream_agentResult_shouldBeSuppressed_whenAnswerAlreadyStreamed() {
        // 已经逐字流出过正文时，结尾的 AGENT_RESULT（同一段文本的汇总）必须丢弃，
        // 否则前端会先看到逐字流出的答案，结束时又整段重复一遍。
        stubEvents(
            new TextBlockDeltaEvent(REPLY_ID, "text", "你好"),
            new AgentResultEvent(assistantMsg("你好")));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("你好", chunks.get(1).text());
    }

    @Test
    void chatStream_shouldClassifyToolCall_bySkillSource() {
        when(agentInstanceFactory.toolSourceFor("coder"))
            .thenReturn(new ToolSourceInfo(Set.of(TOOL_NAME), Set.of()));
        stubEvents(new ToolCallStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_SKILL, ChatNodeKind.THINKING_END);
    }

    @Test
    void chatStream_shouldClassifyToolCall_byMcpSource() {
        when(agentInstanceFactory.toolSourceFor("coder"))
            .thenReturn(new ToolSourceInfo(Set.of(), Set.of(TOOL_NAME)));
        stubEvents(new ToolCallStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.TOOL_MCP, ChatNodeKind.THINKING_END);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatStream_onError_shouldStillEmitThinkingEnd_beforeFallbackAnswer() {
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
            .thenReturn(Flux.error(new RuntimeException("model down")));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.THINKING_END, ChatNodeKind.ANSWER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatStream_shouldFallBackGracefully_whenAgentStreamThrowsSynchronously() {
        // 复现真实场景：HarnessAgent 在沙箱资源获取失败时（如 docker 容器创建超时）是方法调用本身
        // 同步抛异常，不是返回一个 error Flux。如果没包 Flux.defer，这个异常会在 chatStream(...) 方法体
        // 执行期间直接向外抛，onErrorResume 完全没机会接管，前端会收不到任何 SSE 事件（连接挂起）。
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
            .thenThrow(new RuntimeException("docker run timed out for image: maven:3.9-eclipse-temurin-17"));

        List<ChatStreamChunk> chunks = stream("写一个 Fibonacci.java");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.THINKING_END, ChatNodeKind.ANSWER);
    }

    // ===== 附件绑定（对话附件预览：随消息发送把附件绑定到本条用户消息 Msg.id）=====

    @Test
    void chatStream_withAttachmentIds_shouldBindToMessage_inRequestThread() {
        // attachmentIds 非空 → 请求线程同步段（订阅前）调 bindToMessage：sessionId 用归一值，messageId 为本条用户消息 id。
        stubEvents(new AgentResultEvent(assistantMsg("已收到附件")));
        List<String> ids = List.of("att-1", "att-2");

        chatService.chatStreamWithAttachments("coder", "s1", "看下这个附件", null, null, ids).collectList().block();

        verify(chatAttachmentService).bindToMessage(eq("coder"), eq("s1"), anyString(), eq(ids));
    }

    @Test
    void chatStream_emptySession_shouldBindWithDefaultSession() {
        // sessionId 空 → 归一成 "default"，与历史读取/Plan 通道口径一致。
        stubEvents(new AgentResultEvent(assistantMsg("ok")));
        List<String> ids = List.of("att-1");

        chatService.chatStreamWithAttachments("coder", "", "看下这个附件", null, null, ids).collectList().block();

        verify(chatAttachmentService).bindToMessage(eq("coder"), eq("default"), anyString(), eq(ids));
    }

    @Test
    void chatStream_withoutAttachmentIds_shouldNotBind() {
        stubEvents(new AgentResultEvent(assistantMsg("你好")));

        stream("你好");

        verify(chatAttachmentService, never()).bindToMessage(anyString(), anyString(), anyString(), any());
    }

    // ===== 子 Agent 事件流透传（harness spawn 出的子 Agent，事件由框架打上 source 后并入父流）=====

    @Test
    void chatStream_mainChunks_shouldCarryNullSourceAndSubagentName() {
        // 回归：source=null 的父 Agent 事件，产出 chunk 的 source/subagentName 必须为 null（前端据此判定"非子 Agent"）。
        stubEvents(new AgentResultEvent(assistantMsg("你今天已打卡")));

        List<ChatStreamChunk> chunks = stream("查一下我的考勤");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        for (ChatStreamChunk chunk : chunks) {
            assertNull(chunk.source(), "父 Agent 片段 source 应为 null，实际=" + chunk);
            assertNull(chunk.subagentName(), "父 Agent 片段 subagentName 应为 null，实际=" + chunk);
        }
    }

    @Test
    void chatStream_subagentEvents_shouldStampSource_andPrependSubagentStart() {
        // 框架在 spawn 点补的带 source 的 AGENT_START 是该子 Agent 的首个事件 → 补一条 SUBAGENT_START；
        // 后续 chunk 均带 source（调用链 path）与 subagentName（path 末段 = 子 agentId）。
        stubEvents(
            sourced(new AgentStartEvent("sub-session", null, SUB_NAME)),
            sourced(new ThinkingBlockDeltaEvent(REPLY_ID, "thinking", "分析文档结构")),
            sourced(new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME)),
            sourced(new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, "已生成大纲")),
            sourced(new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, ToolResultState.SUCCESS)));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.THINKING, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertEquals(SUB_NAME, chunks.get(1).text(), "SUBAGENT_START 文本为子 Agent 展示名");
        for (int i = 1; i <= 3; i++) {
            assertEquals(SUB_SOURCE, chunks.get(i).source(), "第 " + i + " 个 chunk source 不符，实际=" + chunks);
            assertEquals(SUB_NAME, chunks.get(i).subagentName());
        }
        assertTrue(chunks.get(2).text().contains("分析文档结构"));
        assertTrue(chunks.get(3).text().contains("已生成大纲"));
        // 父 Agent 框架节点（THINKING_START/END）source 仍为 null
        assertNull(chunks.get(0).source());
        assertNull(chunks.get(4).source());
    }

    @Test
    void chatStream_subagentAgentEnd_shouldEmitSubagentResult_withAccumulatedAnswer() {
        // 子 Agent 的 AgentResultEvent 在子流内部就被 callInternal 取走了、到不了父流，
        // 故 SUBAGENT_RESULT 改由带 source 的 AGENT_END 触发，内容取本股流累积的正文增量。
        stubEvents(
            sourced(new AgentStartEvent("sub-session", null, SUB_NAME)),
            sourced(new TextBlockDeltaEvent(REPLY_ID, "text", "文档已")),
            sourced(new TextBlockDeltaEvent(REPLY_ID, "text", "生成完毕")),
            sourced(new AgentEndEvent(null)));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.ANSWER, ChatNodeKind.ANSWER, ChatNodeKind.SUBAGENT_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("文档已生成完毕", chunks.get(4).text());
        assertEquals(SUB_SOURCE, chunks.get(4).source());
        assertEquals(SUB_NAME, chunks.get(4).subagentName());
    }

    @Test
    void chatStream_subagentAnswer_shouldNotSuppressParentAgentResult() {
        // 子 Agent 流出的正文不能把父 Agent 的"是否已流式过正文"标记染上：父侧一个增量都没有时，
        // 结尾的 AGENT_RESULT 仍要作为兜底全文发出去，否则用户看不到父 Agent 的最终回答。
        stubEvents(
            sourced(new AgentStartEvent("sub-session", null, SUB_NAME)),
            sourced(new TextBlockDeltaEvent(REPLY_ID, "text", "子结论")),
            sourced(new AgentEndEvent(null)),
            new AgentResultEvent(assistantMsg("父Agent收尾")));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START, ChatNodeKind.ANSWER,
            ChatNodeKind.SUBAGENT_RESULT, ChatNodeKind.ANSWER, ChatNodeKind.THINKING_END);
        assertEquals("父Agent收尾", chunks.get(4).text());
        assertNull(chunks.get(4).source());
    }

    @Test
    void chatStream_interleavedParentAndChild_shouldBufferToolResultsIndependently() {
        // 父子交错推流且工具调用 id 相同（两个 Agent 各自的 toolCallId 空间独立、完全可能撞号）：
        // 若父子共用一份缓冲，两股工具输出会被拼进同一个 StringBuilder。这里断言各自成文。
        stubEvents(
            new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME),
            sourced(new ToolResultStartEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME)),
            new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, "父结果"),
            sourced(new ToolResultTextDeltaEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, "子结果")),
            new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, ToolResultState.SUCCESS),
            sourced(new ToolResultEndEvent(REPLY_ID, TOOL_CALL_ID, TOOL_NAME, ToolResultState.SUCCESS)));

        List<ChatStreamChunk> chunks = stream("你好");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.TOOL_RESULT, ChatNodeKind.TOOL_RESULT, ChatNodeKind.THINKING_END);
        assertEquals("工具「" + TOOL_NAME + "」返回：父结果", chunks.get(2).text());
        assertNull(chunks.get(2).source());
        assertEquals("工具「" + TOOL_NAME + "」返回：子结果", chunks.get(3).text());
        assertEquals(SUB_SOURCE, chunks.get(3).source());
    }

    @Test
    void chatStream_subagentUnrelatedEventType_shouldBeIgnored_afterSubagentStart() {
        // 子 Agent 的细粒度事件里有大量不进展示轨迹的类型（block start/end、TOOL_CALL_DELTA 等）——
        // 应被忽略不抛异常，仅保留首次出现补的 SUBAGENT_START；随后真正的思考增量正常产出。
        stubEvents(
            sourced(new ModelCallEndEvent(REPLY_ID, new ChatUsage(1, 1, 0.0))),
            sourced(new ThinkingBlockDeltaEvent(REPLY_ID, "thinking", "继续分析")));

        List<ChatStreamChunk> chunks = stream("帮我写文档");

        assertKinds(chunks, ChatNodeKind.THINKING_START, ChatNodeKind.SUBAGENT_START,
            ChatNodeKind.THINKING, ChatNodeKind.THINKING_END);
        assertTrue(chunks.get(2).text().contains("继续分析"));
    }

    // ===== 模型用量聚合（审计需求 §5.3：token 数）=====

    @Test
    void chatStream_shouldAggregateUsageAcrossModelCalls() {
        // 每次模型调用结束各带一条 MODEL_CALL_END，replyId 不同 → 各算一次；
        // 子 Agent 的模型调用同样计入（它也是本轮真实消耗的 token）。
        stubEvents(
            new ModelCallEndEvent(REPLY_ID, new ChatUsage(10, 20, 0.2)),
            sourced(new ModelCallEndEvent("reply-sub", new ChatUsage(5, 7, 0.1))),
            new AgentResultEvent(assistantMsg("done")));

        AtomicReference<ChatUsage> observed = new AtomicReference<>();
        chatService.chatStream("coder", "s1", "你好", observed::set).collectList().block();

        assertEquals(15, observed.get().getInputTokens());
        assertEquals(27, observed.get().getOutputTokens());
        assertEquals(42, observed.get().getTotalTokens());
    }

    @Test
    void chatStream_shouldDedupeUsage_bySameModelCallReplyId() {
        // 同一次模型调用只会来一条 MODEL_CALL_END；即便重复到达也按 replyId 覆盖而非累加。
        stubEvents(
            new ModelCallEndEvent(REPLY_ID, new ChatUsage(3, 4, 0.1)),
            new ModelCallEndEvent(REPLY_ID, new ChatUsage(10, 20, 0.2)));

        AtomicReference<ChatUsage> observed = new AtomicReference<>();
        chatService.chatStream("coder", "s1", "你好", observed::set).collectList().block();

        assertEquals(10, observed.get().getInputTokens());
        assertEquals(20, observed.get().getOutputTokens());
    }

    @Test
    void chatStream_usageObserver_shouldFireBeforeDownstreamTerminalCallback() {
        // VibeCodingService 在本流的<b>下游</b>用 doFinally 读 usageTotal 落审计。Reactor 的 doFinally
        // 语义是"先把终止信号传给下游、回调最后才跑"，用量回调若也挂 doFinally，下游必然读到 null；
        // 迁移加的 publishOn 还会把这个顺序问题跨线程放大（连 block() 都可能先返回）。
        // 这里模拟下游消费者，钉住"上游先写、下游后读"这条契约。
        stubEvents(new ModelCallEndEvent(REPLY_ID, new ChatUsage(10, 20, 0.2)));

        AtomicReference<ChatUsage> usageTotal = new AtomicReference<>();
        AtomicReference<ChatUsage> seenByDownstream = new AtomicReference<>();
        chatService.chatStream("coder", "s1", "你好", usageTotal::set)
            .doFinally(signal -> seenByDownstream.set(usageTotal.get()))
            .collectList().block();

        assertNotNull(seenByDownstream.get(), "下游的终止回调必须已经能读到本轮用量汇总");
        assertEquals(10, seenByDownstream.get().getInputTokens());
    }

    @Test
    void chatStream_shouldObserveNullUsage_whenNoModelCallCarriesUsage() {
        stubEvents(new AgentResultEvent(assistantMsg("你好")));

        AtomicReference<ChatUsage> observed = new AtomicReference<>(new ChatUsage(1, 1, 0));
        chatService.chatStream("coder", "s1", "你好", observed::set).collectList().block();

        assertNull(observed.get(), "无任何用量信息时应回调 null，区分“没有用量”与“用了 0 token”");
    }

    // ---- 知识库自动检索注入 ----

    /** 捕获真正送进 Agent 的用户消息文本。 */
    @SuppressWarnings("unchecked")
    private String capturedUserText() {
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).streamEvents(captor.capture(), any(RuntimeContext.class));
        return captor.getValue().get(0).getTextContent();
    }

    /**
     * 送进 Agent（→ 进 AgentState → 进用户可见历史）的用户消息必须是<b>原文</b>：知识库召回内容
     * 已改由 {@code KnowledgeRetrievalMiddleware} 在推理阶段挂成瞬态消息，不再拼进这条消息文本。
     * 这条断言就是"用户历史里绝不出现 &lt;retrieved_knowledge&gt;"的守门测试。
     */
    @Test
    void chatStream_shouldSendRawUserTextToAgent_withoutAnyInjection() {
        stubEvents(new AgentResultEvent(assistantMsg("好的")));

        chatService.chatStream("coder", "s1", "公积金怎么提取").collectList().block();

        String sent = capturedUserText();
        assertEquals("公积金怎么提取", sent);
        assertFalse(sent.contains("<retrieved_knowledge>"),
            "召回内容绝不能拼进用户消息——那会随 AgentState 持久化并回显给用户，且每轮重发累积 token");
    }

    /** VibeCoding 链路同理：送进 Agent 的仍是"路径指引 + 提问"原文，不得被检索结果污染。 */
    @Test
    void chatStream_shouldNotAlterVibeCodingDirectiveText() {
        stubEvents(new AgentResultEvent(assistantMsg("好的")));
        String directivePrefixed = "[VibeCoding指引-local] 本次对话的会话目录为: sessions/s1/\n\n写一个冒泡排序";
        AgentCallMeta callMeta = new AgentCallMeta("req-1", "admin", "coder", "编码助手",
            AgentCallSessionType.VIBE_CODING, "写一个冒泡排序");

        chatService.chatStream("coder", "s1", directivePrefixed, null, callMeta).collectList().block();

        assertEquals(directivePrefixed, capturedUserText());
    }

    // ==================== 小工具 ====================

    private Msg assistantMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private AgentEvent[] concat(AgentEvent[]... groups) {
        int size = 0;
        for (AgentEvent[] group : groups) {
            size += group.length;
        }
        AgentEvent[] merged = new AgentEvent[size];
        int offset = 0;
        for (AgentEvent[] group : groups) {
            System.arraycopy(group, 0, merged, offset, group.length);
            offset += group.length;
        }
        return merged;
    }
}
