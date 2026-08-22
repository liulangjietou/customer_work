package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheService;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式路径的语义缓存。
 *
 * <p>这条线此前是断的：查缓存与写缓存都只做在非流式的 {@code chat()} 上，而用户端 H5 走的是
 * WebSocket 流式（{@code ChatDispatchService} → {@code chatStream}）——真实流量一条缓存都
 * 产生不了，也一次都命中不了。开着开关、配着 jdbc，表却永远是空的。</p>
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceStreamCacheTest {

    private static final String SESSION_ID = "u42:conv-1";
    private static final SemanticCacheService.CacheGeneration CACHE_GENERATION =
        new SemanticCacheService.CacheGeneration("tenant", "test-generation", true);

    private CustomerServiceAgentFactory factory;
    private ReActAgent agent;
    private SemanticCacheService cache;
    private CustomerServiceService service;

    @BeforeEach
    void setUp() {
        factory = mock(CustomerServiceAgentFactory.class);
        agent = mock(ReActAgent.class);
        cache = mock(SemanticCacheService.class);
        SessionStateManager stateManager = mock(SessionStateManager.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        when(cache.captureGeneration()).thenReturn(CACHE_GENERATION);
        service = new CustomerServiceService(factory, stateManager, new CustomerWorkProperties(),
            empty(), empty(), empty(), empty(), providerOf(cache), empty());
    }

    @Test
    void cacheHit_shouldNotInvokeAgent() {
        when(cache.lookup(eq(CACHE_GENERATION), eq(SESSION_ID), anyString()))
            .thenReturn(Optional.of("七天无理由从签收次日算起。"));

        String joined = String.join("", service.chatStream(SESSION_ID, "几天无理由怎么算")
            .collectList().block());

        assertEquals("七天无理由从签收次日算起。", joined, "切片拼回去必须与缓存原文完全一致");
        // 命中的意义就在这里：Agent 装配、知识检索、模型调用全都不发生
        verify(agent, never()).streamEvents(anyList(), any(RuntimeContext.class));
        verify(factory, never()).createAgent(anyString());
    }

    @Test
    void cacheHit_shouldEmitMultipleChunks_forLongAnswer() {
        // 一个超长 chunk 会让前端消息气泡突然撑开，与真实流式的观感差太多
        String answer = "七".repeat(100);
        when(cache.lookup(eq(CACHE_GENERATION), eq(SESSION_ID), anyString()))
            .thenReturn(Optional.of(answer));

        List<String> chunks = service.chatStream(SESSION_ID, "几天无理由怎么算").collectList().block();

        assertEquals(answer, String.join("", chunks), "切片拼回去必须与缓存原文完全一致");
        assertTrue(chunks.size() > 1, "长答案要切开下发，不能一个巨大 chunk 砸过去");
    }

    @Test
    void cacheMiss_shouldInvokeAgentAndWriteCache() {
        when(cache.lookup(eq(CACHE_GENERATION), eq(SESSION_ID), anyString()))
            .thenReturn(Optional.empty());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(delta("运费"), delta("满 99 包邮。")));

        String joined = String.join("", service.chatStream(SESSION_ID, "运费怎么算")
            .collectList().block());

        assertEquals("运费满 99 包邮。", joined);
        // 缓存的必须是完整回复，不是最后一个增量
        verify(cache, timeout(2000)).put(eq(CACHE_GENERATION), eq(SESSION_ID),
            eq("运费怎么算"), eq("运费满 99 包邮。"));
    }

    @Test
    void streamFailure_shouldNotCacheHalfAnswer() {
        when(cache.lookup(eq(CACHE_GENERATION), eq(SESSION_ID), anyString()))
            .thenReturn(Optional.empty());
        // 已经吐了半句才失败：兜底文案会接在半句后面，把这段缓存下来，
        // 之后每个问到同类问题的人都会收到这段残缺回复
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.concat(Flux.just(delta("运费满")),
                Flux.error(new IllegalStateException("model down"))));

        service.chatStream(SESSION_ID, "运费怎么算").collectList().block();

        verify(cache, never()).put(any(SemanticCacheService.CacheGeneration.class),
            anyString(), anyString(), anyString());
    }

    @Test
    void withoutCache_shouldBehaveAsBefore() {
        CustomerServiceService plain = new CustomerServiceService(factory,
            mock(SessionStateManager.class), new CustomerWorkProperties(),
            empty(), empty(), empty(), empty(), empty(), empty());
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(delta("您好")));

        StepVerifier.create(plain.chatStream(SESSION_ID, "你好"))
            .expectNext("您好")
            .verifyComplete();
    }

    private TextBlockDeltaEvent delta(String text) {
        return new TextBlockDeltaEvent("r1", "b1", text);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
