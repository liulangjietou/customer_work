package com.richard.fyoung.customerwork.observability.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffStatus;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.core.constant.FactTypes;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FactRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务数据分析聚合服务（运营视角：这段时间业务运转得怎么样）。
 *
 * <p>聚合审批（{@code cw_approval}）、人机切换（{@code cw_handoff_ticket}）、质检
 * （{@code FactLog}）三个已有数据源，按时间窗口计算业务指标——这些是 Prometheus 技术指标
 * 无法直接给出的"业务级周期报告"（如"过去 7 天平均放行率""平均接单时长"），数据源本身已具备
 * 时间戳与完整状态机，只是此前没有聚合视角。</p>
 *
 * <p>审批/人机切换维度的时间窗过滤在应用层完成（基于 Store SPI 已有的 {@code list()} 全量读取），
 * 未新增任何按时间范围查询的 SQL 方法——这两类"工单"表体量小（业务上是可控的案例数，非海量流水），
 * 全量加载后在内存过滤是合理的权衡，避免为聚合视角单独扩宽三个 Store SPI。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class BusinessAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(BusinessAnalyticsService.class);

    private final PendingApprovalService approvalService;
    private final HandoffService handoffService;
    private final FactLog factLog;
    private final ObjectMapper mapper = new ObjectMapper();

    public BusinessAnalyticsService(PendingApprovalService approvalService,
                                    HandoffService handoffService,
                                    FactLog factLog) {
        this.approvalService = approvalService;
        this.handoffService = handoffService;
        this.factLog = factLog;
    }

    /**
     * 聚合指定时间窗口 [windowStartMs, windowEndMs) 的业务报表。
     *
     * @param tenantId 质检维度所属租户；为空时质检维度返回空占位（见 {@link QualityStats}）
     */
    public BusinessAnalyticsReport report(long windowStartMs, long windowEndMs, String tenantId) {
        BusinessAnalyticsReport report = new BusinessAnalyticsReport(windowStartMs, windowEndMs,
            approvalStats(windowStartMs, windowEndMs),
            handoffStats(windowStartMs, windowEndMs),
            qualityStats(tenantId, windowStartMs, windowEndMs));
        log.info("business analytics report generated: windowStartMs={}, windowEndMs={}, tenantId={}",
            windowStartMs, windowEndMs, tenantId);
        return report;
    }

    private ApprovalStats approvalStats(long start, long end) {
        List<ApprovalRequest> all = approvalService.list();
        List<ApprovalRequest> inWindow = inWindow(all, ApprovalRequest::getCreatedAtMs, start, end);

        Map<String, Long> byStatus = inWindow.stream()
            .collect(Collectors.groupingBy(r -> r.getStatus().name(), Collectors.counting()));
        long approved = byStatus.getOrDefault(ApprovalStatus.APPROVED.name(), 0L);
        long denied = byStatus.getOrDefault(ApprovalStatus.DENIED.name(), 0L);
        double approvalRate = (approved + denied) == 0 ? 0.0 : (double) approved / (approved + denied);

        List<ApprovalRequest> decided = inWindow.stream()
            .filter(r -> r.getStatus() != ApprovalStatus.PENDING)
            .collect(Collectors.toList());
        Double avgDecisionSeconds = average(decided,
            r -> r.getDecidedAtMs() - r.getCreatedAtMs());

        long currentPendingBacklog = all.stream()
            .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
            .count();

        return new ApprovalStats(inWindow.size(), byStatus, approvalRate, avgDecisionSeconds, currentPendingBacklog);
    }

    private HandoffStats handoffStats(long start, long end) {
        List<HandoffTicket> all = handoffService.list();
        List<HandoffTicket> inWindow = inWindow(all, HandoffTicket::getCreatedAtMs, start, end);

        Map<String, Long> byStatus = inWindow.stream()
            .collect(Collectors.groupingBy(t -> t.getStatus().name(), Collectors.counting()));

        List<HandoffTicket> claimed = inWindow.stream()
            .filter(t -> t.getClaimedAtMs() > 0)
            .collect(Collectors.toList());
        Double avgTimeToClaimSeconds = average(claimed,
            t -> t.getClaimedAtMs() - t.getCreatedAtMs());

        List<HandoffTicket> resolved = inWindow.stream()
            .filter(t -> t.getStatus() == HandoffStatus.RESOLVED)
            .collect(Collectors.toList());
        Double avgTimeToResolveSeconds = average(resolved,
            t -> t.getResolvedAtMs() - t.getClaimedAtMs());

        long currentPendingBacklog = all.stream().filter(t -> t.getStatus() == HandoffStatus.PENDING).count();
        long currentClaimedBacklog = all.stream().filter(t -> t.getStatus() == HandoffStatus.CLAIMED).count();

        return new HandoffStats(inWindow.size(), byStatus, avgTimeToClaimSeconds, avgTimeToResolveSeconds,
            currentPendingBacklog, currentClaimedBacklog);
    }

    private QualityStats qualityStats(String tenantId, long start, long end) {
        if (!StringUtils.hasText(tenantId)) {
            return new QualityStats(null, 0, null);
        }
        List<FactRecord> inWindow = factLog.readRecords(tenantId).stream()
            .filter(r -> r.ts() >= start && r.ts() < end)
            .collect(Collectors.toList());

        List<Integer> scores = inWindow.stream()
            .map(this::parseQualityFailureScore)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());

        Double avgScore = scores.isEmpty() ? null
            : scores.stream().mapToInt(Integer::intValue).average().orElse(0);

        return new QualityStats(tenantId, scores.size(), avgScore);
    }

    /** 解析事实记录：非 quality-failure 类型（如长期记忆事实、未来其它类型）或解析失败返回 null，跳过不计。 */
    private Integer parseQualityFailureScore(FactRecord record) {
        try {
            JsonNode node = mapper.readTree(record.fact());
            if (!FactTypes.QUALITY_FAILURE.equals(node.path("type").asText())) {
                return null;
            }
            return node.path("score").asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> inWindow(List<T> items, java.util.function.ToLongFunction<T> timestampOf,
                                 long start, long end) {
        return items.stream()
            .filter(item -> {
                long ts = timestampOf.applyAsLong(item);
                return ts >= start && ts < end;
            })
            .collect(Collectors.toList());
    }

    private <T> Double average(List<T> items, java.util.function.ToLongFunction<T> durationMsOf) {
        if (items.isEmpty()) {
            return null;
        }
        return items.stream().mapToLong(durationMsOf).average().orElse(0) / 1000.0;
    }
}
