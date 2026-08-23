package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalReleaseGateService;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGateOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGatePolicyRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGatePolicyVO;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.RuntimePublishGateVO;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 现有可靠发布任务的评测门禁策略、判定详情、重评与紧急豁免 API。 */
@RestController
@RequestMapping("/api/aiconfig/runtime-publish/gate")
public class EvalReleaseGateController {

    private final EvalReleaseGateService service;

    public EvalReleaseGateController(EvalReleaseGateService service) {
        this.service = service;
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/policies")
    public Result<List<EvalGatePolicyVO>> policies() {
        return Result.success(service.listPolicies());
    }

    @SaCheckPermission("eval:gate-policy-edit")
    @OperationLog(operation = "保存评测发布门禁策略", target = "ai_eval_release_gate_policy")
    @PutMapping("/policies/{type}")
    public Result<EvalGatePolicyVO> savePolicy(@PathVariable EvalType type,
                                               @Valid @RequestBody EvalGatePolicyRequest request) {
        return Result.success(service.savePolicy(type, request, StpUtil.getLoginIdAsLong()));
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/tasks/{taskId}")
    public Result<RuntimePublishGateVO> task(@PathVariable String taskId) {
        return Result.success(service.taskGate(taskId));
    }

    @SaCheckPermission("eval:run")
    @OperationLog(operation = "重新评估发布门禁", target = "ai_runtime_publish_task")
    @PostMapping("/tasks/{taskId}/retry")
    public Result<Void> retry(@PathVariable String taskId) {
        service.retry(taskId);
        return Result.success();
    }

    @SaCheckPermission("eval:gate-override")
    @OperationLog(operation = "紧急豁免评测发布门禁", target = "ai_eval_release_gate_override")
    @PostMapping("/tasks/{taskId}/override")
    public Result<Void> override(@PathVariable String taskId,
                                 @Valid @RequestBody EvalGateOverrideRequest request) {
        service.override(taskId, request, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
