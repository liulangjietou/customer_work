package com.richard.fyoung.customeradmin.improvement.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementBindArtifactRequest;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementCaseVO;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementEvalCaseRequest;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementReevaluateRequest;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementTriageRequest;
import com.richard.fyoung.customeradmin.improvement.service.ImprovementCaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** KnowledgeGap/badcase 共用的责任、复评、发布与线上效果闭环。 */
@RestController
@RequestMapping("/api/improvement-cases")
public class ImprovementCaseController {

    private final ImprovementCaseService service;

    public ImprovementCaseController(ImprovementCaseService service) {
        this.service = service;
    }

    @SaCheckPermission("improvement:manage")
    @GetMapping("/source/{sourceType}/{sourceKey}")
    public Result<ImprovementCaseVO> bySource(@PathVariable ImprovementSourceType sourceType,
                                              @PathVariable String sourceKey) {
        return Result.success(service.findBySource(sourceType, sourceKey).orElse(null));
    }

    @SaCheckPermission("improvement:manage")
    @GetMapping("/{id}")
    public Result<ImprovementCaseVO> detail(@PathVariable Long id) {
        return Result.success(service.detail(id));
    }

    @SaCheckPermission("improvement:manage")
    @OperationLog(operation = "认领智能体改进项", target = "ai_agent_improvement_case")
    @PostMapping("/source/{sourceType}/{sourceKey}/triage")
    public Result<ImprovementCaseVO> triage(@PathVariable ImprovementSourceType sourceType,
                                            @PathVariable String sourceKey,
                                            @Valid @RequestBody ImprovementTriageRequest request) {
        return Result.success(service.triage(sourceType, sourceKey, request,
            StpUtil.getLoginIdAsString()));
    }

    @SaCheckPermission("improvement:manage")
    @OperationLog(operation = "创建改进回归用例", target = "cw_eval_case")
    @PostMapping("/{id}/eval-case")
    public Result<ImprovementCaseVO> createEvalCase(@PathVariable Long id,
                                                     @Valid @RequestBody ImprovementEvalCaseRequest request) {
        return Result.success(service.createEvalCase(id, request, StpUtil.getLoginIdAsString()));
    }

    @SaCheckPermission("improvement:manage")
    @OperationLog(operation = "绑定智能体改进制品", target = "ai_agent_improvement_case")
    @PostMapping("/{id}/artifact")
    public Result<ImprovementCaseVO> bindArtifact(@PathVariable Long id,
                                                  @Valid @RequestBody ImprovementBindArtifactRequest request) {
        return Result.success(service.bindArtifact(id, request));
    }

    @SaCheckPermission("eval:run")
    @OperationLog(operation = "复评智能体改进项", target = "cw_eval_run")
    @PostMapping("/{id}/reevaluate")
    public Result<ImprovementCaseVO> reevaluate(@PathVariable Long id,
                                                @RequestBody(required = false)
                                                ImprovementReevaluateRequest request) {
        return Result.success(service.reevaluate(id, request == null ? null : request.remark()));
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "发布智能体改进项", target = "ai_runtime_publish_task")
    @PostMapping("/{id}/publish")
    public Result<ImprovementCaseVO> publish(@PathVariable Long id) {
        return Result.success(service.publish(id));
    }

    @SaCheckPermission("improvement:manage")
    @PostMapping("/{id}/refresh")
    public Result<ImprovementCaseVO> refresh(@PathVariable Long id) {
        return Result.success(service.scheduleRefresh(id));
    }
}
