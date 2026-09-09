package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把本轮召回的知识来源采集进终止信封，让用户看得到答案出处、运营查得到是哪条知识。
 *
 * <h3>为什么采在这里</h3>
 * <p>检索侧（{@code ManagedKnowledge}）看似是最自然的采集点，但它接不上本轮请求：
 * C 端走 {@code RAGMode.AGENTIC}，框架的 {@code KnowledgeRetrievalTools} 用
 * {@code Mono.block()} 调检索——新订阅、空 Context，读不到 {@link ChatTerminalCapture}；
 * 而 {@code Knowledge} 实例是全局共享单例（{@code KnowledgeProvider} 缓存了它），
 * 也挂不住请求态。于是检索侧只负责把来源写成正文首行标记，认领工作放到这里。</p>
 *
 * <h3>为什么落 onActing 而不是 onReasoning</h3>
 * <p>{@code onReasoning} 拿到的 {@code messages()} 是整个会话上下文，里面还躺着<b>前几轮</b>
 * 的工具结果，照单解析会把上一个问题的引用挂到这一轮的回答上——用户看到一堆与当前答案
 * 无关的出处，比没有出处更误导。{@code onActing} 的事件流只含本轮真实发生的工具调用。
 * 本中间件只读事件、不改写，因此不受"改事件流改不了模型上下文"那条限制影响。</p>
 *
 * <p>工具结果文本按 {@code toolCallId} 累积到 {@link ToolResultEndEvent} 再解析：
 * {@link ToolResultTextDeltaEvent} 是<b>增量</b>，标记行可能被切在两个分片之间，
 * 逐片解析会稳定地漏掉刚好被切开的那几条。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeCitationMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(contextView -> {
            ChatTerminalCapture capture = ChatTerminalCaptureContext.get(contextView);
            if (capture == null) {
                // 工作台、调度任务等没有终止采集上下文的调用方完全透传
                return next.apply(input);
            }
            // 每次订阅一份累积器：同一会话的并发请求各走各的，不会互相串入引用
            Map<String, StringBuilder> pending = new HashMap<>();
            return next.apply(input).doOnNext(event -> collect(event, pending, capture));
        });
    }

    private void collect(AgentEvent event, Map<String, StringBuilder> pending, ChatTerminalCapture capture) {
        if (event instanceof ToolResultTextDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                pending.computeIfAbsent(keyOf(delta.getToolCallId()), k -> new StringBuilder())
                    .append(delta.getDelta());
            }
            return;
        }
        if (event instanceof ToolResultEndEvent end) {
            StringBuilder text = pending.remove(keyOf(end.getToolCallId()));
            if (text == null) {
                return;
            }
            List<KnowledgeCitation> found = KnowledgeCitation.parseAll(text.toString());
            capture.acceptCitations(found);
        }
    }

    /** 工具调用 id 缺失时归到同一个桶：宁可把两次调用的文本连在一起，也不要整段丢弃。 */
    private String keyOf(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank() ? "" : toolCallId;
    }

    /** 顺序契约见 {@link MiddlewareOrders}：需在间接注入护栏之前看到工具结果原文。 */
    @Override
    public int order() {
        return MiddlewareOrders.KNOWLEDGE_CITATION;
    }
}
