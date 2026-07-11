package com.richard.fyoung.customeradmin.system.menu.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuChangeLogVO;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 菜单管理：树形增删改/拖拽排序/图标上传/发布广播/变更审计流水。
 *
 * <p>与 {@link com.richard.fyoung.customeradmin.system.permission.controller.PermissionController}
 * 共享同一份 {@link PermissionService}/{@code sys_permission} 表，但走独立的 {@code menu:*} 权限点——
 * 那个 Controller 是"角色管理页面挑选权限点"的只读树，借用 {@code role:view}/{@code role:edit}；
 * 这里是真正面向"系统管理 › 菜单管理"页面的编辑入口，两者职责不同，不合并、不复用权限码，
 * 避免"能分配角色权限"和"能改菜单结构"这两件事被绑死成同一个授权开关。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/menu")
public class MenuAdminController {

    private final PermissionService permissionService;
    private final MenuChangeLogService changeLogService;
    private final MenuIconStorageService iconStorageService;

    public MenuAdminController(PermissionService permissionService, MenuChangeLogService changeLogService,
                               MenuIconStorageService iconStorageService) {
        this.permissionService = permissionService;
        this.changeLogService = changeLogService;
        this.iconStorageService = iconStorageService;
    }

    @SaCheckPermission("menu:view")
    @GetMapping("/tree")
    public Result<List<PermissionVO>> tree() {
        return Result.success(permissionService.tree());
    }

    @SaCheckPermission("menu:add")
    @OperationLog(operation = "新建菜单节点", target = "sys_permission")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody PermissionSaveRequest request) {
        permissionService.create(request);
        return Result.success();
    }

    @SaCheckPermission("menu:edit")
    @OperationLog(operation = "编辑菜单节点", target = "sys_permission")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PermissionSaveRequest request) {
        permissionService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("menu:delete")
    @OperationLog(operation = "删除菜单节点", target = "sys_permission")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("menu:edit")
    @OperationLog(operation = "拖拽调整菜单顺序", target = "sys_permission")
    @PutMapping("/reorder")
    public Result<Void> reorder(@Valid @RequestBody MenuReorderRequest request) {
        permissionService.reorder(request);
        return Result.success();
    }

    @SaCheckPermission("menu:edit")
    @OperationLog(operation = "发布菜单变更", target = "sys_permission")
    @PostMapping("/publish")
    public Result<Void> publish() {
        permissionService.publish();
        return Result.success();
    }

    @SaCheckPermission("menu:edit")
    @PostMapping("/icon")
    public Result<String> uploadIcon(@RequestPart("file") MultipartFile file) {
        return Result.success(iconStorageService.upload(file));
    }

    @SaCheckPermission("menu:view")
    @GetMapping("/change-log")
    public Result<PageResult<MenuChangeLogVO>> changeLog(
        @RequestParam(required = false) Long menuId, PageQuery query) {
        return Result.success(changeLogService.page(menuId, query));
    }
}
