package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.agent.AguiService;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.dto.ChatRequest;
import com.richard.fyoung.customerwork.core.dto.ChatResponse;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.observability.MdcContextLifter;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "智能客服", description = "对话 / 流式 / 结构化意图 / 多 Agent 协作 / AG-UI / 会话管理")
public class CustomerServiceController {

    private final CustomerServiceService service;
    private final ChatTurnService chatTurnService;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final AguiService aguiService;

    public CustomerServiceController(CustomerServiceService service,
                                     ChatTurnService chatTurnService,
                                     MultiAgentOrchestrator multiAgentOrchestrator,
                                     AguiService aguiService) {
        this.service = service;
        this.chatTurnService = chatTurnService;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.aguiService = aguiService;
    }

    /**
     * 同步对话：返回完整回复。适合内部联调与简单接入。
     */
    @Operation(summary = "同步对话", description = "返回完整回复")
    @PostMapping("/chat")
    public Mono<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return chatTurnService.chat(sessionId, request.message())
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, sessionId));
    }

    /**
     * 流式对话（SSE）：逐增量片段推送，对应⑤ streamEvents 的逐 token 渲染体验。
     */
    @Operation(summary = "流式对话(SSE)", description = "逐增量片段推送")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return chatTurnService.stream(sessionId, request.message(), null)
            .map(event -> {
                if (event instanceof ChatTurnEvent.Delta delta) {
                    return ServerSentEvent.builder((Object) delta.content())
                        .event("message")
                        .build();
                }
                ChatTurnEvent.Completed completed = (ChatTurnEvent.Completed) event;
                return ServerSentEvent.builder((Object) completed.completion().terminal())
                    .event("done")
                    .build();
            })
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, sessionId));
    }

    /**
     * 结构化意图识别：返回强类型 {@link IntentResult}（对应⑤上游路由可直接消费的结构化结果）。
     */
    @Operation(summary = "结构化意图识别", description = "返回强类型 IntentResult")
    @PostMapping("/intent")
    public Mono<IntentResult> classifyIntent(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return service.classifyIntent(sessionId, request.message())
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, sessionId));
    }

    /**
     * 安全中断：终止指定会话正在执行的 Agent，保留上下文以便后续恢复。
     */
    @Operation(summary = "安全中断会话", description = "终止正在执行的 Agent，保留上下文")
    @PostMapping("/session/{sessionId}/interrupt")
    public Mono<String> interrupt(@PathVariable String sessionId) {
        boolean interrupted = service.interrupt(sessionId);
        return Mono.just(interrupted
            ? "会话 " + sessionId + " 已发出中断"
            : "会话 " + sessionId + " 无活跃任务");
    }

    /**
     * 多 Agent 协作咨询：把问题分发给订单 / 售后 / 知识库多个专家 Agent 并聚合结论
     * （fanout 模式）或流水细化（sequential 模式）。
     *
     * <p>会话 ID 与其余对话入口同口径解析：它决定框架按 {@code (userId, sessionId)}
     * 缓存的对话状态落在哪个槽位，缺省时按匿名会话隔离，绝不可让多个用户共用一个值。</p>
     */
    @Operation(summary = "多 Agent 协作咨询", description = "订单/售后/知识库专家并行或串行协作")
    @PostMapping("/consult")
    public Mono<String> consult(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return multiAgentOrchestrator.consult(sessionId, request.message())
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, sessionId));
    }

    /**
     * AG-UI 协议对话（SSE）：输出标准 Agent-UI 事件流，兼容 AG-UI 的前端可直接对接。
     */
    @Operation(summary = "AG-UI 协议对话(SSE)", description = "标准 Agent-UI 事件流")
    @PostMapping(value = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> agui(@Valid @RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        return aguiService.run(sessionId, request.message())
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, sessionId));
    }

    /**
     * 结束会话。
     */
    @Operation(summary = "结束会话", description = "清理会话与持久化状态")
    @DeleteMapping("/session/{sessionId}")
    public Mono<String> endSession(@PathVariable String sessionId) {
        service.endSession(sessionId);
        return Mono.just("会话 " + sessionId + " 已结束");
    }

    /** 健康检查。 */
    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("OK");
    }

    /** 匿名或空会话 ID 时生成一个稳定的服务端会话 ID。 */
    private String resolveSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : "anonymous-" + UUID.randomUUID();
    }

}
