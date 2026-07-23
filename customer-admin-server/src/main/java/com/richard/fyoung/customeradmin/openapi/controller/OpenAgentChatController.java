package com.richard.fyoung.customeradmin.openapi.controller;

import com.richard.fyoung.customeradmin.openapi.dto.OpenChatRequest;
import com.richard.fyoung.customeradmin.openapi.service.OpenChannelService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 开放 API - 智能体对话（SSE）：供 customer-channel 模块把渠道消息转发给绑定的工作区智能体。
 *
 * <p>复用 {@link ChatService#chatStream(String, String, String)}，只取可见回答正文（{@link ChatNodeKind#ANSWER}）
 * 的增量，以 {@code event:message} 发文本增量；正常结束发 {@code event:done} data {@code [DONE]}；
 * 异常发 {@code event:error} data 错误信息后结束。**不下发思考轨迹/工具节点等其余事件**（渠道侧只要正文）。</p>
 *
 * <p>授权：agentCode 必须有启用的渠道机器人绑定，否则在流内发 error 事件拒绝
 * （见 {@link OpenChannelService#requireAgentBound}）。鉴权（token）由 {@code OpenApiAuthInterceptor} 前置完成。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/open/agents")
public class OpenAgentChatController {

    private static final String EVENT_MESSAGE = "message";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    private static final String DONE_PAYLOAD = "[DONE]";

    private static final Logger log = LoggerFactory.getLogger(OpenAgentChatController.class);

    private final ChatService chatService;
    private final OpenChannelService openChannelService;

    public OpenAgentChatController(ChatService chatService, OpenChannelService openChannelService) {
        this.chatService = chatService;
        this.openChannelService = openChannelService;
    }

    @PostMapping(value = "/{agentCode}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable String agentCode,
                                              @Valid @RequestBody OpenChatRequest request) {
        // defer 到订阅时执行：授权校验的同步异常也能被 onErrorResume 兜成 error 事件（而非直接抛 JSON 错误体）
        Flux<ServerSentEvent<String>> body = Flux.defer(() -> {
                openChannelService.requireAgentBound(agentCode);
                return chatService.chatStream(agentCode, request.sessionId(), request.message());
            })
            .filter(chunk -> chunk.kind() == ChatNodeKind.ANSWER)
            .map(chunk -> ServerSentEvent.<String>builder().event(EVENT_MESSAGE).data(chunk.text()).build());

        return body
            .concatWithValues(ServerSentEvent.<String>builder().event(EVENT_DONE).data(DONE_PAYLOAD).build())
            .onErrorResume(e -> {
                log.error("open api agent chat failed, code={}, agentCode={}", "OPEN-API-CHAT-FAIL", agentCode, e);
                return Flux.just(ServerSentEvent.<String>builder().event(EVENT_ERROR).data(errorMessage(e)).build());
            });
    }

    private String errorMessage(Throwable e) {
        return e.getMessage() == null ? "chat failed" : e.getMessage();
    }
}
