package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.infra.config.properties.OutboxProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Outbox 发布与投递服务：租约保证多实例不并发处理同一条消息，失败按指数退避。 */
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxStore store;
    private final OutboxProperties properties;
    private final Map<String, OutboxHandler> handlers = new HashMap<>();
    private final String instanceId;
    private final MeterRegistry meterRegistry;

    public OutboxService(OutboxStore store, OutboxProperties properties, List<OutboxHandler> handlers) {
        this(store, properties, handlers, null);
    }

    public OutboxService(OutboxStore store, OutboxProperties properties, List<OutboxHandler> handlers,
                         MeterRegistry meterRegistry) {
        this.store = store;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        for (OutboxHandler handler : handlers) {
            OutboxHandler existing = this.handlers.putIfAbsent(handler.type(), handler);
            if (existing != null) {
                throw new IllegalStateException("duplicate outbox handler type: " + handler.type());
            }
        }
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();
        registerDepthGauges();
        log.info("outbox handlers registered: {}", this.handlers.keySet());
    }

    /** 写 Outbox；失败必须抛出，由外层同库事务回滚业务变更。 */
    public OutboxMessage publish(String type, String aggregateId, String payload) {
        OutboxMessage message = OutboxMessage.create(type, aggregateId, payload);
        store.save(message);
        increment("customerwork.outbox.published", "type", type);
        return message;
    }

    /** 执行一轮租约投递。 */
    public int dispatchDue() {
        long now = System.currentTimeMillis();
        List<OutboxMessage> messages = CrossTenantOperations.execute(() ->
            store.claimDue(instanceId, now, now + properties.getLeaseMs(), properties.getBatchSize()));
        int succeeded = 0;
        for (OutboxMessage message : messages) {
            if (dispatchOne(message, now)) {
                succeeded++;
            }
        }
        if (!messages.isEmpty()) {
            log.info("outbox dispatch round finished: claimed={}, succeeded={}", messages.size(), succeeded);
        }
        return succeeded;
    }

    public long count(OutboxStatus status) {
        return store.count(status);
    }

    private boolean dispatchOne(OutboxMessage message, long nowMs) {
        OutboxHandler handler = handlers.get(message.getType());
        if (handler == null) {
            message.releaseWithoutAttempt(nowMs + properties.getScanIntervalMs());
            ensureLeaseCompletion(message);
            log.error("skip outbox without handler, code={}, id={}, type={}",
                "OUTBOX-NO-HANDLER", message.getId(), message.getType());
            increment("customerwork.outbox.deliveries", "result", "no_handler");
            return false;
        }
        try {
            handleInTenant(handler, message);
        } catch (Exception e) {
            message.fail(e.getMessage(), properties.getMaxAttempts(), properties.getBaseBackoffMs(), nowMs);
            ensureLeaseCompletion(message);
            log.error("outbox delivery failed, code={}, id={}, type={}, attempts={}, status={}",
                "OUTBOX-DELIVERY-FAIL", message.getId(), message.getType(),
                message.getAttempts(), message.getStatus(), e);
            increment("customerwork.outbox.deliveries", "result", "failure");
            return false;
        }
        message.succeed(nowMs);
        ensureLeaseCompletion(message);
        increment("customerwork.outbox.deliveries", "result", "success");
        return true;
    }

    private void ensureLeaseCompletion(OutboxMessage message) {
        if (!CrossTenantOperations.execute(() -> store.complete(message, instanceId))) {
            throw new IllegalStateException("outbox lease lost: " + message.getId());
        }
    }

    private void handleInTenant(OutboxHandler handler, OutboxMessage message) throws Exception {
        String previous = TenantContext.get();
        TenantContext.set(message.getTenantId());
        try {
            handler.handle(message);
        } finally {
            TenantContext.set(previous);
        }
    }

    private void registerDepthGauges() {
        if (meterRegistry == null) {
            return;
        }
        for (OutboxStatus status : List.of(OutboxStatus.PENDING, OutboxStatus.PROCESSING,
            OutboxStatus.ABANDONED)) {
            Gauge.builder("customerwork.outbox.depth", store,
                    target -> safeCount(target, status))
                .tag("status", status.name().toLowerCase())
                .register(meterRegistry);
        }
    }

    private double safeCount(OutboxStore target, OutboxStatus status) {
        try {
            return CrossTenantOperations.execute(() -> target.count(status));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void increment(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }
}
