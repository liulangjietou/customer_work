package com.richard.fyoung.customeradmin.system.permission.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限/菜单树管理：CRUD + 树查询（需求文档"二、菜单规划"静态菜单来源）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/permission")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @SaCheckPermission("role:view")
    @GetMapping("/tree")
    public Result<List<PermissionVO>> tree() {
        return Result.success(permissionService.tree());
    }

    @SaCheckPermission("role:edit")
    @OperationLog(operation = "新建权限节点", target = "sys_permission")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody PermissionSaveRequest request) {
        permissionService.create(request);
        return Result.success();
    }

    @SaCheckPermission("role:edit")
    @OperationLog(operation = "编辑权限节点", target = "sys_permission")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PermissionSaveRequest request) {
        permissionService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("role:edit")
    @OperationLog(operation = "删除权限节点", target = "sys_permission")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }
}
