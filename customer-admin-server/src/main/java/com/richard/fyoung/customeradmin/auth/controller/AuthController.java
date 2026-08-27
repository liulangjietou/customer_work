package com.richard.fyoung.customeradmin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.auth.dto.SsoLoginRequest;
import com.richard.fyoung.customeradmin.auth.guard.CaptchaChallenge;
import com.richard.fyoung.customeradmin.auth.guard.ClientIpResolver;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.user.service.UserRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final UserRegistrationService userRegistrationService;
    private final RegistrationGuard registrationGuard;
    private final RegistrationGuardProperties registrationProperties;

    public AuthController(AuthService authService, UserRegistrationService userRegistrationService,
                          RegistrationGuard registrationGuard,
                          RegistrationGuardProperties registrationProperties) {
        this.authService = authService;
        this.userRegistrationService = userRegistrationService;
        this.registrationGuard = registrationGuard;
        this.registrationProperties = registrationProperties;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /**
     * 注册页的开关与验证码要求，前端据此决定是否渲染验证码框与邮箱必填星号。
     *
     * <p>放在登录前的匿名接口里：注册页此刻还没有任何登录态，
     * 而"这个实例开不开放注册"本身不是敏感信息。</p>
     */
    @GetMapping("/register-options")
    public Result<RegisterOptionsVO> registerOptions() {
        return Result.success(new RegisterOptionsVO(
            registrationGuard.selfServiceEnabled(),
            registrationGuard.captchaRequired(),
            registrationGuard.emailRequired()));
    }

    /** 下发一张图形验证码；每次调用都是新的一张，前端点击图片即可刷新。签发按 IP 限流。 */
    @GetMapping("/captcha")
    public Result<CaptchaChallenge> captcha(HttpServletRequest httpRequest) {
        return Result.success(registrationGuard.issueCaptcha(clientIpOf(httpRequest)));
    }

    /**
     * 本地账号自助注册：只创建待审核、无角色的最小权限账号。
     *
     * <p>来源 IP 在这里解析后传给 Service：限流按 IP 计，而 Service 不该依赖 Web 线程上下文。</p>
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest httpRequest) {
        userRegistrationService.register(request, clientIpOf(httpRequest));
        return Result.success();
    }

    /** 来源 IP 解析口径与限流、登录锁定共用一处实现。 */
    private String clientIpOf(HttpServletRequest request) {
        return ClientIpResolver.resolve(request, registrationProperties.isTrustForwardedHeader());
    }

    /**
     * 注册页需要知道的部署形态。
     *
     * @param selfServiceEnabled 是否开放自助注册
     * @param captchaRequired    是否必须填验证码
     * @param emailRequired      是否必须填邮箱
     */
    public record RegisterOptionsVO(boolean selfServiceEnabled, boolean captchaRequired,
                                    boolean emailRequired) {
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
