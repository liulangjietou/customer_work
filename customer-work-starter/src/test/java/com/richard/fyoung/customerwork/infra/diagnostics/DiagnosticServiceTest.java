package com.richard.fyoung.customerwork.infra.diagnostics;

import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.dialog.DialogStage;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FileFactLog;
import com.richard.fyoung.customerwork.observability.AuditQuery;
import com.richard.fyoung.customerwork.observability.AuditRecord;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话诊断聚合服务单测：多源聚合、按 sessionId 过滤、审计后端可选、单源失败降级不崩溃。
 * @author owlzhangfq@gmail.com
 */
class DiagnosticServiceTest {

    private final TenantResolver tenantResolver = new TenantResolver(new CustomerWorkProperties());

    @SuppressWarnings("unchecked")
    private ObjectProvider<AuditQuery> auditProvider(AuditQuery value) {
        ObjectProvider<AuditQuery> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    void aggregatesAllSources(@TempDir Path tempDir) {
        String sessionId = "tenantA:conv-1";

        SessionStateManager stateManager = mock(SessionStateManager.class);
        when(stateManager.exists("tenantA", sessionId)).thenReturn(true);

        DialogStageService dialog = new DialogStageService();
        dialog.set(sessionId, DialogStage.PROCESSING);

        SlotFillingService slot = new SlotFillingService();
        // 一轮未收齐 → 留下"正在追问 orderId"的进度
        slot.submit(sessionId, SlotFillingForm.refundForm(), "我要退款");

        PendingApprovalService approvals = new PendingApprovalService();
        approvals.submit(ApprovalType.REFUND, sessionId, "order-1", null, "质量问题");
        approvals.submit(ApprovalType.REFUND, "other:conv-9", "order-2", null, "无关会话");

        FactLog factLog = new FileFactLog(true, tempDir);
        factLog.append("tenantA", "{\"type\":\"quality-failure\",\"sessionId\":\"tenantA:conv-1\"}");
        factLog.append("tenantA", "{\"type\":\"quality-failure\",\"sessionId\":\"tenantA:conv-2\"}");

        AuditQuery auditQuery = (sid, limit) ->
            List.of(new AuditRecord("tool-call", "CustomerServiceAgent-" + sid, "{}", 1L));

        DiagnosticService service = new DiagnosticService(
            stateManager, dialog, slot, approvals, factLog, tenantResolver, auditProvider(auditQuery));

        SessionDiagnostic d = service.diagnose(sessionId);

        assertEquals(sessionId, d.getSessionId());
        assertEquals("tenantA", d.getTenantId());
        assertTrue(d.isStateExists());
        assertEquals("PROCESSING", d.getDialogStage());
        assertEquals("orderId", d.getSlotFillingAsking());
        assertEquals(1, d.getApprovals().size(), "只应含本会话审批单");
        assertEquals("order-1", d.getApprovals().get(0).getOrderId());
        assertTrue(d.isAuditAvailable());
        assertEquals(1, d.getRecentAudit().size());
        assertEquals(1, d.getQualityFacts().size(), "只应含本会话质检事实");
        assertTrue(d.getDegradedSources().isEmpty());
    }

    @Test
    void auditUnavailableWhenNoQueryBackend(@TempDir Path tempDir) {
        SessionStateManager stateManager = mock(SessionStateManager.class);
        DiagnosticService service = new DiagnosticService(
            stateManager, new DialogStageService(), new SlotFillingService(),
            new PendingApprovalService(), new FileFactLog(true, tempDir), tenantResolver,
            auditProvider(null));  // 无可查询审计后端（如仅 LoggingAuditSink）

        SessionDiagnostic d = service.diagnose("conv-x");
        assertFalse(d.isAuditAvailable());
        assertTrue(d.getRecentAudit().isEmpty());
    }

    @Test
    void degradesGracefullyWhenOneSourceFails(@TempDir Path tempDir) {
        SessionStateManager stateManager = mock(SessionStateManager.class);
        // 让 state 源抛错，模拟 MySQL 瞬断
        when(stateManager.exists(anyString(), anyString())).thenThrow(new RuntimeException("db down"));

        DiagnosticService service = new DiagnosticService(
            stateManager, new DialogStageService(), new SlotFillingService(),
            new PendingApprovalService(), new FileFactLog(true, tempDir), tenantResolver,
            auditProvider(null));

        SessionDiagnostic d = service.diagnose("conv-x");
        // state 源降级，但其它源仍成功聚合，诊断本身不崩溃
        assertEquals(1, d.getDegradedSources().size());
        assertTrue(d.getDegradedSources().get(0).startsWith("state:"));
        assertEquals("GREETING", d.getDialogStage(), "其它源不受影响");
    }
}
