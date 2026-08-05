package com.richard.fyoung.customerwork.rag.search;

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
 * {@link KnowledgeInjectionMiddleware} 单测（召回来源用假 {@link KnowledgeRetrievalProvider}）：
 * 守住评审定下的三条硬约束。
 *
 * <ol>
 *   <li>检索不得跑在调用方（Tomcat 请求）线程上 → 断言 retrieve 实际执行线程不是测试线程；</li>
 *   <li>注入只对本次模型调用可见 → 断言原消息对象与入参列表一个字节都没被改动，
 *       注入是<b>新增</b>的一条带 synthetic 标记的消息；</li>
 *   <li>检索失败/无召回绝不打断对话 → 断言仍然照常 next.apply。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
class KnowledgeInjectionMiddlewareTest {

    private static final String AGENT_CODE = "kb-agent";
    private static final String BLOCK = "<retrieved_knowledge>\n[1] knowledge_base=产品库 内容\n</retrieved_knowledge>";

    private KnowledgeRetrievalProvider retrievalProvider;
    private KnowledgeInjectionMiddleware middleware;

    @BeforeEach
    void setUp() {
        retrievalProvider = mock(KnowledgeRetrievalProvider.class);
        middleware = new KnowledgeInjectionMiddleware(retrievalProvider, AGENT_CODE);
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
        when(retrievalProvider.retrieve(anyString(), anyString())).thenAnswer(inv -> {
            retrievalThread.set(Thread.currentThread().getName());
            return BLOCK;
        });

        RuntimeContext ctx = RuntimeContext.builder().userId(AGENT_CODE).sessionId("s1").build();
        middleware.onReasoning(null, ctx, inputOf(userMsg("公积金怎么提取")), input -> Flux.empty())
            .blockLast();

        // 硬约束1：阻塞式 HTTP 检索必须被 subscribeOn(boundedElastic) 挪走，绝不占用调用方线程。
        // 调用方线程在生产上就是 Tomcat worker（Controller 返回 Flux 不代表业务方法体异步）。
        assertNotEquals(Thread.currentThread().getName(), retrievalThread.get(),
            "检索不得在调用方线程执行，否则 RAG 后端变慢会拖垮整个服务的请求线程池");
        assertTrue(retrievalThread.get().startsWith("boundedElastic-"),
            "应落在 boundedElastic 弹性线程池，实际=" + retrievalThread.get());
    }

