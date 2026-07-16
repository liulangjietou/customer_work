package com.richard.fyoung.customerwork.diagnostics;

import com.richard.fyoung.customerwork.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.dialog.DialogStageService;
import com.richard.fyoung.customerwork.memory.FactLog;
import com.richard.fyoung.customerwork.observability.AuditQuery;
import com.richard.fyoung.customerwork.service.SessionStateManager;
import com.richard.fyoung.customerwork.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.support.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话故障诊断聚合服务（线上定位的核心：一次拉齐分散在多个存储里的会话现场）。
 *
 * <p>把 StateStore / 对话阶段 / 槽位进度 / 审批单 / 审计事件 / 质检事实六个数据源聚合成一个
 * {@link SessionDiagnostic}。<b>本类是"防御式聚合唯一应存在的地方"</b>：每个数据源独立 try/catch，
 * 单个源不可用（如 MySQL 瞬断、审计后端未接入）只标注到 {@code degradedSources} 并降级，
 * 绝不让诊断工具自身崩溃——因为它恰恰要在系统部分故障时还能用。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticService.class);

    /** 审计事件默认返回条数。 */
    public static final int DEFAULT_AUDIT_LIMIT = 20;
    /** 质检事实默认返回条数。 */
    public static final int DEFAULT_FACT_LIMIT = 20;

    private final SessionStateManager sessionStateManager;
    private final DialogStageService dialogStageService;
    private final SlotFillingService slotFillingService;
    private final PendingApprovalService approvalService;
    private final FactLog factLog;
    private final TenantResolver tenantResolver;
    /** 可选：仅当接入了可查询的审计实现（MybatisAuditSink）时存在。 */
    private final ObjectProvider<AuditQuery> auditQueryProvider;

    public DiagnosticService(SessionStateManager sessionStateManager,
                             DialogStageService dialogStageService,
                             SlotFillingService slotFillingService,
                             PendingApprovalService approvalService,
                             FactLog factLog,
                             TenantResolver tenantResolver,
                             ObjectProvider<AuditQuery> auditQueryProvider) {
        this.sessionStateManager = sessionStateManager;
        this.dialogStageService = dialogStageService;
        this.slotFillingService = slotFillingService;
        this.approvalService = approvalService;
        this.factLog = factLog;
        this.tenantResolver = tenantResolver;
        this.auditQueryProvider = auditQueryProvider;
    }

    /** 以默认条数聚合诊断。 */
    public SessionDiagnostic diagnose(String sessionId) {
        return diagnose(sessionId, DEFAULT_AUDIT_LIMIT, DEFAULT_FACT_LIMIT);
    }

    /**
     * 聚合会话诊断全景。
     *
     * @param sessionId  会话 ID（可含租户前缀）
     * @param auditLimit 审计事件返回上限
     * @param factLimit  质检事实返回上限（取最近的）
     */
    public SessionDiagnostic diagnose(String sessionId, int auditLimit, int factLimit) {
        SessionDiagnostic d = new SessionDiagnostic();
        d.setSessionId(sessionId);
        String tenant = tenantResolver.resolve(sessionId);
        d.setTenantId(tenant);

        // 1) 持久化短期状态是否存在
        try {
            d.setStateExists(sessionStateManager.exists(tenant, sessionId));
        } catch (Exception e) {
            degrade(d, "state", e);
        }

        // 2) 对话阶段
        try {
            d.setDialogStage(dialogStageService.current(sessionId).name());
        } catch (Exception e) {
            degrade(d, "dialogStage", e);
        }

        // 3) 槽位收集进度（目前唯一表单：退款）
        try {
            slotFillingService.peek(sessionId, SlotFillingForm.FORM_REFUND).ifPresent(p -> {
                d.setSlotFilling(p.snapshot());
                d.setSlotFillingAsking(p.getAsking());
            });
        } catch (Exception e) {
            degrade(d, "slotFilling", e);
        }

        // 4) 关联审批单（按 sessionId 过滤）
        try {
            List<ApprovalRequest> approvals = approvalService.list().stream()
                .filter(a -> sessionId.equals(a.getSessionId()))
                .collect(Collectors.toList());
            d.setApprovals(approvals);
        } catch (Exception e) {
            degrade(d, "approvals", e);
        }

        // 5) 最近审计事件（可选后端）
        AuditQuery auditQuery = auditQueryProvider.getIfAvailable();
        if (auditQuery != null) {
            d.setAuditAvailable(true);
            try {
                d.setRecentAudit(auditQuery.queryBySession(sessionId, auditLimit));
            } catch (Exception e) {
                degrade(d, "audit", e);
            }
        }

        // 6) 质检失败事实（按 sessionId 过滤租户事实流水，取最近 factLimit 条）
        try {
            List<String> matched = factLog.read(tenant).stream()
                .filter(fact -> fact != null && fact.contains(sessionId))
                .collect(Collectors.toList());
            if (matched.size() > factLimit) {
                matched = matched.subList(matched.size() - factLimit, matched.size());
            }
            d.setQualityFacts(matched);
        } catch (Exception e) {
            degrade(d, "qualityFacts", e);
        }

        log.info("session diagnosed, sessionId={} tenant={} stateExists={} approvals={} auditEvents={} degraded={}",
            sessionId, tenant, d.isStateExists(), d.getApprovals().size(),
            d.getRecentAudit().size(), d.getDegradedSources());
        return d;
    }

    /** 记录某数据源采集失败，并降级（不中断整体诊断）。 */
    private void degrade(SessionDiagnostic d, String source, Exception e) {
        d.getDegradedSources().add(source + ": " + e.getMessage());
        log.error("diagnostic source collect failed, code={}, source={}, sessionId={}",
            "DIAGNOSTIC_SOURCE_ERROR", source, d.getSessionId(), e);
    }
}
