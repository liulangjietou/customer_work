package com.richard.fyoung.customerwork.observability.analytics;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffStore;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.capability.handoff.InMemoryHandoffStore;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FileFactLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 业务数据分析聚合服务单测：审批放行率/平均决策时长、人机切换平均接单结案时长、
 * 质检失败数与均分，均验证窗口过滤 + 当前积压快照不受窗口影响。
 * @author owlzhangfq@gmail.com
 */
class BusinessAnalyticsServiceTest {

    @Test
    void approvalStats_shouldComputeRateAvgDecisionAndBacklog(@TempDir Path tempDir) {
        ApprovalStore store = new InMemoryApprovalStore();

        ApprovalRequest approved = new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1000L);
        approved.approve("alice", null, 3000L);   // 决策耗时 2s
        store.save(approved);

        ApprovalRequest denied = new ApprovalRequest("AP-2", ApprovalType.REFUND, "s2", "O2", "100", "r", 1000L);
        denied.deny("bob", "no", 5000L);            // 决策耗时 4s
        store.save(denied);

        ApprovalRequest pendingInWindow = new ApprovalRequest("AP-3", ApprovalType.REFUND, "s3", "O3", "100", "r", 1000L);
        store.save(pendingInWindow);

        ApprovalRequest pendingOutsideWindow = new ApprovalRequest("AP-4", ApprovalType.REFUND, "s4", "O4", "100", "r", 100_000L);
        store.save(pendingOutsideWindow);

        BusinessAnalyticsService svc = new BusinessAnalyticsService(
            new PendingApprovalService(store), new HandoffService(), new FileFactLog(true, tempDir));

        BusinessAnalyticsReport report = svc.report(0L, 10_000L, null);
        ApprovalStats stats = report.approval();

        assertEquals(3, stats.totalInWindow(), "窗口外的 AP-4 不应计入");
        assertEquals(1L, stats.countByStatus().get("APPROVED"));
        assertEquals(1L, stats.countByStatus().get("DENIED"));
        assertEquals(1L, stats.countByStatus().get("PENDING"));
        assertEquals(0.5, stats.approvalRate(), 1e-9, "1 approved / (1 approved + 1 denied)");
        assertEquals(3.0, stats.avgDecisionSeconds(), 1e-9, "(2s+4s)/2，仅统计已决策单");
        assertEquals(2, stats.currentPendingBacklog(), "当前积压不限时间窗：AP-3 + AP-4 两条 PENDING");
    }

    @Test
    void approvalStats_withNoDecidedItems_shouldHaveNullAvgDecisionSeconds(@TempDir Path tempDir) {
        ApprovalStore store = new InMemoryApprovalStore();
        store.save(new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1000L));

        BusinessAnalyticsService svc = new BusinessAnalyticsService(
            new PendingApprovalService(store), new HandoffService(), new FileFactLog(true, tempDir));

        ApprovalStats stats = svc.report(0L, 10_000L, null).approval();
        assertEquals(0.0, stats.approvalRate(), 1e-9, "无已决策单时放行率应为 0.0（非 NaN）");
        assertNull(stats.avgDecisionSeconds(), "窗口内无已决策单时应为 null");
    }

    @Test
    void handoffStats_shouldComputeAvgTimesAndBacklog(@TempDir Path tempDir) {
        HandoffStore store = new InMemoryHandoffStore();

        HandoffTicket resolved = new HandoffTicket("HO-1", "s1", "r", 1000L);
        resolved.claim("alice", 2000L);     // 接单耗时 1s
        resolved.resolve("done", 5000L);    // 处理耗时 3s
        store.save(resolved);

        HandoffTicket claimedOnly = new HandoffTicket("HO-2", "s2", "r", 1000L);
        claimedOnly.claim("bob", 2500L);    // 接单耗时 1.5s，未结案
        store.save(claimedOnly);

        HandoffTicket pendingInWindow = new HandoffTicket("HO-3", "s3", "r", 1000L);
        store.save(pendingInWindow);

        HandoffTicket pendingOutsideWindow = new HandoffTicket("HO-4", "s4", "r", 100_000L);
        store.save(pendingOutsideWindow);

        BusinessAnalyticsService svc = new BusinessAnalyticsService(
            new PendingApprovalService(), new HandoffService(store), new FileFactLog(true, tempDir));

        HandoffStats stats = svc.report(0L, 10_000L, null).handoff();

        assertEquals(3, stats.totalInWindow(), "窗口外的 HO-4 不应计入");
        assertEquals(1L, stats.countByStatus().get("RESOLVED"));
        assertEquals(1L, stats.countByStatus().get("CLAIMED"));
        assertEquals(1L, stats.countByStatus().get("PENDING"));
        assertEquals(1.25, stats.avgTimeToClaimSeconds(), 1e-9, "(1s+1.5s)/2，HO-1 与 HO-2 均已接单");
        assertEquals(3.0, stats.avgTimeToResolveSeconds(), 1e-9, "仅 HO-1 已结案：5000-2000=3000ms");
        assertEquals(2, stats.currentPendingBacklog(), "当前积压不限时间窗：HO-3 + HO-4");
        assertEquals(1, stats.currentClaimedBacklog(), "HO-2 处理中");
    }

    @Test
    void qualityStats_withoutTenantId_shouldReturnEmptyPlaceholder(@TempDir Path tempDir) {
        BusinessAnalyticsService svc = new BusinessAnalyticsService(
            new PendingApprovalService(), new HandoffService(), new FileFactLog(true, tempDir));

        QualityStats stats = svc.report(0L, 10_000L, null).quality();
        assertNull(stats.tenantId());
        assertEquals(0, stats.failureCountInWindow());
        assertNull(stats.avgScore());
    }

    @Test
    void qualityStats_shouldAggregateScoreAndSkipNonQualityFacts(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("tenantA.jsonl");
        // 手工构造 JSONL：两条窗口内质检失败事实(score 60/80)、一条窗口外(应被过滤)、
        // 一条非 JSON 的长期记忆纯文本事实(应被安全跳过，不影响聚合)
        String lines = String.join(System.lineSeparator(),
            "{\"ts\":1000,\"tenant\":\"tenantA\",\"fact\":\"{\\\"type\\\":\\\"quality-failure\\\",\\\"score\\\":60}\"}",
            "{\"ts\":2000,\"tenant\":\"tenantA\",\"fact\":\"{\\\"type\\\":\\\"quality-failure\\\",\\\"score\\\":80}\"}",
            "{\"ts\":100000,\"tenant\":\"tenantA\",\"fact\":\"{\\\"type\\\":\\\"quality-failure\\\",\\\"score\\\":40}\"}",
            "{\"ts\":1500,\"tenant\":\"tenantA\",\"fact\":\"用户偏好深色主题（长期记忆纯文本事实，非 JSON）\"}"
        ) + System.lineSeparator();
        Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE);

        BusinessAnalyticsService svc = new BusinessAnalyticsService(
            new PendingApprovalService(), new HandoffService(), new FileFactLog(true, tempDir));

        QualityStats stats = svc.report(0L, 10_000L, "tenantA").quality();
        assertEquals("tenantA", stats.tenantId());
        assertEquals(2, stats.failureCountInWindow(), "窗口外(ts=100000)与非 JSON 纯文本事实均应排除");
        assertEquals(70.0, stats.avgScore(), 1e-9, "(60+80)/2");
    }
}
