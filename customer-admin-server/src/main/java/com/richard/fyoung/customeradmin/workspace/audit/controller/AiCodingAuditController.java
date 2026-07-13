package com.richard.fyoung.customeradmin.workspace.audit.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.audit.dto.AiCodingAuditQuery;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 编码审计日志查询（只读——审计记录由各埋点写入，不提供增删改接口）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/ai-coding-audit")
public class AiCodingAuditController {

    private final AiCodingAuditService auditService;

    public AiCodingAuditController(AiCodingAuditService auditService) {
        this.auditService = auditService;
    }

    @SaCheckPermission("ai-audit:view")
    @GetMapping
    public Result<PageResult<AiCodingAuditLog>> page(AiCodingAuditQuery query) {
        return Result.success(auditService.page(query));
    }
}
