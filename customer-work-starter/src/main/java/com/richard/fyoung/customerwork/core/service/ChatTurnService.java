package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.dto.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 同步、SSE 与 WebSocket 共用的对话用例编排：生成正文、落库、发出终止信封。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatTurnService {

    private final CustomerServiceService customerServiceService;
    private final ChatTurnFinalizer finalizer;

    public ChatTurnService(CustomerServiceService customerServiceService, ChatTurnFinalizer finalizer) {
        this.customerServiceService = customerServiceService;
        this.finalizer = finalizer;
    }

    /** 同步响应复用流式核心，保证两种协议的模型、持久化与终止字段完全一致。 */
    public Mono<ChatResponse> chat(String sessionId, String userText) {
        return stream(sessionId, userText, null)
            .ofType(ChatTurnEvent.Completed.class)
            .single()
            .map(completed -> {
                ChatTurnCompletion result = completed.completion();
                return ChatResponse.from(sessionId, result.message().content(), result.terminal());
            });
    }

    public Flux<ChatTurnEvent> stream(String sessionId, String userText, String ticketId) {
        return Flux.deferContextual(contextView -> {
            ChatTerminalCapture capture = new ChatTerminalCapture();
            String traceId = ChatTraceContext.resolveOrCreate(contextView);
            StringBuilder reply = new StringBuilder();

            Flux<ChatTurnEvent> deltas = customerServiceService.chatStream(sessionId, userText)
                .doOnNext(reply::append)
                .map(ChatTurnEvent.Delta::new);
            Mono<ChatTurnEvent> completed = Mono.defer(() ->
                    finalizer.complete(sessionId, ticketId, reply.toString(), capture, traceId))
                .map(ChatTurnEvent.Completed::new);

            return deltas.concatWith(completed)
                .contextWrite(context -> withTurnContext(context, capture, traceId));
        });
    }

    private Context withTurnContext(Context context, ChatTerminalCapture capture, String traceId) {
        Context result = ChatTerminalCaptureContext.withCapture(context, capture);
        return ChatTraceContext.withTraceId(result, traceId);
    }
}
