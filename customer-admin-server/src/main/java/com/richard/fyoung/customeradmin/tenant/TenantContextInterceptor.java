package com.richard.fyoung.customeradmin.tenant;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把登录态里的租户写入 {@link TenantContext}，供持久层拦截器读取。
 *
 * <p>未登录请求（登录接口、静态资源、健康检查）不设置上下文——这些链路要么不碰业务表，
 * 要么必须显式走 {@code CrossTenantOperations}（如登录时按用户名跨租户查用户）。</p>
 *
 * <p>admin 是 Spring MVC，一个请求一个线程，因此 ThreadLocal 直接可用，
 * 不需要客服端那套 Reactor 上下文传播。但线程是复用的，{@code afterCompletion} 的清理不能省，
 * 否则下一个请求会读到上一个租户——这类串号比查不到数据危险得多。</p>
 * @author owlzhangfq@gmail.com
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = TenantSession.effectiveTenant();
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
