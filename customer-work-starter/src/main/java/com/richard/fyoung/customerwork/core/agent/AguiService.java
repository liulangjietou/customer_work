package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import com.richard.fyoung.customerwork.core.service.ChatTraceContext;
import com.richard.fyoung.customerwork.core.service.ChatTurnFinalizer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.richard.fyoung.customerwork.infra.config.properties.ProtocolProperties;

/**
 * AG-UI 协议服务（对应「交互协议 · AG-UI」）。
 *
 * <p>把会话 Agent 适配为标准 AG-UI 事件流：前端按 AG-UI 协议发起 {@link RunAgentInput}，
 * 服务端经 {@link AguiAgentAdapter} 产出类型化 {@link io.agentscope.core.agui.event.AguiEvent}
 * （消息开始/增量/结束、工具调用、状态变更等），再由 {@link AguiEventEncoder} 编码为 SSE 文本下发。
 * 这样任意兼容 AG-UI 的前端均可直接对接，无需自定义协议。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AguiService {

    private static final Logger log = LoggerFactory.getLogger(AguiService.class);

    private final CustomerServiceAgentFactory agentFactory;
    private final CustomerWorkProperties properties;
    private final ChatTurnFinalizer finalizer;
    private final AguiMessageConverter messageConverter = new AguiMessageConverter();
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public AguiService(CustomerServiceAgentFactory agentFactory, CustomerWorkProperties properties,
                       ChatTurnFinalizer finalizer) {
        this.agentFactory = agentFactory;
        this.properties = properties;
        this.finalizer = finalizer;
    }

    /** 以 AG-UI 协议运行一轮对话，返回编码后的 SSE 事件文本流。 */
    public Flux<String> run(String sessionId, String userText) {
        return Flux.deferContextual(contextView -> {
            ChatTerminalCapture capture = new ChatTerminalCapture();
            String traceId = ChatTraceContext.resolveOrCreate(contextView);
            Map<String, StringBuilder> messageBuffers = new HashMap<>();
            AtomicReference<String> latestMessageId = new AtomicReference<>();
            return Flux.using(
                () -> agentFactory.createAgent(sessionId),
                agent -> {
                    AguiAgentAdapter adapter = new AguiAgentAdapter(agent, adapterConfig());
                    return adapter.run(buildInput(sessionId, userText))
                        .concatMap(event -> finalizeEvent(sessionId, event, capture, traceId,
                            messageBuffers, latestMessageId))
                        .contextWrite(context -> withTurnContext(context, capture, traceId))
                        .map(encoder::encode);
                },
                agent -> AgentResourceCloser.closeQuietly(agent, "agui:" + sessionId));
        }).doOnError(error -> log.error("[AG-UI] run failed, code={}, sessionId={}",
            "AGUI-RUN-FAIL", sessionId, error));
    }

    /** 仅在标准 RUN_FINISHED 前执行持久化，并用统一终止信封填充其 result。 */
    Mono<AguiEvent> finalizeEvent(String sessionId, AguiEvent event,
                                  ChatTerminalCapture capture, String traceId,
                                  Map<String, StringBuilder> messageBuffers,
                                  AtomicReference<String> latestMessageId) {
        if (event instanceof AguiEvent.TextMessageContent content) {
            messageBuffers.computeIfAbsent(content.messageId(), ignored -> new StringBuilder())
                .append(content.delta());
            latestMessageId.set(content.messageId());
            return Mono.just(event);
        }
        if (event instanceof AguiEvent.TextMessageEnd end) {
            latestMessageId.set(end.messageId());
            return Mono.just(event);
        }
        if (!(event instanceof AguiEvent.RunFinished finished)) {
            return Mono.just(event);
        }
        String reply = messageText(messageBuffers, latestMessageId.get());
        return finalizer.complete(sessionId, null, reply, capture, traceId)
            .map(completion -> new AguiEvent.RunFinished(
                finished.threadId(), finished.runId(), completion.terminal(), finished.outcome()));
    }

    private String messageText(Map<String, StringBuilder> messageBuffers, String messageId) {
        StringBuilder text = messageId == null ? null : messageBuffers.get(messageId);
        return text == null ? "" : text.toString();
    }

    private Context withTurnContext(Context context, ChatTerminalCapture capture, String traceId) {
        Context result = ChatTerminalCaptureContext.withCapture(context, capture);
        return ChatTraceContext.withTraceId(result, traceId);
    }

    /** 构造 AG-UI 运行输入（抽出以便单测）。 */
    RunAgentInput buildInput(String sessionId, String userText) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(userText).build()).build();
        return new RunAgentInput(
            sessionId,                                   // threadId
            "run-" + UUID.randomUUID(),                  // runId
            messageConverter.toAguiMessageList(List.of(userMsg)),
            List.of(),                                   // tools（由服务端 toolkit 提供）
            List.of(),                                   // context
            Map.of(),                                    // state
            Map.of());                                   // forwardedProps
    }

    private AguiAdapterConfig adapterConfig() {
        ProtocolProperties.Agui cfg = properties.getProtocol().getAgui();
        return AguiAdapterConfig.builder()
            .enableReasoning(cfg.isEnableReasoning())
            .emitToolCallArgs(cfg.isEmitToolCallArgs())
            .emitStateEvents(true)
            .build();
    }
}
