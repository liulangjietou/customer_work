package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识候选沿用看板读取与补知识权限；客户端不能提交租户和实际操作人。 */
@RestController
@Validated
@RequestMapping("/api/ops/knowledge-gap/candidates")
public class KnowledgeCandidateController {
    private final KnowledgeCandidateService service;

    public KnowledgeCandidateController(KnowledgeCandidateService service) {
        this.service = service;
    }

    /** 按来源恢复候选编辑内容，故障通过标准 Result 返回。 */
    @SaCheckPermission("knowledge-gap:view")
    @GetMapping("/source/{questionHash}")
    public Result<KnowledgeCandidate> bySource(@PathVariable @Pattern(regexp = "[a-f0-9]{64}") String questionHash) {
        return Result.success(service.bySource(questionHash));
    }

    /** 保存候选不会立即改变正式知识；两种权限都具备才能写入。 */
    @SaCheckPermission(value = {"knowledge-gap:view", "knowledge-gap:fill"}, mode = SaMode.AND)
    @OperationLog(operation = "保存知识候选", target = "ai_knowledge_candidate_revision")
    @PutMapping("/{id}")
    public Result<KnowledgeCandidate> save(@PathVariable @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String id,
                                           @Valid @RequestBody KnowledgeCandidateSaveRequest request) {
        return Result.success(service.save(id, request, StpUtil.getLoginIdAsLong()));
    }
}
