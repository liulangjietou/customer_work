package com.richard.fyoung.customeradmin.system.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserPageQuery;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserVO;
import com.richard.fyoung.customeradmin.system.user.service.UserService;
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
 * 用户管理：CRUD + 分页/搜索/筛选/排序（需求文档 3.2 通用列表能力）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @SaCheckPermission("user:view")
    @GetMapping
    public Result<PageResult<UserVO>> page(UserPageQuery query) {
        return Result.success(userService.page(query));
    }

    @SaCheckPermission("user:view")
    @GetMapping("/{id}")
    public Result<UserVO> get(@PathVariable Long id) {
        return Result.success(userService.get(id));
    }

    @SaCheckPermission("user:add")
    @OperationLog(operation = "新建用户", target = "sys_user")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody UserSaveRequest request) {
        userService.create(request);
        return Result.success();
    }

    @SaCheckPermission("user:edit")
    @OperationLog(operation = "编辑用户", target = "sys_user")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest request) {
        userService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("user:edit")
    @OperationLog(operation = "审核注册用户", target = "sys_user")
    @PutMapping("/{id}/approval")
    public Result<Void> review(@PathVariable Long id,
                               @Valid @RequestBody UserApprovalRequest request) {
        userService.review(id, request);
        return Result.success();
    }

    @SaCheckPermission("user:delete")
    @OperationLog(operation = "删除用户", target = "sys_user")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }
}
