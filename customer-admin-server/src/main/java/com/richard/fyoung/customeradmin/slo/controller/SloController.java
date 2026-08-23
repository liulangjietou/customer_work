package com.richard.fyoung.customeradmin.slo.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicySaveRequest;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicyVO;
import com.richard.fyoung.customeradmin.slo.service.SloEvaluationService;
import com.richard.fyoung.customeradmin.slo.service.SloPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前租户 SLO 策略与同步 error-budget 评估 API。 */
@RestController
@RequestMapping("/api/slo")
public class SloController {

    private final SloPolicyService policyService;
    private final SloEvaluationService evaluationService;

    public SloController(SloPolicyService policyService, SloEvaluationService evaluationService) {
        this.policyService = policyService;
        this.evaluationService = evaluationService;
    }

    @SaCheckPermission("slo:view")
    @GetMapping("/policies")
    public Result<List<SloPolicyVO>> list() {
        return Result.success(policyService.list());
    }

    @SaCheckPermission("slo:edit")
    @OperationLog(operation = "保存 SLO 策略", target = "ai_slo_policy")
    @PostMapping("/policies")
    public Result<Long> upsert(@Valid @RequestBody SloPolicySaveRequest request) {
        return Result.success(policyService.upsert(request));
    }

    @SaCheckPermission("slo:evaluate")
    @OperationLog(operation = "评估 SLO 错误预算", target = "ai_slo_alert")
    @PostMapping("/policies/{id}/evaluate")
    public Result<SloEvaluationVO> evaluate(@PathVariable Long id) {
        return Result.success(evaluationService.evaluate(id));
    }
}
