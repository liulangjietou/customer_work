package com.richard.fyoung.customerwork.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetter;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterHandler;
import org.springframework.stereotype.Component;

/** 主动通知死信重投处理器：复用原通道，并保留同一个下游幂等键。 */
@Component
public class ProactiveNotificationDeadLetterHandler implements DeadLetterHandler {

    public static final String TYPE = "proactive-notification";

    private final NotificationChannel channel;
    private final ObjectMapper objectMapper;

    public ProactiveNotificationDeadLetterHandler(NotificationChannel channel, ObjectMapper objectMapper) {
        this.channel = channel;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void retry(DeadLetter letter) throws Exception {
        NotificationDeadLetterPayload payload = objectMapper.readValue(
            letter.getPayload(), NotificationDeadLetterPayload.class);
        channel.push(payload.operationId(), payload.target(), payload.message()).block();
    }
}
