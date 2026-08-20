package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.dto.FillKnowledgeGapRequest;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识盲区运营入口：反复查不到的问题排行 + 一键补知识。
 *
 * <p>这份数据本来唾手可得（检索未命中时记一笔），此前没人记，于是补知识全靠拍脑袋——
 * 而拍出来的往往是运营自己关心的，不是用户实际在问的。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ops/knowledge-gap")
public class KnowledgeGapController {

    private static final int DEFAULT_LIMIT = 50;

    private final OpsAdminService opsAdminService;

    public KnowledgeGapController(OpsAdminService opsAdminService) {
        this.opsAdminService = opsAdminService;
    }

    /** 盲区排行：未命中次数降序，越靠前越该优先补。 */
    @SaCheckPermission("knowledge-gap:view")
    @GetMapping("/top")
    public Result<List<KnowledgeGap>> top(@RequestParam(defaultValue = TenantContext.DEFAULT) String scopeId,
                                          @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return Result.success(opsAdminService.topKnowledgeGaps(scopeId, limit));
    }

    /**
     * 一键补知识：直接往知识库 FAQ 插一条。
     *
     * <p>标题正文由运营填而非拿原问题照抄——用户的提问是口语化的，直接入库会污染检索质量。</p>
     *
     * @return 新建的知识条目 ID
     */
    @SaCheckPermission("knowledge-gap:fill")
    @OperationLog(operation = "盲区补知识", target = "cw_knowledge")
    @PostMapping("/fill")
    public Result<Long> fill(@Valid @RequestBody FillKnowledgeGapRequest request) {
        return Result.success(opsAdminService.fillKnowledgeGap(request.getTitle(), request.getContent(),
            request.getKeyword(), request.getQuestionHash()));
    }
}
