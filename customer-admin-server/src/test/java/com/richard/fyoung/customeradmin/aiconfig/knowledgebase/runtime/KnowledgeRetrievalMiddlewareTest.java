package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalMiddleware} 单测：守住评审定下的三条硬约束。
 *
 * <ol>
 *   <li>检索不得跑在调用方（Tomcat 请求）线程上 → 断言 retrieve 实际执行线程不是测试线程；</li>
 *   <li>注入只对本次模型调用可见 → 断言原消息对象与入参列表一个字节都没被改动，
 *       注入是<b>新增</b>的一条带 synthetic 标记的消息；</li>
 *   <li>检索失败/无召回绝不打断对话 → 断言仍然照常 next.apply。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
class KnowledgeRetrievalMiddlewareTest {

    private static final String AGENT_CODE = "kb-agent";
    private static final String BLOCK = "<retrieved_knowledge>\n[1] knowledge_base=产品库 内容\n</retrieved_knowledge>";

    private KnowledgeRetrievalService retrievalService;
    private KnowledgeRetrievalMiddleware middleware;

    @BeforeEach
    void setUp() {
        retrievalService = mock(KnowledgeRetrievalService.class);
        middleware = new KnowledgeRetrievalMiddleware(retrievalService, AGENT_CODE);
    }

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private ReasoningInput inputOf(Msg... msgs) {
        return new ReasoningInput(List.of(msgs), List.of(), null);
    }

    /** 捕获中间件真正传给下游的 ReasoningInput（即最终发给模型的消息列表）。 */
    private Function<ReasoningInput, Flux<AgentEvent>> capturing(AtomicReference<ReasoningInput> sink) {
        return input -> {
            sink.set(input);
            return Flux.empty();
        };
    }

    @Test
    void shouldRunRetrievalOffCallerThread() {
        AtomicReference<String> retrievalThread = new AtomicReference<>();
        when(retrievalService.retrieve(anyString(), anyString())).thenAnswer(inv -> {
            retrievalThread.set(Thread.currentThread().getName());
            return BLOCK;
        });

        RuntimeContext ctx = RuntimeContext.builder().userId(AGENT_CODE).sessionId("s1").build();
        middleware.onReasoning(null, ctx, inputOf(userMsg("公积金怎么提取")), input -> Flux.empty())
            .blockLast();

        // 硬约束1：阻塞式 HTTP 检索必须被 subscribeOn(boundedElastic) 挪走，绝不占用调用方线程。
        // 调用方线程在生产上就是 Tomcat worker（ChatController.stream 返回 Flux 不代表方法体异步）。
        assertNotEquals(Thread.currentThread().getName(), retrievalThread.get(),
            "检索不得在调用方线程执行，否则 RAG 后端变慢会拖垮整个 admin-server 的请求线程池");
        assertTrue(retrievalThread.get().startsWith("boundedElastic-"),
            "应落在 boundedElastic 弹性线程池，实际=" + retrievalThread.get());
    }

