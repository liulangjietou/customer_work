package com.richard.fyoung.customeradmin.openapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.openapi.dto.OpenChatRequest;
import com.richard.fyoung.customeradmin.openapi.service.OpenChannelService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
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
 * <p>复用 {@link ChatService#chatStreamForChannel(String, String, String, String)}，只取可见回答正文（{@link ChatNodeKind#ANSWER}）
 * 的增量，以 {@code event:message} 发文本增量；正常结束发 {@code event:done} data {@code [DONE]}；
 * 异常发 {@code event:error} data 错误信息后结束。**不下发思考轨迹/工具节点等其余事件**（渠道侧只要正文）。</p>
 *
 * <p><b>SSE data 契约（换行安全）</b>：{@code message}/{@code error} 事件的 data 一律为
 * <b>JSON 字符串字面量</b>（{@link ObjectMapper#writeValueAsString(Object)} 编码后的带引号转义串，
 * 如 {@code "第一行\n第二行"}）。原因：SSE 协议会剥掉每个 data 行末尾的换行，模型分片以 {@code \n} 结尾时
 * 换行会丢失（钉钉/微信表格首尾行相接）。JSON 编码把换行转义进字面量、不再裸露在 SSE 帧里，消费端
 * （customer-channel {@code AdminOpenApiClient}）用 {@code readValue(String.class)} 解码即还原。
 * {@code done} 事件 data 仍为固定的 {@code [DONE]} 结束标记（非 JSON），消费端据此判终止。</p>
 *
 * <p>授权：agentCode + channelType + appKey 必须精确命中同一条启用的渠道机器人绑定，否则在流内发 error 事件拒绝
 * （见 {@link OpenChannelService#requireAgentBound}）。鉴权（token）由 {@code OpenApiAuthInterceptor} 前置完成。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/open/agents")
public class OpenAgentChatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAgentChatController.class);

    /** SSE data 的 JSON 字符串编码器（窄用途，不依赖容器注入）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatService chatService;
    private final OpenChannelService openChannelService;

    public OpenAgentChatController(ChatService chatService, OpenChannelService openChannelService) {
        this.chatService = chatService;
        this.openChannelService = openChannelService;
    }

    @PostMapping(value = "/{agentCode}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable String agentCode,
                                              @Valid @RequestBody OpenChatRequest request) {
        // MVC 拦截器在线程释放前先捕获可信凭据解析出的租户，订阅和异步模型链路再从 Reactor Context 恢复。
        String tenantId = TenantContext.get();
        // defer 到订阅时执行：授权校验的同步异常也能被 onErrorResume 兜成 error 事件（而非直接抛 JSON 错误体）
        Flux<ServerSentEvent<String>> body = Flux.defer(() -> {
                openChannelService.requireAgentBound(agentCode, request.channelType(), request.appKey());
                return chatService.chatStreamForChannel(
                    agentCode, request.sessionId(), request.message(), request.channelType());
            })
            .filter(chunk -> chunk.kind() == ChatNodeKind.ANSWER)
            .map(chunk -> ServerSentEvent.<String>builder().event(OpenApiProtocol.SSE_EVENT_MESSAGE).data(jsonString(chunk.text())).build());

        Flux<ServerSentEvent<String>> result = body
            .concatWithValues(ServerSentEvent.<String>builder().event(OpenApiProtocol.SSE_EVENT_DONE).data(OpenApiProtocol.SSE_DONE_MARKER).build())
            .onErrorResume(e -> {
                log.error("open api agent chat failed, code={}, agentCode={}", "OPEN-API-CHAT-FAIL", agentCode, e);
                return Flux.just(ServerSentEvent.<String>builder()
                    .event(OpenApiProtocol.SSE_EVENT_ERROR).data(jsonString(errorMessage(e))).build());
            });
        return tenantId == null ? result
            : result.contextWrite(context -> context.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }

    private String errorMessage(Throwable e) {
        return e.getMessage() == null ? "chat failed" : e.getMessage();
    }

    /**
     * 把文本编码成 JSON 字符串字面量（带引号转义），使换行不裸露在 SSE 帧里。
     * 序列化异常（几乎不可能，String 恒可序列化）时回退空 JSON 串，保证流不中断。
     */
    private String jsonString(String text) {
        try {
            return JSON.writeValueAsString(text == null ? "" : text);
        } catch (Exception e) {
            log.error("open api sse data json encode failed, code={}", "OPEN-API-SSE-ENCODE-FAIL", e);
            return "\"\"";
        }
    }
}
