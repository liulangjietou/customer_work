package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentRevisionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSourceVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeSyncRunVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeDocumentUploadService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.richard.fyoung.customerwork.data.attachment.DocumentTextExtractor;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 知识库文档源、同步运行和 lineage 运维接口。 */
@RestController
@RequestMapping("/api/aiconfig/knowledge-base/{knowledgeBaseId}/sources")
public class KnowledgeSourceController {

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceSyncService syncService;
    private final KnowledgeDocumentUploadService uploadService;

    public KnowledgeSourceController(KnowledgeSourceService sourceService,
                                     KnowledgeSourceSyncService syncService,
                                     KnowledgeDocumentUploadService uploadService) {
        this.sourceService = sourceService;
        this.syncService = syncService;
        this.uploadService = uploadService;
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

    /**
     * 上传文档文件入库（pdf/word/ppt/excel/文本等）。
     *
     * <p>与 {@code /sync} 是同一件事的两个入口，因此共用 {@code source-sync} 权限：
     * 它们最终都走同一条 UPSERT 链路，区别只是正文由服务端从文件里提取还是调用方直接给。
     * 单独发一个权限点会让「谁能往知识库灌内容」这件事有两处答案。</p>
     */
    @SaCheckPermission("knowledge-base:source-sync")
    @OperationLog(operation = "上传知识文档", target = "ai_knowledge_sync_run")
    @PostMapping("/{sourceId}/documents/upload")
    public Result<KnowledgeSyncRunVO> upload(@PathVariable Long knowledgeBaseId,
                                             @PathVariable Long sourceId,
                                             @RequestPart("files") List<MultipartFile> files) {
        return Result.success(uploadService.upload(knowledgeBaseId, sourceId, files));
    }

    /** 可上传的文件类型，供前端做选择器过滤与错误提示，避免前后端各维护一份清单。 */
    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/document-upload-options")
    public Result<Set<String>> uploadOptions(@PathVariable Long knowledgeBaseId) {
        return Result.success(new TreeSet<>(DocumentTextExtractor.allowedExtensions()));
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
