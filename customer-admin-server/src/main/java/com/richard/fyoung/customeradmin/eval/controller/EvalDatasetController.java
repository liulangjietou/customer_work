package com.richard.fyoung.customeradmin.eval.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.eval.dto.EvalCaseSaveRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetDiffVO;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetImportRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetReviewRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetVersionCreateRequest;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetRelease;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase;
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

import java.util.List;

/** 评测数据集治理：工作集 CRUD/导入导出、命名版本、审核与 diff。 */
@RestController
@RequestMapping("/api/eval/datasets")
public class EvalDatasetController {

    private final EvalDatasetAdminService datasetService;

    public EvalDatasetController(EvalDatasetAdminService datasetService) {
        this.datasetService = datasetService;
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/{type}/cases")
    public Result<List<PersistedEvalCase>> cases(@PathVariable EvalType type) {
        return Result.success(datasetService.listCases(type));
    }

    @SaCheckPermission("eval:dataset-edit")
    @OperationLog(operation = "新增评测用例", target = "cw_eval_case")
    @PostMapping("/{type}/cases")
    public Result<PersistedEvalCase> createCase(@PathVariable EvalType type,
                                                @Valid @RequestBody EvalCaseSaveRequest request) {
        return Result.success(datasetService.createCase(type, request));
    }

    @SaCheckPermission("eval:dataset-edit")
    @OperationLog(operation = "更新评测用例", target = "cw_eval_case")
    @PutMapping("/{type}/cases/{caseId}")
    public Result<PersistedEvalCase> updateCase(@PathVariable EvalType type,
                                                @PathVariable String caseId,
                                                @Valid @RequestBody EvalCaseSaveRequest request) {
        return Result.success(datasetService.updateCase(type, caseId, request));
    }

    @SaCheckPermission("eval:dataset-edit")
    @OperationLog(operation = "删除评测用例覆盖", target = "cw_eval_case")
    @DeleteMapping("/{type}/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable EvalType type, @PathVariable String caseId) {
        datasetService.deleteCase(type, caseId);
        return Result.success();
    }

    @SaCheckPermission("eval:dataset-edit")
    @OperationLog(operation = "导入评测数据集", target = "cw_eval_case")
    @PostMapping("/{type}/import")
    public Result<List<PersistedEvalCase>> importCases(
        @PathVariable EvalType type,
        @Valid @RequestBody EvalDatasetImportRequest request) {
        return Result.success(datasetService.importCases(type, request));
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/{type}/export")
    public Result<List<PersistedEvalCase>> exportCases(@PathVariable EvalType type) {
        return Result.success(datasetService.exportCases(type));
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/{type}/versions")
    public Result<List<EvalDatasetRelease>> versions(@PathVariable EvalType type) {
        return Result.success(datasetService.listVersions(type));
    }

    @SaCheckPermission("eval:dataset-edit")
    @OperationLog(operation = "创建评测数据集版本", target = "cw_eval_dataset_release")
    @PostMapping("/{type}/versions")
    public Result<EvalDatasetRelease> createVersion(
        @PathVariable EvalType type,
        @Valid @RequestBody EvalDatasetVersionCreateRequest request) {
        return Result.success(datasetService.createVersion(type, request.versionName()));
    }

    @SaCheckPermission("eval:dataset-review")
    @OperationLog(operation = "审核评测数据集版本", target = "cw_eval_dataset_release")
    @PostMapping("/versions/{releaseId}/review")
    public Result<EvalDatasetRelease> review(
        @PathVariable String releaseId,
        @Valid @RequestBody EvalDatasetReviewRequest request) {
        return Result.success(datasetService.review(releaseId, request.decision(), request.comment()));
    }

    @SaCheckPermission("eval:view")
    @GetMapping("/versions/diff")
    public Result<EvalDatasetDiffVO> diff(@RequestParam String fromReleaseId,
                                          @RequestParam String toReleaseId) {
        return Result.success(datasetService.diff(fromReleaseId, toReleaseId));
    }
}
