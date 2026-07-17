package com.richard.fyoung.customeradmin.workspace.knowledge.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeAskRequest;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeAskResponse;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeIndexRequest;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeSearchHit;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeIndex;
import com.richard.fyoung.customeradmin.workspace.knowledge.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 代码知识库问答（P3-2 降级版）：索引管理 + 语义检索 + 检索增强问答。入口挂"工作区"（复用 workspace
 * 权限），无需新建菜单。构建为显式触发、进度可查（{@code GET /index/{id}}）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 触发构建/重建索引（异步）：立即返回索引ID，前端轮询 {@code GET /index/{id}} 看进度。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "构建代码知识库索引", target = "ai_code_knowledge_index")
    @PostMapping("/index/build")
    public Result<Long> buildIndex(@Valid @RequestBody KnowledgeIndexRequest request) {
        return Result.success(knowledgeService.buildIndex(request.indexName(), request.sourcePath()));
    }

    /** 索引列表（含状态与已入库分块数）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/index/list")
    public Result<List<AiCodeKnowledgeIndex>> listIndexes() {
        return Result.success(knowledgeService.listIndexes());
    }

    /** 单个索引详情（构建进度轮询）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/index/{indexId}")
    public Result<AiCodeKnowledgeIndex> getIndex(@PathVariable Long indexId) {
        return Result.success(knowledgeService.getIndex(indexId));
    }

    /** 删除索引及其分块。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "删除代码知识库索引", target = "ai_code_knowledge_index")
    @DeleteMapping("/index/{indexId}")
    public Result<Void> deleteIndex(@PathVariable Long indexId) {
        knowledgeService.deleteIndex(indexId);
        return Result.success(null);
    }

    /** 语义检索 top-k：返回命中片段（含文件路径/符号/相似度/片段）。 */
    @SaCheckPermission("workspace")
    @GetMapping("/search")
    public CompletableFuture<Result<List<KnowledgeSearchHit>>> search(
            @RequestParam Long indexId, @RequestParam String query,
            @RequestParam(required = false) Integer topK) {
        return knowledgeService.search(indexId, query, topK).thenApply(Result::success);
    }

    /** 检索增强问答：自然语言问题 → 相关代码片段 → 模型作答（带出处）。 */
    @SaCheckPermission("workspace")
    @OperationLog(operation = "代码知识库问答", target = "ai_code_knowledge_index")
    @PostMapping("/ask")
    public CompletableFuture<Result<KnowledgeAskResponse>> ask(@Valid @RequestBody KnowledgeAskRequest request) {
        return knowledgeService.ask(request.indexId(), request.question(), request.topK()).thenApply(Result::success);
    }
}
