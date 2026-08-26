package com.richard.fyoung.customeradmin.workspace.security;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 后台 Agent 资源的可信登录主体入口。
 *
 * <p>Agent 状态与长期记忆都按 {@link AgentInvocationIdentity} 分区。模型调用路径原先由配额拦截器
 * 顺带写入身份，而历史列表、历史消息与记忆管理不消耗配额，因此没有经过该拦截器，造成写入使用
 * 主体分区、读取却退回旧的共享分区。本拦截器只负责身份，不判定或记录额度。</p>
 *
 * <p>线程由 Tomcat 复用，请求完成必须清理；异步 SSE 在控制器返回后还会切换处理线程，因此同步段
 * 结束时也要清理请求线程里的值（Agent 链路所需身份已在返回前写入 Reactor Context）。未登录请求
 * 同样先清理可能残留的旧值，实际鉴权仍由 Sa-Token 权限注解负责。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class AdminAgentIdentityInterceptor implements AsyncHandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AgentInvocationIdentityContext.clear();
        if (!StpUtil.isLogin()) {
            return true;
        }
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(
            tenantOf(), QuotaSubjectType.ADMIN_USER, StpUtil.getLoginIdAsString(), true)
            .withChannel(AgentInvocationIdentity.CHANNEL_ADMIN));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AgentInvocationIdentityContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        AgentInvocationIdentityContext.clear();
    }

    /** 登录态里的租户；单租户模式或登录态未带租户时统一使用默认租户。 */
    private static String tenantOf() {
        String tenantId = TenantSession.effectiveTenant();
        return tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
    }
}
