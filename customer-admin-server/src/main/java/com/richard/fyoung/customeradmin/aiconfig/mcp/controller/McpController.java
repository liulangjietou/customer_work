package com.richard.fyoung.customeradmin.aiconfig.mcp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpVO;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 管理：CRUD + 分页/搜索/筛选/排序。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/mcp")
public class McpController {

    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @SaCheckPermission("mcp:view")
    @GetMapping
    public Result<PageResult<McpVO>> page(PageQuery query) {
        return Result.success(mcpService.page(query));
    }

    @SaCheckPermission("mcp:view")
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
}
