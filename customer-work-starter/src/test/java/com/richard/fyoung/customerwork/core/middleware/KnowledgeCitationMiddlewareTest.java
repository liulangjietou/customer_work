package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用采集中间件：从工具结果事件流里认领本轮召回的知识来源。
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeCitationMiddlewareTest {

    private final KnowledgeCitationMiddleware middleware = new KnowledgeCitationMiddleware();

    private static final ActingInput EMPTY_INPUT = new ActingInput(List.of());

    @Test
    @DisplayName("从工具结果里采集到引用，并写进终止信封")
    void collectsCitationsFromToolResult() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String output = "检索到 1 个结果：\n"
            + new KnowledgeCitation("售后FAQ", "doc-refund", "10237", 0.87).marker() + "\n"
            + "七天无理由退货适用于未拆封商品。";

        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", output),
            new ToolResultEndEvent("r", "call-1", "retrieve_knowledge", ToolResultState.SUCCESS)));

        List<KnowledgeCitation> citations = capture.citations();
        assertEquals(1, citations.size());
        assertEquals("售后FAQ", citations.get(0).knowledgeBase());
        assertEquals("10237", citations.get(0).chunkId());
        assertEquals(citations, capture.envelope("MSG-1", "回复", "trace-1").citations(),
            "采到的引用必须真的出现在终止信封里，否则前端永远拿不到");
    }

    /**
     * 分片是这条链路最容易静默失效的地方：标记行被切在两个 delta 之间时，
     * 逐片解析会稳定地漏掉刚好被切开的那几条，而且不报任何错。
     */
    @Test
    @DisplayName("标记行被切成多个分片时仍能完整解析")
    void accumulatesDeltasBeforeParsing() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String marker = new KnowledgeCitation("物流政策", "doc-ship", "88", 0.6).marker();
        int cut = marker.length() / 2;

        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", marker.substring(0, cut)),
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", marker.substring(cut)),
            new ToolResultEndEvent("r", "call-1", "retrieve_knowledge", ToolResultState.SUCCESS)));

        assertEquals(List.of("88"), capture.citations().stream()
            .map(KnowledgeCitation::chunkId).toList());
    }

    @Test
    @DisplayName("同一分片被多次召回只回传一条")
    void deduplicatesByChunkId() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String marker = new KnowledgeCitation("售后FAQ", "doc-a", "5", 0.9).marker();

        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", marker),
            new ToolResultEndEvent("r", "call-1", "retrieve_knowledge", ToolResultState.SUCCESS)));
        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-2", "retrieve_knowledge", marker),
            new ToolResultEndEvent("r", "call-2", "retrieve_knowledge", ToolResultState.SUCCESS)));

        assertEquals(1, capture.citations().size());
    }

    @Test
    @DisplayName("并发的两次工具调用各自解析，不会把文本串在一起")
    void separatesByToolCallId() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String first = new KnowledgeCitation("售后FAQ", "doc-a", "1", 0.9).marker();
        String second = new KnowledgeCitation("物流政策", "doc-b", "2", 0.5).marker();
        int cut = first.length() / 2;

        // 两次调用的分片交错到达：按 toolCallId 分桶才不会把两段标记接成一段乱码
        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", first.substring(0, cut)),
            new ToolResultTextDeltaEvent("r", "call-2", "retrieve_knowledge", second),
            new ToolResultTextDeltaEvent("r", "call-1", "retrieve_knowledge", first.substring(cut)),
            new ToolResultEndEvent("r", "call-2", "retrieve_knowledge", ToolResultState.SUCCESS),
            new ToolResultEndEvent("r", "call-1", "retrieve_knowledge", ToolResultState.SUCCESS)));

        assertEquals(List.of("2", "1"), capture.citations().stream()
            .map(KnowledgeCitation::chunkId).toList());
    }

    @Test
    @DisplayName("普通业务工具的结果不产生引用")
    void ignoresUnrelatedToolOutput() {
        ChatTerminalCapture capture = new ChatTerminalCapture();

        run(capture, Flux.just(
            new ToolResultTextDeltaEvent("r", "call-1", "query_order", "订单 SO-1 已发货"),
            new ToolResultEndEvent("r", "call-1", "query_order", ToolResultState.SUCCESS)));

        assertTrue(capture.citations().isEmpty());
    }

    /**
     * 工作台、调度任务等调用方没有终止采集上下文，必须原样透传——
     * 这类"顺带把别人打挂"的回归正是本项目反复出现的形状。
     */
    @Test
    @DisplayName("没有采集上下文时完全透传")
    void passesThroughWithoutCapture() {
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        List<AgentEvent> events = middleware.onActing(null, null, EMPTY_INPUT, input -> {
            nextCalled.set(true);
            return Flux.just(new ToolResultEndEvent("r", "call-1", "any", ToolResultState.SUCCESS));
        }).collectList().block();

        assertTrue(nextCalled.get(), "下游中间件必须照常被调用");
        assertEquals(1, events.size(), "事件流不得被改写或吞掉");
    }

    private void run(ChatTerminalCapture capture, Flux<AgentEvent> downstream) {
        middleware.onActing(null, null, EMPTY_INPUT, input -> downstream)
            .contextWrite(context -> ChatTerminalCaptureContext.withCapture(context, capture))
            .blockLast();
    }
}
