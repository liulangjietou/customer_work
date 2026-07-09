package com.richard.fyoung.customeradmin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.dto.SsoLoginRequest;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录认证：登录（含失败记录）/ 登出 / 改密 / 当前用户信息。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /** OA 域账号（LDAP/AD）单点登录，与上面的账号密码登录入口共存，前端登录页 Tab 切换。 */
    @PostMapping("/sso-login")
    public Result<LoginResponse> ssoLogin(@Valid @RequestBody SsoLoginRequest request) {
        return Result.success(authService.ssoLogin(request));
    }

    @OperationLog(operation = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @OperationLog(operation = "修改密码")
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<Long> me() {
        return Result.success(StpUtil.getLoginIdAsLong());
    }

    /** 当前用户的全量权限点（含按钮/接口级 type=2，菜单树接口只返回 type=1），前端 v-permission 指令用。 */
    @GetMapping("/permissions")
    public Result<List<String>> permissions() {
        return Result.success(StpUtil.getPermissionList());
    }
}
