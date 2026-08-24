package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentRevisionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeSourceSyncService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
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

import java.util.List;

/** 知识库文档源、同步运行和 lineage 运维接口。 */
@RestController
@RequestMapping("/api/aiconfig/knowledge-base/{knowledgeBaseId}/sources")
public class KnowledgeSourceController {

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceSyncService syncService;

    public KnowledgeSourceController(KnowledgeSourceService sourceService,
                                     KnowledgeSourceSyncService syncService) {
        this.sourceService = sourceService;
        this.syncService = syncService;
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping
    public Result<List<KnowledgeSourceVO>> list(@PathVariable Long knowledgeBaseId) {
        return Result.success(sourceService.list(knowledgeBaseId));
    }

    @SaCheckPermission("knowledge-base:edit")
    @OperationLog(operation = "新建知识文档源", target = "ai_knowledge_source")
    @PostMapping
    public Result<Long> create(@PathVariable Long knowledgeBaseId,
                               @Valid @RequestBody KnowledgeSourceSaveRequest request) {
        return Result.success(sourceService.create(knowledgeBaseId, request));
    }

    @SaCheckPermission("knowledge-base:edit")
    @OperationLog(operation = "编辑知识文档源", target = "ai_knowledge_source")
    @PutMapping("/{sourceId}")
    public Result<Void> update(@PathVariable Long knowledgeBaseId,
                               @PathVariable Long sourceId,
                               @Valid @RequestBody KnowledgeSourceSaveRequest request) {
        sourceService.update(knowledgeBaseId, sourceId, request);
        return Result.success();
    }

    @SaCheckPermission("knowledge-base:delete")
    @OperationLog(operation = "删除知识文档源", target = "ai_knowledge_source")
    @DeleteMapping("/{sourceId}")
    public Result<Void> delete(@PathVariable Long knowledgeBaseId, @PathVariable Long sourceId) {
        sourceService.delete(knowledgeBaseId, sourceId);
        return Result.success();
    }

    @SaCheckPermission("knowledge-base:source-sync")
    @OperationLog(operation = "同步知识文档源", target = "ai_knowledge_sync_run")
    @PostMapping("/{sourceId}/sync")
    public Result<KnowledgeSyncRunVO> sync(@PathVariable Long knowledgeBaseId,
                                           @PathVariable Long sourceId,
                                           @Valid @RequestBody KnowledgeSyncRequest request) {
        return Result.success(syncService.sync(knowledgeBaseId, sourceId, request));
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/{sourceId}/sync-runs")
    public Result<List<KnowledgeSyncRunVO>> runs(@PathVariable Long knowledgeBaseId,
                                                 @PathVariable Long sourceId) {
        return Result.success(sourceService.runs(knowledgeBaseId, sourceId));
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/{sourceId}/documents/lineage")
    public Result<List<KnowledgeDocumentRevisionVO>> lineage(@PathVariable Long knowledgeBaseId,
                                                              @PathVariable Long sourceId,
                                                              @RequestParam String externalId) {
        return Result.success(sourceService.lineage(knowledgeBaseId, sourceId, externalId));
    }
}