    @Test
    void shouldInjectAsExtraTransientMessage_withoutTouchingUserMessage() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(BLOCK);
        Msg original = userMsg("公积金怎么提取");
        ReasoningInput input = inputOf(original);
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, capturing(passed))
            .blockLast();

        List<Msg> sent = passed.get().messages();
        assertEquals(2, sent.size(), "注入应是新增一条消息，而不是改写原消息");
        // 硬约束2的关键：用户那条消息（会被框架写进 AgentState 并回显给用户）必须原封不动——
        // 同一个对象引用、同一个 Msg.id、同一段文本，注入痕迹一个字符都不能沾。
        assertSame(original, sent.get(0));
        assertEquals("公积金怎么提取", sent.get(0).getTextContent());
        assertFalse(sent.get(0).getTextContent().contains("<retrieved_knowledge>"));
        // 入参列表本身也不能被就地改动（框架复用该列表做别的事时不能被污染）
        assertEquals(1, input.messages().size(), "不得就地修改传入的消息列表");

        Msg injected = sent.get(1);
        assertEquals(BLOCK, injected.getTextContent());
        assertEquals(MsgRole.USER, injected.getRole());
        assertEquals(Boolean.TRUE, injected.getMetadata().get(Msg.METADATA_SYNTHETIC),
            "注入消息必须打 synthetic 标记，任何观察到它的消费方都能识别并跳过");
    }

    @Test
    void shouldRetrieveOncePerTurn_andReinjectOnLaterReasoningSteps() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(BLOCK);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        AtomicReference<ReasoningInput> second = new AtomicReference<>();

        // ReAct 循环：一轮对话里 onReasoning 会被调多次（每次工具返回后再推理一次）
        middleware.onReasoning(null, ctx, inputOf(userMsg("问题")), input -> Flux.empty()).blockLast();
        middleware.onReasoning(null, ctx, inputOf(userMsg("问题")), capturing(second)).blockLast();

        verify(retrievalService, times(1)).retrieve(eq(AGENT_CODE), anyString());
        assertEquals(2, second.get().messages().size(), "后续推理步仍要注入（缓存复用），模型才始终看得到参考资料");
    }

    /** 子 Agent 可能与父 Agent 共用同一个 RuntimeContext：缓存必须按 agentCode 隔离，否则子 Agent 会用到父的召回块。 */
    @Test
    void shouldNotShareCacheAcrossAgents_onSameRuntimeContext() {
        when(retrievalService.retrieve(eq(AGENT_CODE), anyString())).thenReturn(BLOCK);
        when(retrievalService.retrieve(eq("sub-agent"), anyString())).thenReturn(null);
        RuntimeContext sharedCtx = RuntimeContext.builder().sessionId("s1").build();
        KnowledgeRetrievalMiddleware subMiddleware =
            new KnowledgeRetrievalMiddleware(retrievalService, "sub-agent");
        AtomicReference<ReasoningInput> subPassed = new AtomicReference<>();

        middleware.onReasoning(null, sharedCtx, inputOf(userMsg("问题")), input -> Flux.empty()).blockLast();
        ReasoningInput subInput = inputOf(userMsg("问题"));
        subMiddleware.onReasoning(null, sharedCtx, subInput, capturing(subPassed)).blockLast();

        verify(retrievalService).retrieve("sub-agent", "问题");
        assertSame(subInput, subPassed.get(), "子 Agent 自己没召回到东西，就不该被父 Agent 的缓存块污染");
    }

    @Test
    void shouldPreferCallMetaQuestion_overRawMessageText() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(null);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        ctx.put(AgentCallMeta.class,
            new AgentCallMeta("req-1", "tester", AGENT_CODE, "KB助手", null, "写一个冒泡排序"));
        // VibeCoding 链路送进 Agent 的是"路径指引 + 提问"，拿它检索等于给 RAG 喂指令噪声
        String directivePrefixed = "[VibeCoding指引-local] 本次对话的会话目录为: sessions/s1/\n\n写一个冒泡排序";

        middleware.onReasoning(null, ctx, inputOf(userMsg(directivePrefixed)), input -> Flux.empty()).blockLast();

        verify(retrievalService).retrieve(AGENT_CODE, "写一个冒泡排序");
    }

    @Test
    void shouldFallBackToLastUserMessage_whenNoCallMeta() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(null);

        // 最后一条消息是 ASSISTANT（ReAct 循环中常见），必须回溯到最后一条 USER 消息
        ReasoningInput input = new ReasoningInput(List.of(
            userMsg("公积金怎么提取"),
            Msg.builder().role(MsgRole.ASSISTANT).name("bot")
                .content(TextBlock.builder().text("让我查一下").build()).build()), List.of(), null);

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input,
            in -> Flux.empty()).blockLast();

        verify(retrievalService).retrieve(AGENT_CODE, "公积金怎么提取");
    }

    @Test
    void shouldPassThrough_whenNothingRetrieved() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(null);
        ReasoningInput input = inputOf(userMsg("问题"));
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, capturing(passed))
            .blockLast();

        assertSame(input, passed.get(), "无召回时连列表拷贝都不做，原样透传");
    }

    @Test
    void shouldNotBreakConversation_whenRetrievalThrows() {
        when(retrievalService.retrieve(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
        ReasoningInput input = inputOf(userMsg("问题"));
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        // 硬约束3：检索失败绝不打断对话——不抛异常，照常把原始输入交给下游继续推理
        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, capturing(passed))
            .blockLast();

        assertSame(input, passed.get());
    }

    @Test
    void shouldSkipRetrieval_whenNoQueryResolvable() {
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, capturing(passed))
            .blockLast();

        verify(retrievalService, never()).retrieve(anyString(), anyString());
        assertSame(input, passed.get());
    }
}
