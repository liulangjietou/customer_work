package com.richard.fyoung.customeradmin.aiconfig.mcp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpContractSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.service.McpContractService;
import com.richard.fyoung.customeradmin.aiconfig.mcp.service.McpService;
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
 * MCP 管理：CRUD + 分页/搜索/筛选/排序。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/mcp")
public class McpController {

    private final McpService mcpService;
    private final McpContractService contractService;

    public McpController(McpService mcpService, McpContractService contractService) {
        this.mcpService = mcpService;
        this.contractService = contractService;
    }

    @SaCheckPermission("mcp:view")
    @GetMapping
    public Result<PageResult<McpVO>> page(PageQuery query) {
        return Result.success(mcpService.page(query));
    }

    /** 编辑详情包含可复用的脱敏占位结构，只向具备编辑权限的用户开放。 */
    @SaCheckPermission("mcp:edit")
    @GetMapping("/{id}")
    public Result<McpVO> get(@PathVariable Long id) {
        return Result.success(mcpService.get(id));
    }

    @SaCheckPermission("mcp:add")
    @OperationLog(operation = "新建MCP", target = "ai_mcp")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody McpSaveRequest request) {
        mcpService.create(request);
        return Result.success();
    }

    @SaCheckPermission("mcp:edit")
    @OperationLog(operation = "编辑MCP", target = "ai_mcp")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody McpSaveRequest request) {
        mcpService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("mcp:delete")
    @OperationLog(operation = "删除MCP", target = "ai_mcp")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpService.delete(id);
        return Result.success();
    }

    /** 不修改配置，仅探测可达性，复用 mcp:view 权限点即可，不额外新增权限点。 */
    @SaCheckPermission("mcp:view")
    @OperationLog(operation = "MCP连通性测试", target = "ai_mcp")
    @PostMapping("/{id}/test-connectivity")
    public CompletableFuture<Result<McpTestResult>> testConnectivity(@PathVariable Long id) {
        return mcpService.testConnectivity(id).thenApply(Result::success);
    }

    /** 调试面板 · 列出该 MCP 提供的工具（只读探测，复用 mcp:view，不新增权限点）。 */
    @SaCheckPermission("mcp:view")
    @PostMapping("/{id}/debug/tools")
    public CompletableFuture<Result<List<McpDebugToolVO>>> debugTools(@PathVariable Long id) {
        return mcpService.listDebugTools(id).thenApply(Result::success);
    }

    /**
     * 契约快照 · 采集一次（能力差距 P1-9）。
     *
     * <p>会真的去连远端服务器列一遍工具，与上一条快照比对后落库。
     * 复用 {@code mcp:view}：契约就是 MCP 的工具定义，拿得到这个权限的人本来就能在调试面板
     * 看到同样的内容，它不是一个新的数据出口；连远端这件事本身，test-connectivity 也在做。</p>
     *
     * <p>检测到破坏性漂移<b>不会停用这个 MCP</b>——上游演进是常态，为一次漂移把业务能力
     * 整个关掉代价远大于收益，何况判定依据是我们这边的基线，基线本身可能已经过期。</p>
     */
    @SaCheckPermission("mcp:view")
    @OperationLog(operation = "MCP契约快照采集", target = "ai_mcp")
    @PostMapping("/{id}/contract/capture")
    public CompletableFuture<Result<McpContractSnapshotVO>> captureContract(@PathVariable Long id) {
        return contractService.capture(id, StpUtil.getLoginIdAsLong()).thenApply(Result::success);
    }

    /** 契约快照 · 最新一条；从未采集过返回空。 */
    @SaCheckPermission("mcp:view")
    @GetMapping("/{id}/contract")
    public Result<McpContractSnapshotVO> latestContract(@PathVariable Long id) {
        return Result.success(contractService.latest(id));
    }

    /** 契约快照 · 历史（最新在前，默认 20 条）。 */
    @SaCheckPermission("mcp:view")
    @GetMapping("/{id}/contract/history")
    public Result<List<McpContractSnapshotVO>> contractHistory(
        @PathVariable Long id, @RequestParam(required = false) Integer limit) {
        return Result.success(contractService.history(id, limit));
    }

    /** 调试面板 · 单次调用工具可能改变下游数据，必须具备编辑权限并落审计日志。 */
    @SaCheckPermission("mcp:edit")
    @OperationLog(operation = "MCP调试工具调用", target = "ai_mcp")
    @PostMapping("/{id}/debug/call")
    public CompletableFuture<Result<McpDebugCallResult>> debugCall(
        @PathVariable Long id, @Valid @RequestBody McpDebugCallRequest request) {
        return mcpService.callDebugTool(id, request).thenApply(Result::success);
    }
}
