package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterService;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStatus;
import com.richard.fyoung.customerwork.capability.deadletter.InMemoryDeadLetterStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 可靠投递队列的指标与健康状态门控。 */
class DeliveryQueueObservabilityTest {

    @Test
    void metrics_shouldExposePublishDeliveryAndDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxHandler handler = new OutboxHandler() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public void handle(OutboxMessage message) {
                // 成功即可。
            }
        };
        OutboxService service = new OutboxService(store, new CustomerWorkProperties().getOutbox(),
            List.of(handler), registry);

        service.publish("test", "A-1", "{}");
        assertEquals(1.0, registry.get("customerwork.outbox.published").counter().count());
        assertEquals(1.0, registry.get("customerwork.outbox.depth").tag("status", "pending").gauge().value());

        service.dispatchDue();

        assertEquals(1.0, registry.get("customerwork.outbox.deliveries")
            .tag("result", "success").counter().count());
        assertEquals(0.0, registry.get("customerwork.outbox.depth").tag("status", "pending").gauge().value());
    }

    @Test
    void health_shouldBecomeDegradedWhenMessageIsAbandoned() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getOutbox().setMaxAttempts(1);
        properties.getOutbox().setBaseBackoffMs(0L);
        InMemoryOutboxStore outboxStore = new InMemoryOutboxStore();
        OutboxHandler failing = new OutboxHandler() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public void handle(OutboxMessage message) {
                throw new IllegalStateException("down");
            }
        };
        OutboxService service = new OutboxService(outboxStore, properties.getOutbox(), List.of(failing));
        InMemoryDeadLetterStore deadLetterStore = new InMemoryDeadLetterStore();
        service.publish("test", "A-1", "{}");
        service.dispatchDue();

        DeliveryQueueHealthIndicator health = new DeliveryQueueHealthIndicator(
            outboxStore, deadLetterStore, properties);

        assertEquals(new Status("DEGRADED"), health.health().getStatus());
        assertEquals(1L, health.health().getDetails().get("outbox.abandoned"));
    }

    @Test
    void deadLetterMetrics_shouldExposeRecordedAndSucceeded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getDeadLetter().setBaseBackoffMs(0L);
        DeadLetterService service = new DeadLetterService(new InMemoryDeadLetterStore(),
            properties.getDeadLetter(), List.of(new com.richard.fyoung.customerwork.capability.deadletter.DeadLetterHandler() {
                @Override
                public String type() {
                    return "notify";
                }

                @Override
                public void retry(com.richard.fyoung.customerwork.capability.deadletter.DeadLetter letter) {
                    // 成功即可。
                }
            }), registry);

        service.record("notify", "{}", "B-1", "timeout");
        service.retryDue();

        assertEquals(1.0, registry.get("customerwork.deadletter.recorded").counter().count());
        assertEquals(1.0, registry.get("customerwork.deadletter.retries")
            .tag("result", "success").counter().count());
        assertEquals(1, service.count(DeadLetterStatus.SUCCEEDED));
    }
}
