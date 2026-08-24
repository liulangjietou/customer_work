package com.richard.fyoung.customeradmin.slo.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.slo.dto.SloAlertEventVO;
import com.richard.fyoung.customeradmin.slo.dto.SloAlertVO;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicySaveRequest;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicyVO;
import com.richard.fyoung.customeradmin.slo.service.SloAlertService;
import com.richard.fyoung.customeradmin.slo.service.SloEvaluationService;
import com.richard.fyoung.customeradmin.slo.service.SloPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 当前租户 SLO 策略、手工评估与告警生命周期 API。 */
@RestController
@RequestMapping("/api/slo")
public class SloController {

    private final SloPolicyService policyService;
    private final SloEvaluationService evaluationService;
    private final SloAlertService alertService;

    public SloController(SloPolicyService policyService,
                         SloEvaluationService evaluationService,
                         SloAlertService alertService) {
        this.policyService = policyService;
        this.evaluationService = evaluationService;
        this.alertService = alertService;
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

    @SaCheckPermission("slo:view")
    @GetMapping("/alerts")
    public Result<List<SloAlertVO>> alerts(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer limit) {
        return Result.success(alertService.list(status, limit));
    }

    @SaCheckPermission("slo:view")
    @GetMapping("/alerts/{id}/events")
    public Result<List<SloAlertEventVO>> alertEvents(@PathVariable Long id) {
        return Result.success(alertService.events(id));
    }

    @SaCheckPermission("slo:ack")
    @OperationLog(operation = "确认 SLO 告警", target = "ai_slo_alert")
    @PostMapping("/alerts/{id}/ack")
    public Result<Void> acknowledge(@PathVariable Long id) {
        alertService.acknowledge(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
