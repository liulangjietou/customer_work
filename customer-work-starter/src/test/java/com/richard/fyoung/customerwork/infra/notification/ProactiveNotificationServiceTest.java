package com.richard.fyoung.customerwork.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterService;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStatus;
import com.richard.fyoung.customerwork.capability.deadletter.InMemoryDeadLetterStore;
import com.richard.fyoung.customerwork.infra.config.properties.DeadLetterProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主动服务单测（离线确定性）：文案组装正确、推送委托给注入的通道。
 * @author owlzhangfq@gmail.com
 */
class ProactiveNotificationServiceTest {

    @Test
    void notifyOrderStatus_shouldPushComposedMessage() {
        AtomicReference<String> target = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        NotificationChannel capturing = (t, m) -> {
            target.set(t);
            message.set(m);
            return Mono.empty();
        };
        ProactiveNotificationService svc = new ProactiveNotificationService(capturing);

        svc.notifyOrderStatus("20260613001", "已发货", "user-123").block();

        assertEquals("user-123", target.get());
        assertTrue(message.get().contains("20260613001") && message.get().contains("已发货"));
    }

    @Test
    void sendSatisfactionSurvey_shouldPushSurveyMessage() {
        AtomicReference<String> message = new AtomicReference<>();
        ProactiveNotificationService svc = new ProactiveNotificationService((t, m) -> {
            message.set(m);
            return Mono.empty();
        });

        svc.sendSatisfactionSurvey("20260613001", "user-123").block();

        assertTrue(message.get().contains("满意度") && message.get().contains("20260613001"));
    }

    @Test
    void loggingChannel_shouldNotThrow() {
        new LoggingNotificationChannel().push("u", "hi").block();
    }

    @Test
    void failedPush_shouldEnterDeadLetterAndSucceedAfterChannelRecovery() throws Exception {
        AtomicReference<Boolean> available = new AtomicReference<>(false);
        AtomicReference<String> firstOperationId = new AtomicReference<>();
        NotificationChannel channel = new NotificationChannel() {
            @Override
            public Mono<Void> push(String target, String message) {
                return Mono.error(new UnsupportedOperationException());
            }

            @Override
            public Mono<Void> push(String operationId, String target, String message) {
                firstOperationId.compareAndSet(null, operationId);
                return available.get() ? Mono.empty() : Mono.error(new IllegalStateException("channel down"));
            }
        };
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore();
        DeadLetterProperties properties = new DeadLetterProperties();
        properties.setBaseBackoffMs(0L);
        ObjectMapper objectMapper = new ObjectMapper();
        ProactiveNotificationDeadLetterHandler handler =
            new ProactiveNotificationDeadLetterHandler(channel, objectMapper);
        DeadLetterService deadLetterService = new DeadLetterService(store, properties, List.of(handler));
        ProactiveNotificationService service =
            new ProactiveNotificationService(channel, deadLetterService, objectMapper);

        service.notifyOrderStatus("O-1", "已发货", "user-1").block();
        assertEquals(1, deadLetterService.count(DeadLetterStatus.PENDING));

        available.set(true);
        assertEquals(1, deadLetterService.retryDue());
        assertEquals(1, deadLetterService.count(DeadLetterStatus.SUCCEEDED));
        NotificationDeadLetterPayload payload = objectMapper.convertValue(
            objectMapper.readTree(store.findByStatus(DeadLetterStatus.SUCCEEDED, 1).get(0).getPayload()),
            NotificationDeadLetterPayload.class);
        assertEquals(firstOperationId.get(), payload.operationId(), "首次与重投必须复用同一幂等键");
    }
}
