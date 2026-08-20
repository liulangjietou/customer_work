package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.richard.fyoung.customerwork.data.user.UserAccount;
import com.richard.fyoung.customerwork.data.user.UserAccountService;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import com.richard.fyoung.customerworkapp.service.DemoOrderSeeder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终端用户账户端点（公开，无需登录态）：注册 / 登录 / 查看当前登录信息。
 *
 * <p>登录成功签发 JWT 登录态，后续 {@code /api/customer/user/**} 携带 {@code Authorization: Bearer} 访问
 * （由 {@code UserAuthWebFilter} 校验）。本控制器不在 {@code /user/**} 之下，故 {@code /me} 在控制器内
 * 手动验签。领域异常在本地翻译为语义化状态码（重名 409、登录失败 401）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/auth")
@Tag(name = "用户账户", description = "注册 / 登录 / 当前登录信息")
public class UserAuthController {

    private final UserAccountService userAccountService;
    private final UserJwtService jwtService;
    private final DemoOrderSeeder demoOrderSeeder;
    private final AvatarStorageService avatarStorageService;

    public UserAuthController(UserAccountService userAccountService, UserJwtService jwtService,
                             DemoOrderSeeder demoOrderSeeder, AvatarStorageService avatarStorageService) {
        this.userAccountService = userAccountService;
        this.jwtService = jwtService;
        this.demoOrderSeeder = demoOrderSeeder;
        this.avatarStorageService = avatarStorageService;
    }

    /**
     * 注册请求体。
     *
     * <p>{@code tenantCode} 由前端按接入方部署注入（URL 参数或构建期配置），留空归默认租户。
     * 注册与登录是仅有的两个必须由客户端提供租户线索的接口——此时还没有登录态可依据。</p>
     */
    public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 6, max = 64) String password,
        String nickname,
        String phone,
        String tenantCode) {
    }

    /** 登录请求体，{@code tenantCode} 语义同注册。 */
    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        String tenantCode) {
    }

    @Operation(summary = "注册", description = "用户名 3-64、密码 6-64；用户名重复返回 409")
    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        String tenantId = resolveTenant(request.tenantCode());
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId, () -> {
                UserAccount account = userAccountService.register(
                    request.username(), request.password(), request.nickname(), request.phone());
                // 演示订单播种：失败已在 seeder 内部 catch 并 error 日志，不影响注册主流程
                demoOrderSeeder.seedForNewUser(account.getId());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("userId", account.getId());
                return body;
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(IllegalStateException.class,
                e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()));
    }

    @Operation(summary = "登录", description = "校验失败返回 401；成功返回 JWT 登录态令牌")
    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        String tenantId = resolveTenant(request.tenantCode());
        return Mono.fromCallable(() -> TenantContext.callWith(tenantId,
                () -> userAccountService.verifyLogin(request.username(), request.password())))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "用户名或密码错误")))
            .map(account -> {
                Map<String, Object> body = new LinkedHashMap<>();
                // 租户焊进令牌：后续请求一律以令牌里的为准，不再看客户端传什么
                body.put("token", jwtService.issue(
                    account.getId(), account.getUsername(), account.getNickname(), tenantId));
                body.put("userId", account.getId());
                body.put("nickname", account.getNickname());
                body.put("expiresAtMs", jwtService.expiresAtMs());
                return body;
            });
    }

    /** 客户端未提供租户线索时归默认租户，保证单租户部署的既有前端不改一行也能继续用。 */
    private String resolveTenant(String tenantCode) {
        return tenantCode == null || tenantCode.isBlank() ? TenantContext.DEFAULT : tenantCode;
    }

    @Operation(summary = "当前登录信息", description = "需 Bearer 令牌；令牌无效返回 401")
    @GetMapping("/me")
    public Mono<Map<String, Object>> me(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        UserPrincipal principal = requirePrincipal(authorization);
        return Mono.fromCallable(() -> userAccountService.findById(principal.userId()))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(Mono::justOrEmpty)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found")))
            .map(account -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("userId", account.getId());
                body.put("username", account.getUsername());
                body.put("nickname", account.getNickname());
                body.put("phone", account.getPhone());
                body.put("avatarUrl", account.getAvatarUrl());
                return body;
            });
    }

    @Operation(summary = "上传头像", description = "需 Bearer 令牌；multipart 字段名 file，支持 png/jpg/jpeg/gif，≤2MB；返回头像访问 URL")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadAvatar(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestPart("file") FilePart file) {
        UserPrincipal principal = requirePrincipal(authorization);
        return avatarStorageService.store(file)
            .flatMap(url -> Mono.fromCallable(() -> {
                    userAccountService.updateAvatar(principal.userId(), url);
                    return url;
                })
                .subscribeOn(Schedulers.boundedElastic()))
            .map(url -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("avatarUrl", url);
                return body;
            });
    }

    /** 手动验签取登录态主体（本控制器不在 {@code /user/**} 之下，故不经 UserAuthWebFilter，令牌无效即 401）。 */
    private UserPrincipal requirePrincipal(String authorization) {
        if (authorization == null || !authorization.startsWith(HttpAuthConstants.BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }
        return jwtService.verify(authorization.substring(HttpAuthConstants.BEARER_PREFIX.length()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }
}
