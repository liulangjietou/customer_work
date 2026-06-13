package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final AguiMessageConverter messageConverter = new AguiMessageConverter();
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public AguiService(CustomerServiceAgentFactory agentFactory, CustomerWorkProperties properties) {
        this.agentFactory = agentFactory;
        this.properties = properties;
    }

    /** 以 AG-UI 协议运行一轮对话，返回编码后的 SSE 事件文本流。 */
    public Flux<String> run(String sessionId, String userText) {
        ReActAgent agent = agentFactory.createAgent(sessionId);
        AguiAgentAdapter adapter = new AguiAgentAdapter(agent, adapterConfig());
        return adapter.run(buildInput(sessionId, userText))
            .map(encoder::encode)
            .doOnError(e -> log.error("[AG-UI] 运行失败: {}", e.getMessage()));
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
        CustomerWorkProperties.Protocol.Agui cfg = properties.getProtocol().getAgui();
        return AguiAdapterConfig.builder()
            .enableReasoning(cfg.isEnableReasoning())
            .emitToolCallArgs(cfg.isEmitToolCallArgs())
            .emitStateEvents(true)
            .build();
    }
}
