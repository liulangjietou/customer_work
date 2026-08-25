package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.safety.security.UserPrincipals;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaUsage;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前用户的额度查询（登录态必需，由 {@code UserAuthWebFilter} 保证）。
 *
 * <p><b>为什么要把额度暴露给用户自己</b>：被限流的那一刻才知道有额度，是最糟的知情方式。
 * 前端可以据此提前提示"本时段还剩几次"，把一次硬邦邦的 429 变成一句可预期的提醒。</p>
 *
 * <p>只读当前用量，不返回"何时恢复"：滚动窗口下额度是连续释放的，任何一个恢复时刻都只对
 * 某一笔用量成立，报出去只会变成客服要解释的新问题。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/user/quota")
@Tag(name = "用户额度", description = "当前登录用户的速率额度与已用量")
public class UserQuotaController {

    private static final String KEY_LEVEL_CODE = "levelCode";
    private static final String KEY_WINDOW_SECONDS = "windowSeconds";
    private static final String KEY_TOKEN_USED = "tokenUsed";
    private static final String KEY_TOKEN_LIMIT = "tokenLimit";
    private static final String KEY_TOKEN_REMAINING = "tokenRemaining";
    private static final String KEY_REQUEST_USED = "requestUsed";
    private static final String KEY_REQUEST_LIMIT = "requestLimit";
    private static final String KEY_REQUEST_REMAINING = "requestRemaining";
    private static final String KEY_LIMITED = "limited";

    private final SubjectQuotaGuard subjectQuotaGuard;

    public UserQuotaController(SubjectQuotaGuard subjectQuotaGuard) {
        this.subjectQuotaGuard = subjectQuotaGuard;
    }

    @Operation(summary = "我的额度", description = "当前滚动窗口内的 token 与次数用量；-1 表示该维度不限")
    @GetMapping
    public Mono<Map<String, Object>> myQuota(ServerWebExchange exchange) {
        UserPrincipal user = UserPrincipals.require(exchange);
        // 等级按租户隔离，判定必须在用户归属租户下进行
        String tenantId = user.tenantId() == null || user.tenantId().isBlank()
            ? TenantContext.DEFAULT : user.tenantId();
        SubjectQuotaUsage usage = TenantContext.callWith(tenantId,
            () -> subjectQuotaGuard.usage(QuotaSubject.user(user.userId())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY_LEVEL_CODE, usage.levelCode());
        body.put(KEY_WINDOW_SECONDS, usage.windowSeconds());
        body.put(KEY_TOKEN_USED, usage.tokenUsed());
        body.put(KEY_TOKEN_LIMIT, usage.tokenLimit());
        body.put(KEY_TOKEN_REMAINING, usage.tokenRemaining());
        body.put(KEY_REQUEST_USED, usage.requestUsed());
        body.put(KEY_REQUEST_LIMIT, usage.requestLimit());
        body.put(KEY_REQUEST_REMAINING, usage.requestRemaining());
        // 显式给一个"到底受不受限"的布尔，免得前端自己去猜 levelCode 为空是什么意思
        body.put(KEY_LIMITED, usage.levelCode() != null);
        return Mono.just(body);
    }

    /** 从 exchange 属性取当前用户主体（过滤器已保证存在，此处为单一防御点）。 */
}
