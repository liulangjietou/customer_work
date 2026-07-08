package com.richard.fyoung.customeradmin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
