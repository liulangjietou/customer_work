package com.richard.fyoung.customeradmin.aiconfig.model.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAssetOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthEventVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigService;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretRotationRequest;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.List;

/**
 * AI 模型配置管理：CRUD + 分页/搜索/筛选/排序 + 连通性测试。
 *
 * <p>{@code test-connectivity} 返回 {@link CompletableFuture}：Spring MVC 异步支持下，
 * 该请求在等待外部模型接口响应期间释放 Tomcat 请求线程，不占用主线程池（实施计划 §五）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/model")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @SaCheckPermission("model:view")
    @GetMapping
    public Result<PageResult<ModelVO>> page(PageQuery query) {
        return Result.success(modelConfigService.page(query));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}")
    public Result<ModelVO> get(@PathVariable Long id) {
        return Result.success(modelConfigService.get(id));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/asset-options")
    public Result<List<ModelAssetOptionVO>> assetOptions() {
        return Result.success(modelConfigService.assetOptions());
    }

    @SaCheckPermission("model:add")
    @OperationLog(operation = "新建模型配置", target = "ai_model_config")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ModelSaveRequest request) {
        modelConfigService.create(request);
        return Result.success();
    }

    @SaCheckPermission("model:edit")
    @OperationLog(operation = "编辑模型配置", target = "ai_model_config")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ModelSaveRequest request) {
        modelConfigService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("model:delete")
    @OperationLog(operation = "删除模型配置", target = "ai_model_config")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/impact")
    public Result<ModelImpactVO> impact(@PathVariable Long id,
                                        @RequestParam(required = false) String action) {
        return Result.success(modelConfigService.impact(id, action));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/health")
    public Result<ModelHealthSnapshotVO> health(@PathVariable Long id) {
        return Result.success(modelConfigService.health(id));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/health-events")
    public Result<List<ModelHealthEventVO>> healthEvents(@PathVariable Long id,
                                                         @RequestParam(required = false) Integer limit) {
        return Result.success(modelConfigService.healthEvents(id, limit));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/certification")
    public Result<ModelCertificationVO> certification(@PathVariable Long id) {
        return Result.success(modelConfigService.certification(id));
    }

    @SaCheckPermission("model:view")
    @GetMapping("/{id}/certification-runs")
    public Result<List<ModelCertificationVO>> certificationHistory(@PathVariable Long id) {
        return Result.success(modelConfigService.certificationHistory(id));
    }

    @SaCheckPermission("model:certify")
    @OperationLog(operation = "执行模型上线认证", target = "ai_model_certification_run")
    @PostMapping("/{id}/certifications")
    public Result<ModelCertificationVO> certify(@PathVariable Long id,
                                                @Valid @RequestBody ModelCertificationRequest request) {
        return Result.success(modelConfigService.certify(id, request));
    }

    @SaCheckPermission("model:edit")
    @OperationLog(operation = "轮换模型凭据", target = "ai_secret_ref")
    @PutMapping("/{id}/credential")
    public Result<SecretMetadataVO> rotateCredential(@PathVariable Long id,
                                                      @Valid @RequestBody SecretRotationRequest request) {
        return Result.success(modelConfigService.rotateCredential(id, request));
    }

    /** 仅探测可达性；只读 default 共享配置的探测结果不会回写共享记录。 */
    @SaCheckPermission("model:health-test")
    @OperationLog(operation = "模型连通性测试", target = "ai_model_config")
    @PostMapping("/{id}/test-connectivity")
    public CompletableFuture<Result<ModelTestResult>> testConnectivity(@PathVariable Long id) {
        return modelConfigService.testConnectivity(id).thenApply(Result::success);
    }

    @SaCheckPermission("model:health-test")
    @OperationLog(operation = "模型健康探测", target = "ai_model_health_event")
    @PostMapping("/{id}/health-checks")
    public CompletableFuture<Result<ModelTestResult>> healthCheck(@PathVariable Long id) {
        return modelConfigService.testConnectivity(id).thenApply(Result::success);
    }
}
