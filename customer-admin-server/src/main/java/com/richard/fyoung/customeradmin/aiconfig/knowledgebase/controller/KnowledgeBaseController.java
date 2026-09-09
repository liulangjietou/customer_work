package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection.KnowledgeProjectionService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVersionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 知识库配置管理：CRUD + 分页/搜索 + 启停 + 连通性测试 + 智能体表单下拉。
 *
 * <p>{@code test-connectivity} 返回 {@link CompletableFuture}：Spring MVC 异步支持下，
 * 该请求在等待外部 RAG 服务响应期间释放 Tomcat 请求线程（与 {@code ModelConfigController} 同款）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/knowledge-base")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseVersionService versionService;
    private final KnowledgeProjectionService projectionService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeBaseVersionService versionService,
                                   KnowledgeProjectionService projectionService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.versionService = versionService;
        this.projectionService = projectionService;
    }

    /**
     * 把某个知识库版本投影到客服端库，让 C 端能真正检索到它。
     *
     * <p><b>为什么需要一个显式动作</b>：知识资产的编辑、审核、版本都在后台，而运行时要读的表
     * 在客服端库。没有这一步，运营在后台做的一切对线上对话零影响——那正是这套知识栈此前的状态：
     * 看板显示知识库好好的，用户那边一问三不知。</p>
     *
     * <p>用 {@code knowledge-base:edit} 而不新开权限点：能编辑知识库的人本就决定着
     * 客服端看到什么内容，投影只是把这个决定生效，不构成新的数据出口。</p>
     */
    @SaCheckPermission("knowledge-base:edit")
    @OperationLog(operation = "知识库投影到客服端", target = "ai_knowledge_base")
    @PostMapping("/{id}/versions/{versionId}/project")
    public Result<Integer> project(@PathVariable Long id, @PathVariable Long versionId) {
        return Result.success(projectionService.project(id, versionId));
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/page")
    public Result<PageResult<KnowledgeBaseVO>> page(PageQuery query) {
        return Result.success(knowledgeBaseService.page(query));
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> get(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.get(id));
    }

    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/{id}/versions")
    public Result<List<KnowledgeBaseVersionVO>> versions(@PathVariable Long id) {
        return Result.success(versionService.versions(id));
    }

    /** 智能体表单下拉：仅 status=1 且 testStatus=1 的知识库，权限点复用 view。 */
    @SaCheckPermission("knowledge-base:view")
    @GetMapping("/options")
    public Result<List<KnowledgeBaseOptionVO>> options() {
        return Result.success(knowledgeBaseService.options());
    }

    @SaCheckPermission("knowledge-base:add")
    @OperationLog(operation = "新建知识库", target = "ai_knowledge_base")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody KnowledgeBaseSaveRequest request) {
        knowledgeBaseService.create(request);
        return Result.success();
    }

    @SaCheckPermission("knowledge-base:edit")
    @OperationLog(operation = "编辑知识库", target = "ai_knowledge_base")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody KnowledgeBaseSaveRequest request) {
        knowledgeBaseService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("knowledge-base:delete")
    @OperationLog(operation = "删除知识库", target = "ai_knowledge_base")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("knowledge-base:edit")
    @OperationLog(operation = "知识库启停", target = "ai_knowledge_base")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam int status) {
        knowledgeBaseService.updateStatus(id, status);
        return Result.success();
    }

    /** 不修改配置，仅探测可达性，复用 knowledge-base:view 权限点即可，不额外新增权限点。 */
    @SaCheckPermission("knowledge-base:view")
    @OperationLog(operation = "知识库连通性测试", target = "ai_knowledge_base")
    @PostMapping("/{id}/test-connectivity")
    public CompletableFuture<Result<KnowledgeBaseTestResult>> testConnectivity(@PathVariable Long id) {
        return knowledgeBaseService.testConnectivity(id).thenApply(Result::success);
    }
}
