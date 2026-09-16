package com.richard.fyoung.customeradmin.improvement.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
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
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateReviewVO;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidatePublishRequest;
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

    /** 知识绑定同时需要原文读取、补知识和改进管理权限。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "knowledge-gap:fill", "improvement:manage"}, mode = SaMode.AND)
    @OperationLog(operation = "绑定知识候选评测", target = "ai_knowledge_candidate_binding")
    @PostMapping("/{id}/knowledge-candidate")
    public Result<ImprovementCaseVO> bindKnowledgeCandidate(@PathVariable Long id,
                                                              @Valid @RequestBody KnowledgeCandidateBindRequest request) {
        return Result.success(service.bindKnowledgeCandidate(id, request, StpUtil.getLoginIdAsLong()));
    }

    /** 包含审核集的问题和期望要点，除知识与改进权限外还需要评测读取权限。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "improvement:manage", "eval:view"}, mode = SaMode.AND)
    @GetMapping("/{id}/knowledge-candidate")
    public Result<KnowledgeCandidateReviewVO> knowledgeCandidateReview(@PathVariable Long id) {
        return Result.success(service.knowledgeCandidateReview(id));
    }

    /** 独立权限入口避免只有运行配置评测权限的用户读取候选知识正文。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "improvement:manage", "eval:run"}, mode = SaMode.AND)
    @OperationLog(operation = "复评知识候选", target = "ai_knowledge_candidate_evaluation")
    @PostMapping("/{id}/knowledge-candidate/reevaluate")
    public Result<ImprovementCaseVO> reevaluateKnowledgeCandidate(@PathVariable Long id,
                                                                  @RequestBody(required = false)
                                                                  ImprovementReevaluateRequest request) {
        return Result.success(service.reevaluateKnowledgeCandidate(id, request == null ? null : request.remark()));
    }

    /** 发布 FAQ 需要知识写入和评测读取权限，不能借用运行配置发布权限绕过知识门禁。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "knowledge-gap:fill", "improvement:manage", "eval:view"}, mode = SaMode.AND)
    @OperationLog(operation = "提交知识候选发布", target = "ai_agent_improvement_case")
    @PostMapping("/{id}/knowledge-candidate/publish")
    public Result<ImprovementCaseVO> publishKnowledgeCandidate(@PathVariable Long id,
                                                                @Valid @RequestBody KnowledgeCandidatePublishRequest request) {
        return Result.success(service.publishKnowledgeCandidate(id, request, StpUtil.getLoginIdAsLong()));
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
