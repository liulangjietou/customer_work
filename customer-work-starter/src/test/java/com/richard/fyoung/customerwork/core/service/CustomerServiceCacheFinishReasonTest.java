package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheService;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义缓存只收正常收尾的答复：按本轮最终结果的结束原因判定，流式与非流式两条路径同一口径。
 *
 * <p><b>为什么要看结束原因</b>：命中缓存时 Agent 根本不会运行——不转人工、不发审批、不续上被打断的回答，
 * 只把上次的文本原样再放一遍。轮次用尽的收尾带着「已为您转接人工客服」、等待审批的答复停在「请稍候确认」、
 * 被打断的只有半句；把它们缓存下来，下一次问到同类问题时用户收到的是一句承诺，而那句承诺背后什么都不会发生。
 * 用户端 H5 本就把这些结束原因显示为「答复尚未完成 / 等待后续处理 / 答复已中断」，缓存却当成完整答复收下了。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceCacheFinishReasonTest {

    private static final String SESSION_ID = "u42:conv-1";
    private static final String QUESTION = "运费怎么算";
    private static final String ANSWER = "运费满 99 包邮。";
    private static final SemanticCacheService.CacheGeneration CACHE_GENERATION =
        new SemanticCacheService.CacheGeneration("tenant", "test-generation", true);
    /**
     * 断言「没写缓存」前的等待：写缓存挪在弹性线程池上异步执行，不等它落地就断言，
     * 修复前的实现也能侥幸通过。
     */
    private static final long ASYNC_WRITE_GRACE_MS = 200;
    private static final long ASYNC_WRITE_TIMEOUT_MS = 2000;

    private ReActAgent agent;
    private SemanticCacheService cache;
    private CustomerServiceService service;

    @BeforeEach
    void setUp() {
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        agent = mock(ReActAgent.class);
        cache = mock(SemanticCacheService.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        when(cache.captureGeneration()).thenReturn(CACHE_GENERATION);
        when(cache.lookup(eq(CACHE_GENERATION), anyString(), anyString())).thenReturn(Optional.empty());
        service = new CustomerServiceService(factory, mock(SessionStateManager.class), new CustomerWorkProperties(),
            empty(), empty(), empty(), empty(), providerOf(cache), empty());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GenerateReason.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"MODEL_STOP", "STRUCTURED_OUTPUT"})
    @DisplayName("流式：没有正常收尾的答复照常下发，但不写缓存")
    void streamReplyNotFinished_shouldNotBeCached(GenerateReason reason) {
        streamReturns(delta("运费"), delta("满 99 包邮。"), finalResult(reason));

        assertEquals(ANSWER, streamed(), "缓存判定不该影响本轮下发给用户的内容");
        assertNotCached();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GenerateReason.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"MODEL_STOP", "STRUCTURED_OUTPUT"})
    @DisplayName("非流式：没有正常收尾的答复照常返回，但不写缓存")
    void callReplyNotFinished_shouldNotBeCached(GenerateReason reason) {
        callReturns(finalMsg(reason));

        assertEquals(ANSWER, service.chat(SESSION_ID, QUESTION).block(), "缓存判定不该影响本轮返回给用户的内容");
        assertNotCached();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GenerateReason.class, names = {"MODEL_STOP", "STRUCTURED_OUTPUT"})
    @DisplayName("流式：正常收尾的答复写缓存，写的是完整回复")
    void streamReplyFinished_shouldBeCached(GenerateReason reason) {
        streamReturns(delta("运费"), delta("满 99 包邮。"), finalResult(reason));

        assertEquals(ANSWER, streamed());
        assertCached(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GenerateReason.class, names = {"MODEL_STOP", "STRUCTURED_OUTPUT"})
    @DisplayName("非流式：正常收尾的答复写缓存")
    void callReplyFinished_shouldBeCached(GenerateReason reason) {
        callReturns(finalMsg(reason));

        assertEquals(ANSWER, service.chat(SESSION_ID, QUESTION).block());
        assertCached(1);
    }

    /**
     * 框架（2.0.3）正常收尾时<b>不写</b>结束原因——{@code ReActAgent} 只给用尽、审批、中断这类收尾标原因，
     * {@code Msg#getGenerateReason()} 对缺失的键返回 {@code MODEL_STOP}。判定若改成只认显式写入的原因，
     * 缓存会一条都写不进去，而且不报任何错。
     */
    @Test
    @DisplayName("最终结果没带结束原因（框架正常收尾的实际形态）：两条路径都按正常结束写缓存")
    void finishedWithoutRecordedReason_shouldBeCachedOnBothPaths() {
        Msg unmarked = Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(TextBlock.builder().text(ANSWER).build()).build();
        streamReturns(delta(ANSWER), new AgentResultEvent(unmarked));
        callReturns(unmarked);

        assertEquals(ANSWER, streamed());
        assertEquals(ANSWER, service.chat(SESSION_ID, QUESTION).block());
        assertCached(2);
    }

    @Test
    @DisplayName("流式：始终没收到最终结果（无从判断怎么收尾）的答复不写缓存")
    void streamWithoutFinalResult_shouldNotBeCached() {
        streamReturns(delta("运费"), delta("满 99 包邮。"));

        assertEquals(ANSWER, streamed());
        assertNotCached();
    }

    @Test
    @DisplayName("流式：非流式模型只给最终结果时，全文照常补发，缓存同样按它的结束原因判定")
    void nonStreamingProviderNotFinished_shouldNotBeCached() {
        streamReturns(finalResult(GenerateReason.MAX_ITERATIONS));

        assertEquals(ANSWER, streamed(), "一个增量都没出过时仍要用最终结果补全文");
        assertNotCached();
    }

    @Test
    @DisplayName("流式：收到正常收尾之后才失败的，同样不写缓存——兜底文案已经接在正文后面")
    void streamFailingAfterFinishedResult_shouldNotBeCached() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.concat(
            Flux.just(delta(ANSWER), finalResult(GenerateReason.MODEL_STOP)),
            Flux.error(new IllegalStateException("state save failed"))));

        assertEquals(ANSWER + CustomerServiceService.FALLBACK_REPLY, streamed());
        assertNotCached();
    }

    @Test
    @DisplayName("非流式：拿到正常收尾的结果之后才失败的，兜底回复同样不写缓存")
    void callFailingAfterFinishedResult_shouldNotBeCached() {
        Msg unreadable = mock(Msg.class);
        when(unreadable.getGenerateReason()).thenReturn(GenerateReason.MODEL_STOP);
        when(unreadable.getTextContent()).thenThrow(new IllegalStateException("malformed content"));
        callReturns(unreadable);

        assertEquals(CustomerServiceService.FALLBACK_REPLY, service.chat(SESSION_ID, QUESTION).block());
        assertNotCached();
    }

    @Test
    @DisplayName("非流式：调用失败的兜底回复不写缓存")
    void callFallback_shouldNotBeCached() {
        when(agent.call(anyString(), any(RuntimeContext.class)))
            .thenReturn(Mono.error(new IllegalStateException("model down")));

        assertEquals(CustomerServiceService.FALLBACK_REPLY, service.chat(SESSION_ID, QUESTION).block());
        assertNotCached();
    }

    // ---------- 辅助 ----------

    private void streamReturns(AgentEvent... events) {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(events));
    }

    private void callReturns(Msg reply) {
        when(agent.call(anyString(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
    }

    private String streamed() {
        return String.join("", service.chatStream(SESSION_ID, QUESTION).collectList().block());
    }

    private void assertCached(int times) {
        verify(cache, timeout(ASYNC_WRITE_TIMEOUT_MS).times(times))
            .put(eq(CACHE_GENERATION), eq(SESSION_ID), eq(QUESTION), eq(ANSWER));
    }

    private void assertNotCached() {
        verify(cache, after(ASYNC_WRITE_GRACE_MS).never())
            .put(any(SemanticCacheService.CacheGeneration.class), anyString(), anyString(), anyString());
    }

    private static TextBlockDeltaEvent delta(String text) {
        return new TextBlockDeltaEvent("r1", "b1", text);
    }

    private static AgentResultEvent finalResult(GenerateReason reason) {
        return new AgentResultEvent(finalMsg(reason));
    }

    private static Msg finalMsg(GenerateReason reason) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(TextBlock.builder().text(ANSWER).build())
            .generateReason(reason).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
