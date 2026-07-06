package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.diagnostics.DiagnosticService;
import com.richard.fyoung.customerwork.diagnostics.SessionDiagnostic;
import com.richard.fyoung.customerwork.observability.AuditRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 会话故障诊断端点（线上定位专用，受 X-API-Key 保护，面向运维/坐席后台）。
 *
 * <p>把散落在 StateStore / 对话阶段 / 槽位 / 审批 / 审计 / 质检六处的会话现场一次拉齐，
 * 把"用户报障 → 定位根因"从人肉查多张表缩短为一次接口调用。诊断读取涉及 JDBC / 文件 IO，
 * 统一放到 {@code boundedElastic} 线程执行，不阻塞 Netty 事件循环。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/diagnostics")
@Tag(name = "故障诊断", description = "按 sessionId 聚合会话全景 / 审计事件下钻")
public class DiagnosticController {

    private final DiagnosticService diagnosticService;

    public DiagnosticController(DiagnosticService diagnosticService) {
        this.diagnosticService = diagnosticService;
    }

    @Operation(summary = "会话诊断全景",
        description = "按 sessionId 聚合会话状态/对话阶段/槽位进度/审批单/最近审计/质检事实")
    @GetMapping("/session/{sessionId}")
    public Mono<SessionDiagnostic> diagnose(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "" + DiagnosticService.DEFAULT_AUDIT_LIMIT) int auditLimit,
            @RequestParam(defaultValue = "" + DiagnosticService.DEFAULT_FACT_LIMIT) int factLimit) {
        return Mono.fromCallable(() -> diagnosticService.diagnose(sessionId, auditLimit, factLimit))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "审计事件下钻",
        description = "按 sessionId 查更多审计事件（需接入可查询审计后端如 JdbcAuditSink，否则返回空）")
    @GetMapping("/session/{sessionId}/audit")
    public Mono<List<AuditRecord>> audit(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(() -> diagnosticService.diagnose(sessionId, limit, 0).getRecentAudit())
            .subscribeOn(Schedulers.boundedElastic());
    }
}
