package com.richard.fyoung.customeradmin.aiconfig.experiment.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentEventVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentMetricsVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentStopRequest;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.service.ModelExperimentService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模型在线实验控制面；RUNNING 状态通过可靠发布链驱动运行时稳定分流。 */
@RestController
@RequestMapping("/api/aiconfig/model-experiments")
public class ModelExperimentController {

    private final ModelExperimentService experimentService;

    public ModelExperimentController(ModelExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @SaCheckPermission("model-experiment:view")
    @GetMapping
    public Result<List<ModelExperimentVO>> list(@RequestParam(required = false) Long agentId,
                                                @RequestParam(required = false) String status) {
        return Result.success(experimentService.list(agentId, status));
    }

    @SaCheckPermission("model-experiment:view")
    @GetMapping("/{id}")
    public Result<ModelExperimentVO> get(@PathVariable Long id) {
        return Result.success(experimentService.get(id));
    }

    @SaCheckPermission("model-experiment:create")
    @OperationLog(operation = "创建模型在线实验", target = "ai_model_experiment")
    @PostMapping
    public Result<ModelExperimentVO> create(@Valid @RequestBody ModelExperimentCreateRequest request) {
        return Result.success(experimentService.create(request));
    }

    @SaCheckPermission("model-experiment:start")
    @OperationLog(operation = "启动模型在线实验", target = "ai_model_experiment")
    @PostMapping("/{id}/start")
    public Result<ModelExperimentVO> start(@PathVariable Long id) {
        return Result.success(experimentService.start(id));
    }

    @SaCheckPermission("model-experiment:stop")
    @OperationLog(operation = "停止模型在线实验", target = "ai_model_experiment")
    @PostMapping("/{id}/stop")
    public Result<ModelExperimentVO> stop(@PathVariable Long id,
                                          @Valid @RequestBody ModelExperimentStopRequest request) {
        return Result.success(experimentService.stop(id, request.reason()));
    }

    @SaCheckPermission("model-experiment:view")
    @GetMapping("/{id}/events")
    public Result<List<ModelExperimentEventVO>> events(@PathVariable Long id) {
        return Result.success(experimentService.events(id));
    }

    @SaCheckPermission("model-experiment:view")
    @GetMapping("/{id}/metrics")
    public Result<ModelExperimentMetricsVO> metrics(@PathVariable Long id) {
        return Result.success(experimentService.metrics(id));
    }
}
