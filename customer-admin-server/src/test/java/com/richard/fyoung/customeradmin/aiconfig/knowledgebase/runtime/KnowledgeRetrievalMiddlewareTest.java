package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.richard.fyoung.customerwork.rag.search.KnowledgeInjectionMiddleware;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalMiddleware} 单测：本类是 starter {@link KnowledgeInjectionMiddleware} 的
 * 调用壳，故这里只验证<b>属于薄壳的两件事</b>——把 {@link KnowledgeRetrievalService#retrieve} 正确接成
 * starter 的召回来源（agentCode 构建期绑定、query 原样透传），以及壳确实沿用了父类的注入行为。
 * 瞬态注入、每轮一次、boundedElastic 调度、隔离包裹、失败不打断等语义在 starter 的
 * {@code KnowledgeInjectionMiddlewareTest} 覆盖，不在此重复。
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

    private ReasoningInput inputOf(String text) {
        return new ReasoningInput(List.of(Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build()), List.of(), null);
    }

    /** 壳必须把构建期绑定的 agentCode 与本轮提问原样交给查表服务，接错了整条 RAG 链路会静默不召回。 */
    @Test
    void shouldWireRetrievalServiceAsProvider() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(BLOCK);
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(),
            inputOf("公积金怎么提取"), input -> {
                passed.set(input);
                return Flux.<AgentEvent>empty();
            }).blockLast();

        verify(retrievalService).retrieve(AGENT_CODE, "公积金怎么提取");
        // 壳确实继承了父类的注入行为：原消息不动，末尾多一条隔离包裹的合成消息
        List<Msg> sent = passed.get().messages();
        assertEquals(2, sent.size());
        assertEquals("公积金怎么提取", sent.get(0).getTextContent());
        assertTrue(sent.get(1).getTextContent().contains(BLOCK));
        assertEquals(Boolean.TRUE, sent.get(1).getMetadata().get(Msg.METADATA_SYNTHETIC));
    }

    /** 查表服务返回 null（未绑知识库/未命中/降级）时原样透传，壳不得自己造空块。 */
    @Test
    void shouldPassThrough_whenRetrievalServiceReturnsNull() {
        when(retrievalService.retrieve(anyString(), anyString())).thenReturn(null);
        ReasoningInput input = inputOf("问题");
        AtomicReference<ReasoningInput> passed = new AtomicReference<>();

        middleware.onReasoning(null, RuntimeContext.builder().sessionId("s1").build(), input, in -> {
            passed.set(in);
            return Flux.<AgentEvent>empty();
        }).blockLast();

        assertSame(input, passed.get());
    }
}
