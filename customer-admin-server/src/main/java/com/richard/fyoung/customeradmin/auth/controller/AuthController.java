package com.richard.fyoung.customeradmin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.EmailCodeRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaChallengeResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaProofResponse;
import com.richard.fyoung.customeradmin.auth.dto.LoginCaptchaVerifyRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.dto.PasswordResetEmailCodeRequest;
import com.richard.fyoung.customeradmin.auth.dto.PasswordResetRequest;
import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.auth.dto.SsoLoginRequest;
import com.richard.fyoung.customeradmin.auth.guard.CaptchaChallenge;
import com.richard.fyoung.customeradmin.auth.guard.ClientIpResolver;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.service.AuthService;
import com.richard.fyoung.customeradmin.auth.service.PasswordResetService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.user.service.UserRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录认证：登录（含失败记录）/ 登出 / 改密 / 找回密码 / 当前用户信息。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRegistrationService userRegistrationService;
    private final RegistrationGuard registrationGuard;
    private final ClientIpResolver clientIpResolver;
    private final LoginCaptchaService loginCaptchaService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, UserRegistrationService userRegistrationService,
                          RegistrationGuard registrationGuard,
                          ClientIpResolver clientIpResolver,
                          LoginCaptchaService loginCaptchaService,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.userRegistrationService = userRegistrationService;
        this.registrationGuard = registrationGuard;
        this.clientIpResolver = clientIpResolver;
        this.loginCaptchaService = loginCaptchaService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        ClientContext client = clientContextOf(httpRequest);
        return Result.success(authService.login(request, client.ip(), client.userAgent()));
    }

    /** 下发登录拼图 challenge；签发限流、TTL 与指纹绑定均由独立服务负责。 */
    @PostMapping("/login-captcha/challenge")
    public Result<LoginCaptchaChallengeResponse> loginCaptchaChallenge(HttpServletRequest httpRequest) {
        ClientContext client = clientContextOf(httpRequest);
        return Result.success(loginCaptchaService.issueChallenge(client.ip(), client.userAgent()));
    }

    /** 校验一次拼图落点与轨迹，并签发仅能消费一次的登录 proof。 */
    @PostMapping("/login-captcha/verify")
    public Result<LoginCaptchaProofResponse> verifyLoginCaptcha(
        @Valid @RequestBody LoginCaptchaVerifyRequest request, HttpServletRequest httpRequest) {
        ClientContext client = clientContextOf(httpRequest);
        return Result.success(loginCaptchaService.verify(request, client.ip(), client.userAgent()));
    }

    /**
     * 登录页需要知道的部署形态：开不开放注册、要不要验证码与邮箱、能不能自助找回密码。
     *
     * <p>放在登录前的匿名接口里：登录页此刻还没有任何登录态，
     * 而"这个实例开不开放注册"本身不是敏感信息。</p>
     */
    @GetMapping("/register-options")
    public Result<RegisterOptionsVO> registerOptions() {
        return Result.success(new RegisterOptionsVO(
            registrationGuard.selfServiceEnabled(),
            registrationGuard.captchaRequired(),
            registrationGuard.emailRequired(),
            registrationGuard.emailVerificationRequired(),
            registrationGuard.emailCodeResendCooldownSeconds(),
            passwordResetService.available()));
    }

    /**
     * 找回密码第一步：向账号登记的邮箱发验证码。
     *
     * <p>图形验证码在这里<b>无条件</b>校验（不看部署形态），理由与注册发码同源，
     * 但更强一层：这个接口对着的是已经存在的账号。</p>
     *
     * <p><b>返回值与"邮箱压根没注册"时完全一致</b>，见 {@code PasswordResetService} 的类注释。</p>
     *
     * @return 验证码有效期（秒），供前端做倒计时提示
     */
    @PostMapping("/password-reset/email-code")
    public Result<Integer> sendPasswordResetEmailCode(@Valid @RequestBody PasswordResetEmailCodeRequest request,
                                                      HttpServletRequest httpRequest) {
        return Result.success(passwordResetService.sendCode(request, clientIpOf(httpRequest)));
    }

    /**
     * 找回密码第二步：核验验证码并设置新密码。
     *
     * <p>不做 {@code @OperationLog}：那个切面靠 {@code StpUtil.isLogin()} 解析操作人，
     * 而这里是匿名请求。留痕由 Service 的 info 日志承担。</p>
     */
    @PostMapping("/password-reset")
    public Result<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request,
                                      HttpServletRequest httpRequest) {
        passwordResetService.reset(request, clientIpOf(httpRequest));
        return Result.success();
    }

    /**
     * 向注册邮箱发送验证码。
     *
     * <p>图形验证码在这一步校验（而不是注册那一步）：发信是唯一会向站外第三方产生副作用的
     * 匿名操作——服务端替调用者给任意地址发一封信，它才是最该先挡住脚本的地方。</p>
     *
     * @return 验证码有效期（秒），供前端做倒计时提示
     */
    @PostMapping("/email-code")
    public Result<Integer> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                         HttpServletRequest httpRequest) {
        return Result.success(userRegistrationService.sendEmailCode(
            request.email(), request.captchaId(), request.captcha(), clientIpOf(httpRequest)));
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
        return clientIpResolver.resolve(request);
    }

    /** 登录链路只解析一次来源上下文，验证码绑定、锁定与日志共享同一份值。 */
    private ClientContext clientContextOf(HttpServletRequest request) {
        return new ClientContext(clientIpOf(request), request.getHeader(HttpHeaders.USER_AGENT));
    }

    private record ClientContext(String ip, String userAgent) {
    }

    /**
     * 登录页需要知道的部署形态。
     *
     * @param selfServiceEnabled       是否开放自助注册
     * @param captchaRequired          是否需要图形验证码。开启邮箱验证时它用在<b>发码</b>那一步，
     *                                 否则用在注册那一步。<b>找回密码不看这一位</b>，它无条件要求图形码
     * @param emailRequired            是否必须填邮箱
     * @param emailVerificationRequired 是否需要邮箱验证码（决定注册表单渲染"获取验证码"按钮）
     * @param emailCodeCooldownSeconds 同一邮箱两次发码之间的服务端冷却时间（秒），注册与找回密码共用
     * @param passwordResetEnabled     能否自助找回密码。它跟随邮件服务是否真的可用，
     *                                 没有对应的配置开关——多一个开关就多一个漏配点，
     *                                 而配错的后果是"用户永远找不回密码"且无人告警
     */
    public record RegisterOptionsVO(boolean selfServiceEnabled, boolean captchaRequired,
                                    boolean emailRequired, boolean emailVerificationRequired,
                                    int emailCodeCooldownSeconds, boolean passwordResetEnabled) {
    }

    /** OA 域账号（LDAP/AD）单点登录，与上面的账号密码登录入口共存，前端登录页 Tab 切换。 */
    @PostMapping("/sso-login")
    public Result<LoginResponse> ssoLogin(@Valid @RequestBody SsoLoginRequest request,
                                          HttpServletRequest httpRequest) {
        ClientContext client = clientContextOf(httpRequest);
        return Result.success(authService.ssoLogin(request, client.ip(), client.userAgent()));
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
