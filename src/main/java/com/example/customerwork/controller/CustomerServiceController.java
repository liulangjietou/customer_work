package com.example.customerwork.controller;

import com.example.customerwork.dto.ChatRequest;
import com.example.customerwork.dto.ChatResponse;
import com.example.customerwork.dto.IntentResult;
import com.example.customerwork.service.CustomerServiceService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 客服对话接口（对应深度解析一文①"接入层"的应用入口 与 ⑤"回复用户"）。
 *
 * <p>这里是 Agent 应用自身暴露的 HTTP 入口。Higress AI 网关、鉴权限流、A/B 分流、
 * RocketMQ 异步削峰等，是部署在本应用<b>前面</b>的基础设施，不在应用代码内实现——
 * 生产中本应用作为上游被网关路由即可。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerServiceController {

    private final CustomerServiceService service;

    public CustomerServiceController(CustomerServiceService service) {
        this.service = service;
    }

    /**
     * 同步对话：返回完整回复。适合内部联调与简单接入。
     */
    @PostMapping("/chat")
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return service.chat(sessionId, request.message())
            .map(reply -> new ChatResponse(sessionId, reply));
    }

    /**
     * 流式对话（SSE）：逐增量片段推送，对应⑤ streamEvents 的逐 token 渲染体验。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return service.chatStream(sessionId, request.message())
            .map(chunk -> ServerSentEvent.<String>builder()
                .event("message")
                .data(chunk)
                .build())
            .concatWithValues(ServerSentEvent.<String>builder()
                .event("done")
                .data("[DONE]")
                .build());
    }

    /**
     * 结构化意图识别：返回强类型 {@link IntentResult}（对应⑤上游路由可直接消费的结构化结果）。
     */
    @PostMapping("/intent")
    public Mono<IntentResult> classifyIntent(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return service.classifyIntent(sessionId, request.message());
    }

    /**
     * 安全中断：终止指定会话正在执行的 Agent，保留上下文以便后续恢复。
     */
    @PostMapping("/session/{sessionId}/interrupt")
    public Mono<String> interrupt(@PathVariable String sessionId) {
        boolean interrupted = service.interrupt(sessionId);
        return Mono.just(interrupted
            ? "会话 " + sessionId + " 已发出中断"
            : "会话 " + sessionId + " 无活跃任务");
    }

    /**
     * 结束会话。
     */
    @DeleteMapping("/session/{sessionId}")
    public Mono<String> endSession(@PathVariable String sessionId) {
        service.endSession(sessionId);
        return Mono.just("会话 " + sessionId + " 已结束");
    }

    /** 健康检查。 */
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("OK");
    }

    /** 匿名或空会话 ID 时生成一个稳定的服务端会话 ID。 */
    private String resolveSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : "anonymous-" + UUID.randomUUID();
    }
}
