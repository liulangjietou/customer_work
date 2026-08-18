package com.richard.fyoung.customeradmin.datascope;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把当前用户的数据范围写入 {@link DataScopeContext}，供持久层拦截器读取。
 *
 * <p>与 {@code TenantContextInterceptor} 同构：请求进来时写、结束时清。线程是复用的，
 * {@code afterCompletion} 的清理不能省，否则下一个请求会沿用上一个人的归属条件——
 * 表现为"张三看到了李四的数据"，正是本功能要防的事。</p>
 * @author owlzhangfq@gmail.com
 */
public class DataScopeInterceptor implements HandlerInterceptor {

    private final DataScopeResolver resolver;

    public DataScopeInterceptor(DataScopeResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DataScope scope = resolver.resolve();
        if (scope != null) {
            DataScopeContext.set(scope, currentUserId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        DataScopeContext.clear();
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }
}
