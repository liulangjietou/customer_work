package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话收尾的唯一实现：先持久化助手消息，再构造包含真实 messageId 的终止信封。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ChatTurnFinalizer {

    private final ChatLogService chatLogService;

    public ChatTurnFinalizer(ChatLogService chatLogService) {
        this.chatLogService = chatLogService;
    }

    public Mono<ChatTurnCompletion> complete(String sessionId, String ticketId, String reply,
                                             ChatTerminalCapture capture, String traceId) {
        return Mono.fromCallable(() -> {
            ChatMessage message = chatLogService.append(
                sessionId, ticketId, TicketActorType.BOT, null, reply);
            return new ChatTurnCompletion(message,
                capture.envelope(message.messageId(), message.content(), traceId));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
