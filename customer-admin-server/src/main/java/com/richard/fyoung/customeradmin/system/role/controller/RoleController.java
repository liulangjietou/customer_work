package com.richard.fyoung.customeradmin.system.role.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.dto.RoleVO;
import com.richard.fyoung.customeradmin.system.role.service.RoleService;
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
 * 角色/权限分配管理：CRUD + 分页/搜索/筛选/排序。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/role")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @SaCheckPermission("role:view")
    @GetMapping
    public Result<PageResult<RoleVO>> page(PageQuery query) {
        return Result.success(roleService.page(query));
    }

    @SaCheckPermission("role:view")
    @GetMapping("/{id}")
    public Result<RoleVO> get(@PathVariable Long id) {
        return Result.success(roleService.get(id));
    }

    @SaCheckPermission("role:add")
    @OperationLog(operation = "新建角色", target = "sys_role")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody RoleSaveRequest request) {
        roleService.create(request);
        return Result.success();
    }

    @SaCheckPermission("role:edit")
    @OperationLog(operation = "编辑角色", target = "sys_role")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest request) {
        roleService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("role:delete")
    @OperationLog(operation = "删除角色", target = "sys_role")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }
}
