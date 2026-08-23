package com.richard.fyoung.customeradmin.aiconfig.model.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteDryRunVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRoutePolicyCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRoutePolicyVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteValidationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteVersionCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteVersionVO;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模型路由策略控制面；不存在更新版本内容的接口。 */
@RestController
@RequestMapping("/api/aiconfig/model-routing-policies")
public class ModelRoutingPolicyController {

    private final ModelRoutingPolicyService routingPolicyService;

    public ModelRoutingPolicyController(ModelRoutingPolicyService routingPolicyService) {
        this.routingPolicyService = routingPolicyService;
    }

    @SaCheckPermission("model:view")
    @GetMapping
    public Result<List<ModelRoutePolicyVO>> list() {
        return Result.success(routingPolicyService.list());
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}")
    public Result<ModelRoutePolicyVO> get(@PathVariable Long id) {
        return Result.success(routingPolicyService.get(id));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/versions")
    public Result<List<ModelRouteVersionVO>> versions(@PathVariable Long id) {
        return Result.success(routingPolicyService.versions(id));
    }

    @SaCheckPermission("model:edit")
    @OperationLog(operation = "新建模型路由策略", target = "ai_model_route_policy")
    @PostMapping
    public Result<ModelRoutePolicyVO> create(@Valid @RequestBody ModelRoutePolicyCreateRequest request) {
        return Result.success(routingPolicyService.create(request));
    }

    @SaCheckPermission("model:edit")
    @PostMapping("/{id}/versions/validate")
    public Result<ModelRouteValidationVO> validate(@PathVariable Long id,
                                                   @Valid @RequestBody ModelRouteVersionCreateRequest request) {
        routingPolicyService.get(id);
        return Result.success(routingPolicyService.validate(request));
    }

    @SaCheckPermission("model:edit")
    @OperationLog(operation = "创建模型路由策略版本", target = "ai_model_route_policy_version")
    @PostMapping("/{id}/versions")
    public Result<ModelRouteVersionVO> createVersion(@PathVariable Long id,
                                                     @Valid @RequestBody ModelRouteVersionCreateRequest request) {
        return Result.success(routingPolicyService.createVersion(id, request));
    }

    @SaCheckPermission("model:edit")
    @OperationLog(operation = "激活模型路由策略版本", target = "ai_model_route_policy_version")
    @PutMapping("/{id}/versions/{versionId}/activate")
    public Result<ModelRoutePolicyVO> activate(@PathVariable Long id, @PathVariable Long versionId) {
        return Result.success(routingPolicyService.activate(id, versionId));
    }

    @SaCheckPermission("model:view")
    @PostMapping("/{id}/dry-run")
    public Result<ModelRouteDryRunVO> dryRun(@PathVariable Long id,
                                             @Valid @RequestBody ModelRouteDryRunRequest request) {
        return Result.success(routingPolicyService.dryRun(id, request));
    }
}
