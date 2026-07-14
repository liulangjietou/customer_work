package com.richard.fyoung.customeradmin.aiconfig.systemtool.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolVO;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.service.SystemToolService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统工具管理：分页查询 + 编辑（改名称/描述/启停/备注）。工具目录是代码定义的，不支持新建/删除。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system-tool")
public class SystemToolController {

    private final SystemToolService systemToolService;

    public SystemToolController(SystemToolService systemToolService) {
        this.systemToolService = systemToolService;
    }

    @SaCheckPermission("system-tool:view")
    @GetMapping
    public Result<PageResult<SystemToolVO>> page(PageQuery query) {
        return Result.success(systemToolService.page(query));
    }

    @SaCheckPermission("system-tool:view")
    @GetMapping("/{id}")
    public Result<SystemToolVO> get(@PathVariable Long id) {
        return Result.success(systemToolService.get(id));
    }

    @SaCheckPermission("system-tool:edit")
    @OperationLog(operation = "编辑系统工具", target = "ai_system_tool")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SystemToolSaveRequest request) {
        systemToolService.update(id, request);
        return Result.success();
    }
}
