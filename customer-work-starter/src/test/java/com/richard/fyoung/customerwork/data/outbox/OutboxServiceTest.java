package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.infra.config.properties.OutboxProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Outbox 投递语义单测：租约互斥、成功终态、失败退避与无 Handler 不耗次数。 */
class OutboxServiceTest {

    @Test
    void publishAndDispatch_shouldSucceedOnce() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        AtomicInteger calls = new AtomicInteger();
        OutboxHandler handler = handler("ticket-event", calls, false);
        OutboxService service = new OutboxService(store, properties(), List.of(handler));

        service.publish("ticket-event", "TK-1", "{}");

        assertEquals(1, service.dispatchDue());
        assertEquals(1, calls.get());
        assertEquals(1, service.count(OutboxStatus.SUCCEEDED));
        assertEquals(0, service.dispatchDue(), "成功消息不能重复投递");
    }

    @Test
    void failedDelivery_shouldBackoffAndEventuallyAbandon() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        AtomicInteger calls = new AtomicInteger();
        OutboxProperties properties = properties();
        properties.setMaxAttempts(2);
        properties.setBaseBackoffMs(0L);
        OutboxService service = new OutboxService(store, properties,
            List.of(handler("ticket-event", calls, true)));
        service.publish("ticket-event", "TK-1", "{}");

        service.dispatchDue();
        service.dispatchDue();

        assertEquals(2, calls.get());
        assertEquals(1, service.count(OutboxStatus.ABANDONED));
    }

    @Test
    void claimLease_shouldPreventAnotherOwnerBeforeExpiry() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxMessage message = OutboxMessage.create("type", "A", "{}");
        store.save(message);
        long now = System.currentTimeMillis();

        assertEquals(1, store.claimDue("owner-1", now, now + 60_000L, 10).size());
        assertEquals(0, store.claimDue("owner-2", now, now + 60_000L, 10).size());
    }

    @Test
    void missingHandler_shouldReturnPendingWithoutAttempt() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        OutboxProperties properties = properties();
        properties.setScanIntervalMs(0L);
        OutboxService service = new OutboxService(store, properties, List.of());
        service.publish("missing", "A", "{}");

        service.dispatchDue();

        assertEquals(1, service.count(OutboxStatus.PENDING));
    }

    @Test
    void duplicateHandlerType_shouldFailFast() {
        AtomicInteger calls = new AtomicInteger();
        OutboxHandler first = handler("ticket-event", calls, false);
        OutboxHandler second = handler("ticket-event", calls, false);

        assertThrows(IllegalStateException.class, () ->
            new OutboxService(new InMemoryOutboxStore(), properties(), List.of(first, second)));
    }

    @Test
    void dispatchShouldRestoreMessageTenantContext() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        AtomicReference<String> handledTenant = new AtomicReference<>();
        OutboxHandler handler = new OutboxHandler() {
            @Override
            public String type() {
                return "ticket-event";
            }

            @Override
            public void handle(OutboxMessage message) {
                handledTenant.set(TenantContext.require());
            }
        };
        OutboxService service = new OutboxService(store, properties(), List.of(handler));
        TenantContext.runWith("tenant-a", () -> service.publish("ticket-event", "TK-1", "{}"));

        service.dispatchDue();

        assertEquals("tenant-a", handledTenant.get());
        assertNull(TenantContext.get());
    }

    private OutboxProperties properties() {
        OutboxProperties properties = new OutboxProperties();
        properties.setLeaseMs(60_000L);
        return properties;
    }

    private OutboxHandler handler(String type, AtomicInteger calls, boolean fail) {
        return new OutboxHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(OutboxMessage message) {
                calls.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("downstream unavailable");
                }
            }
        };
    }
}
