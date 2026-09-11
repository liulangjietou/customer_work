package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeGapReviewDetail;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeGapReviewRequest;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeGapReviewService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 查看与复核权限分开，浏览器无法指定租户、来源或操作人。 */
@RestController
@Validated
@RequestMapping("/api/ops/knowledge-gap/reviews")
public class KnowledgeGapReviewController {
    private final KnowledgeGapReviewService service;

    public KnowledgeGapReviewController(KnowledgeGapReviewService service) {
        this.service = service;
    }

    /** 所有有看板读取权限的人均可查证来源与历史。 */
    @SaCheckPermission("knowledge-gap:view")
    @GetMapping("/{questionHash}")
    public Result<KnowledgeGapReviewDetail> detail(
        @PathVariable @Pattern(regexp = "[a-f0-9]{64}") String questionHash,
        @RequestParam(defaultValue = "9223372036854775807") @Min(1) long beforeRevision) {
        return Result.success(service.detail(questionHash, beforeRevision));
    }

    /** 分类必须有理由；写入身份由当前登录态确定。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "improvement:manage"}, mode = SaMode.AND)
    @OperationLog(operation = "复核知识缺口分类", target = "cw_knowledge_gap_review")
    @PostMapping("/{questionHash}")
    public Result<KnowledgeGap> review(
        @PathVariable @Pattern(regexp = "[a-f0-9]{64}") String questionHash,
        @Valid @RequestBody KnowledgeGapReviewRequest request) {
        return Result.success(service.review(questionHash, request, StpUtil.getLoginIdAsString()));
    }
}
