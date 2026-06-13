package com.example.customerwork.controller;

import com.example.customerwork.dto.ChatRequest;
import com.example.customerwork.service.CustomerServiceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 客服对话接口（对应流程图①"接入层"的应用入口 与 ⑤"回复用户"）。
 *
 * <p>这里是 Agent 应用自身暴露的 HTTP 入口。图中①的 Higress AI 网关、鉴权限流、A/B 分流、
 * RocketMQ 异步削峰等，是部署在本应用<b>前面</b>的基础设施，不在应用代码内实现——
 * 生产中本应用作为上游被网关路由即可。</p>
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
    public Mono<String> chat(@RequestBody ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
            ? "anonymous" : request.sessionId();
        return service.chat(sessionId, request.message());
    }

    /**
     * 流式对话（SSE）：对应⑤ streamEvents() 的逐 Token 渲染体验。
     *
     * <p>说明：此处用 WebFlux 的 SSE 通道演示"流式返回"的对接形态。要做到真正的"逐 Token 推送"，
     * 需在 Agent 侧通过 ReasoningChunkEvent Hook 把增量文本发布到一个 Sink，再由本接口订阅转发。
     * 为保持示例聚焦核心链路，这里先以"整体回复包成单事件流"的简化形式给出对接骨架。</p>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<String> chatStream(@RequestBody ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
            ? "anonymous" : request.sessionId();
        return service.chat(sessionId, request.message());
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
}
