package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 人机切换工单 SLA 巡检器单测：PENDING/CLAIMED 超阈值告警、未超时不告警、阈值 &lt;=0 禁用。
 * @author owlzhangfq@gmail.com
 */
class HandoffSlaSchedulerTest {

    private static final String METRIC = "customerwork.handoff.sla.breach";

    @SuppressWarnings("unchecked")
    private HandoffSlaScheduler scheduler(CustomerWorkProperties props, HandoffService svc,
                                          SimpleMeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new HandoffSlaScheduler(props, svc, provider);
    }

    @Test
    void pendingOverThreshold_shouldBreachAndCountMetric() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setSlaPendingSeconds(1);

        InMemoryHandoffStore store = new InMemoryHandoffStore();
        HandoffService svc = new HandoffService(store);
        HandoffTicket created = svc.create("s1", "投诉升级");
        // 模拟超时：把创建时间改到 2 秒前，重新 save（InMemoryHandoffStore.save 是 upsert）
        store.save(new HandoffTicket(created.getId(), "s1", "投诉升级", System.currentTimeMillis() - 2000));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandoffSlaScheduler scheduler = scheduler(props, svc, registry);
        scheduler.runSlaCheck();

        double count = registry.get(METRIC).tag("stage", "pending").counter().count();
        assertEquals(1.0, count);
    }

    @Test
    void claimedOverThreshold_shouldBreachAndCountMetric() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setSlaClaimedSeconds(1);

        InMemoryHandoffStore store = new InMemoryHandoffStore();
        HandoffService svc = new HandoffService(store);
        HandoffTicket ticket = svc.create("s1", "test");
        svc.claim(ticket.getId(), "alice");
        // 模拟接单已久：直接改 claimedAtMs 为 2 秒前重新落库
        HandoffTicket stale = store.find(ticket.getId()).orElseThrow();
        HandoffTicket rebuilt = HandoffTicket.reconstruct(stale.getId(), stale.getSessionId(),
            stale.getReason(), stale.getCreatedAtMs(), HandoffStatus.CLAIMED, "alice",
            System.currentTimeMillis() - 2000, null, 0L);
        store.save(rebuilt);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandoffSlaScheduler scheduler = scheduler(props, svc, registry);
        scheduler.runSlaCheck();

        double count = registry.get(METRIC).tag("stage", "claimed").counter().count();
        assertEquals(1.0, count);
    }

    @Test
    void recentTicket_shouldNotBreach() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setSlaPendingSeconds(60);

        HandoffService svc = new HandoffService();
        svc.create("s1", "test");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandoffSlaScheduler scheduler = scheduler(props, svc, registry);
        scheduler.runSlaCheck();

        assertNull(registry.find(METRIC).counter(), "未超时不应告警");
    }

    @Test
    void slaDisabled_shouldNotBreach() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        // slaPendingSeconds/slaClaimedSeconds 默认 0 = 禁用

        InMemoryHandoffStore store = new InMemoryHandoffStore();
        HandoffService svc = new HandoffService(store);
        HandoffTicket ticket = svc.create("s1", "test");
        store.save(new HandoffTicket(ticket.getId(), "s1", "test", System.currentTimeMillis() - 999_000));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HandoffSlaScheduler scheduler = scheduler(props, svc, registry);
        scheduler.runSlaCheck();

        assertNull(registry.find(METRIC).counter(), "阈值<=0 时应完全禁用");
    }
}