    @Test
    void shouldInjectAsExtraTransientMessage_withoutTouchingUserMessage() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenReturn(BLOCK);
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
        // 召回内容经 ContentSpotlighter 包进随机标签隔离块（间接注入防护）：原文完整保留，外面多一层标记
        assertTrue(injected.getTextContent().contains(BLOCK), "召回原文应完整保留");
        assertTrue(injected.getTextContent().startsWith("<untrusted_"), "召回内容应被隔离标记包裹");
        assertTrue(injected.getTextContent().contains("source=\"knowledge_base\""), "应标出来源是知识库");
        assertEquals(MsgRole.USER, injected.getRole());
        assertEquals(Boolean.TRUE, injected.getMetadata().get(Msg.METADATA_SYNTHETIC),
            "注入消息必须打 synthetic 标记，任何观察到它的消费方都能识别并跳过");
    }

    /** 只包隔离块不说明规则，模型看不懂标签含义，防护会打折；且规则追加必须幂等（同智能体可能挂多个隔离中间件）。 */
    @Test
    void shouldAppendSpotlightHintToSystemPrompt_idempotently() {
        String once = middleware.onSystemPrompt(null, null, "你是客服助手").block();
        String twice = middleware.onSystemPrompt(null, null, once).block();

        assertTrue(once.startsWith("你是客服助手"), "原始提示词必须保留在前");
        assertTrue(once.length() > "你是客服助手".length(), "应追加隔离规则说明");
        assertEquals(once, twice, "重复追加必须幂等，否则同一智能体挂多个隔离中间件会写重复");
    }

    @Test
    void shouldRetrieveOncePerTurn_andReinjectOnLaterReasoningSteps() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenReturn(BLOCK);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        AtomicReference<ReasoningInput> second = new AtomicReference<>();

        // ReAct 循环：一轮对话里 onReasoning 会被调多次（每次工具返回后再推理一次）
        middleware.onReasoning(null, ctx, inputOf(userMsg("问题")), input -> Flux.empty()).blockLast();
        middleware.onReasoning(null, ctx, inputOf(userMsg("问题")), capturing(second)).blockLast();

        verify(retrievalProvider, times(1)).retrieve(eq(AGENT_CODE), anyString());
        assertEquals(2, second.get().messages().size(), "后续推理步仍要注入（缓存复用），模型才始终看得到参考资料");
    }

    /** 子 Agent 可能与父 Agent 共用同一个 RuntimeContext：缓存必须按 agentCode 隔离，否则子 Agent 会用到父的召回块。 */
    @Test
    void shouldNotShareCacheAcrossAgents_onSameRuntimeContext() {
        when(retrievalProvider.retrieve(eq(AGENT_CODE), anyString())).thenReturn(BLOCK);
        when(retrievalProvider.retrieve(eq("sub-agent"), anyString())).thenReturn(null);
        RuntimeContext sharedCtx = RuntimeContext.builder().sessionId("s1").build();
        KnowledgeInjectionMiddleware subMiddleware =
            new KnowledgeInjectionMiddleware(retrievalProvider, "sub-agent");
        AtomicReference<ReasoningInput> subPassed = new AtomicReference<>();

        middleware.onReasoning(null, sharedCtx, inputOf(userMsg("问题")), input -> Flux.empty()).blockLast();
        ReasoningInput subInput = inputOf(userMsg("问题"));
        subMiddleware.onReasoning(null, sharedCtx, subInput, capturing(subPassed)).blockLast();

        verify(retrievalProvider).retrieve("sub-agent", "问题");
        assertSame(subInput, subPassed.get(), "子 Agent 自己没召回到东西，就不该被父 Agent 的缓存块污染");
    }

    @Test
    void shouldPreferCallMetaQuestion_overRawMessageText() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenReturn(null);
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").build();
        ctx.put(AgentCallMeta.class,
            new AgentCallMeta("req-1", "tester", AGENT_CODE, "KB助手", null, "写一个冒泡排序"));
        // VibeCoding 链路送进 Agent 的是"路径指引 + 提问"，拿它检索等于给 RAG 喂指令噪声
        String directivePrefixed = "[VibeCoding指引-local] 本次对话的会话目录为: sessions/s1/\n\n写一个冒泡排序";

        middleware.onReasoning(null, ctx, inputOf(userMsg(directivePrefixed)), input -> Flux.empty()).blockLast();

        verify(retrievalProvider).retrieve(AGENT_CODE, "写一个冒泡排序");
    }

    @Test
    void shouldFallBackToLastUserMessage_whenNoCallMeta() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenReturn(null);

        // 最后一条消息是 ASSISTANT（ReAct 循环中常见），必须回溯到最后一条 USER 消息
        ReasoningInput input = new ReasoningInput(List.of(
            userMsg("公积金怎么提取"),
            Msg.builder().role(MsgRole.ASSISTANT).name("bot")
                .content(TextBlock.builder().text("让我查一下").build()).build()), List.of(), null);

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input,
            in -> Flux.empty()).blockLast();

        verify(retrievalProvider).retrieve(AGENT_CODE, "公积金怎么提取");
    }

    @Test
    void shouldPassThrough_whenNothingRetrieved() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenReturn(null);
        ReasoningInput input = inputOf(userMsg("问题"));
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, capturing(passed))
            .blockLast();

        assertSame(input, passed.get(), "无召回时连列表拷贝都不做，原样透传");
    }

    @Test
    void shouldNotBreakConversation_whenRetrievalThrows() {
        when(retrievalProvider.retrieve(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
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

        verify(retrievalProvider, never()).retrieve(anyString(), anyString());
        assertSame(input, passed.get());
    }
}
