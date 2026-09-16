package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
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

    /** 冻结一次答复信息并与正文同次保存，完成事件复用同一快照，避免迟到回调改变实时结果。 */
    public Mono<ChatTurnCompletion> complete(String sessionId, String ticketId, String reply,
                                             ChatTerminalCapture capture, String traceId) {
        return Mono.fromCallable(() -> {
            var evidence = capture.answerEvidence(reply);
            ChatMessage message = chatLogService.appendAnswer(sessionId, ticketId, reply, evidence);
            return new ChatTurnCompletion(message,
                new ChatTerminalEnvelope(message.messageId(), evidence.finishReason(), capture.usage(), traceId,
                    evidence.citations(), evidence.taskPlan()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
