package com.richard.fyoung.customeradmin.subjectquota.runtime;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaDecision;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 后台登录用户的 AI 用量判定入口。
 *
 * <p>admin 是 Spring MVC，没有 {@code WebFilter}，因此这套判定用 {@link HandlerInterceptor} 实现，
 * 而不是复用客服端那个 {@code SubjectQuotaWebFilter}。</p>
 *
 * <p><b>为什么要写 {@code QuotaSubjectContext}</b>：token 的真实用量要到模型调用之后才知道，
 * 由 {@code AgentCallTimingMiddleware} 补记。MVC 这一段虽是同一个线程，但 AI 链路随后会切到
 * Reactor 线程，故 {@code ChatService} 还要把它写进 Reactor Context——两处缺一，token 就记不到人头上。</p>
 *
 * <p><b>租户上下文</b>：等级表按租户隔离，而租户拦截器排在最后执行（本拦截器之后），
 * 所以这里自己按登录态的租户包一层，不能依赖那个还没跑的拦截器。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class AdminQuotaInterceptor implements HandlerInterceptor {

    private final SubjectQuotaGuard guard;
    private final ObjectMapper objectMapper;

    public AdminQuotaInterceptor(SubjectQuotaGuard guard, ObjectMapper objectMapper) {
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!StpUtil.isLogin()) {
            // 未登录的请求由 Sa-Token 拦截器负责拒绝，这里无从建立可信主体
            QuotaSubjectContext.clear();
            AgentInvocationIdentityContext.clear();
            return true;
        }
        QuotaSubject subject = QuotaSubject.adminUser(StpUtil.getLoginIdAsString());
        // 主体身份来自 Sa-Token，必须独立于配额开关存在；MCP 授权与审计同样依赖它。
        QuotaSubjectContext.set(subject);
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            tenantOf(), subject.type(), subject.id(), true)
            .withChannel(AgentInvocationIdentity.CHANNEL_ADMIN));
        if (!guard.isEnabled()) {
            return true;
        }
        String tenantId = tenantOf();
        SubjectQuotaDecision decision = TenantContext.callWith(tenantId,
            () -> guard.check(subject, request.getRequestURI()));

        if (decision.shouldBlock()) {
            QuotaSubjectContext.clear();
            AgentInvocationIdentityContext.clear();
            writeQuotaExceeded(response, decision);
            return false;
        }
        TenantContext.runWith(tenantId, () -> guard.recordRequest(subject));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程是复用的，不清理会让下一个请求的 token 记到上一个人头上
        QuotaSubjectContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    /** 登录态里的租户；取不到按默认租户算（未开多租户时就是这种情况）。 */
    private static String tenantOf() {
        String tenantId = TenantSession.effectiveTenant();
        return tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
    }

    /**
     * 429 + 统一 Result 包装。
     *
     * <p>状态码用 429 而不是 200：前端的响应拦截器按状态码分流，用 200 包一个错误码
     * 会让"额度用尽"混进正常响应里，只有认真读 body 的调用方才发现。</p>
     */
    private void writeQuotaExceeded(HttpServletResponse response, SubjectQuotaDecision decision)
            throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        Result<Void> body = Result.failure(ResultCode.QUOTA_EXCEEDED, decision.message());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
