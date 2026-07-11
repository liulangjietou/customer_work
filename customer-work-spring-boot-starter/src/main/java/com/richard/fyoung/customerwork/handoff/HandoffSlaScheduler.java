package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 人机切换工单 SLA 巡检器（升级引擎）。
 *
 * <p>周期性扫描工单，超过 SLA 阈值仍未流转的按阶段告警：</p>
 * <ul>
 *   <li>{@code PENDING} 超过 {@code human-handoff.sla-pending-seconds} 未被坐席接单——
 *       坐席队列积压或缺人手；</li>
 *   <li>{@code CLAIMED} 超过 {@code human-handoff.sla-claimed-seconds} 未结案——
 *       坐席可能卡在处理中，需主管介入。</li>
 * </ul>
 *
 * <p>与 {@link com.richard.fyoung.customerwork.approval.ApprovalTimeoutScheduler} 的"escalate"
 * 分支同一设计语言：只读扫描 + 结构化告警日志，不引入新状态、不做自动流转（人机切换没有"自动接单/
 * 自动结案"这种合理的兜底动作，升级只能是告警，交由人工介入）。每个巡检周期对仍超标的工单重复告警
 * （与既有 {@code ApprovalTimeoutScheduler} 一致，依赖下游日志/指标系统做告警去重与聚合，不在本类维护
 * 状态）。仅当对应阈值 &gt; 0 时生效。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class HandoffSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(HandoffSlaScheduler.class);

    private static final String M_SLA_BREACH = "customerwork.handoff.sla.breach";
    private static final String STAGE_PENDING = "pending";
    private static final String STAGE_CLAIMED = "claimed";

    private final CustomerWorkProperties properties;
    private final HandoffService handoffService;
    /** 可为 null：未接入 Micrometer 时观测降级为仅日志。 */
    private final MeterRegistry meterRegistry;

    public HandoffSlaScheduler(CustomerWorkProperties properties,
                               HandoffService handoffService,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        this.handoffService = handoffService;
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void checkSla() {
        checkPendingSla();
        checkClaimedSla();
    }

    /** PENDING 超阈值未接单告警（抽出以便单测）。 */
    void checkPendingSla() {
        long slaSeconds = properties.getHumanHandoff().getSlaPendingSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * 1000;
        List<HandoffTicket> pending = handoffService.listByStatus(HandoffStatus.PENDING);
        for (HandoffTicket ticket : pending) {
            if (ticket.getCreatedAtMs() < threshold) {
                breach(ticket.getId(), STAGE_PENDING, ticket.getCreatedAtMs(), slaSeconds);
            }
        }
    }

    /** CLAIMED 超阈值未结案告警（抽出以便单测）。 */
    void checkClaimedSla() {
        long slaSeconds = properties.getHumanHandoff().getSlaClaimedSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * 1000;
        List<HandoffTicket> claimed = handoffService.listByStatus(HandoffStatus.CLAIMED);
        for (HandoffTicket ticket : claimed) {
            if (ticket.getClaimedAtMs() < threshold) {
                breach(ticket.getId(), STAGE_CLAIMED, ticket.getClaimedAtMs(), slaSeconds);
            }
        }
    }

    /** 单条 SLA 超标告警：结构化日志 + 计数指标。 */
    private void breach(String ticketId, String stage, long sinceMs, long slaSeconds) {
        long ageSeconds = (System.currentTimeMillis() - sinceMs) / 1000;
        if (meterRegistry != null) {
            Counter.builder(M_SLA_BREACH).tag("stage", stage).register(meterRegistry).increment();
        }
        log.error("[SLA] handoff breach, code={}, stage={}, id={}, ageSeconds={}, slaSeconds={}",
            "HANDOFF_SLA_BREACH", stage, ticketId, ageSeconds, slaSeconds);
    }

    /** 可被单测调用的同步入口（跳过 @Scheduled 注解）。 */
    public void runSlaCheck() {
        checkSla();
    }
}
