package com.richard.fyoung.customerwork.infra.notification;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

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
}
