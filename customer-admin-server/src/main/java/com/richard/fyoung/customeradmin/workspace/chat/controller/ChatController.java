package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 智能体工作区对话（SSE）。权限点复用菜单聚合已经在用的 {@code workspace}——能看见工作区菜单
 * 节点的角色即可对话，不额外新增按智能体粒度的权限点（动态节点天然继承父节点可见性，
 * 见批次三 {@code MenuAggregationService} 的设计取舍）。
 *
 * <p>本模块是 Spring MVC（非 WebFlux），但 {@code reactor-core} 经 starter 传递可用，Spring MVC 6.x
 * 原生支持控制器方法返回 {@link Flux}&lt;{@link ServerSentEvent}&gt; 做流式响应（框架内置
 * {@code ReactiveTypeHandler}），无需手动桥接 {@code SseEmitter}，与
 * {@code CustomerServiceController#chatStream} 同一套写法。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/{agentCode}/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @SaCheckPermission("workspace")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String agentCode, @Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(agentCode, request.sessionId(), request.message())
            .map(chunk -> ServerSentEvent.<String>builder().event("message").data(chunk).build())
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
    }
}
