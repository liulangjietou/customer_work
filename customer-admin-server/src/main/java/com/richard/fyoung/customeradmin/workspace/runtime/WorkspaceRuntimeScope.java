package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.util.StringUtils;

/** 工作区非 MyBatis 资源的统一租户作用域键，单租户模式保持历史键格式不变。 */
public final class WorkspaceRuntimeScope {

    private static final String TENANT_SEPARATOR = "::";
    private static final String DEFAULT_SESSION = "default";

    private WorkspaceRuntimeScope() {
    }

    /** Agent 实例、状态 userId、缓存与工作目录共用同一租户作用域。 */
    public static String agent(String agentCode) {
        return TenantContext.isPresent()
            ? TenantContext.get() + TENANT_SEPARATOR + agentCode
            : agentCode;
    }

    public static String session(String agentCode, String sessionId) {
        return agent(agentCode) + ":" + safeSession(sessionId);
    }

    public static String safeSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION;
    }

    /** 从框架状态用的租户作用域 userId 还原业务 Agent 编码。 */
    public static String rawAgent(String scopedAgentCode) {
        if (!StringUtils.hasText(scopedAgentCode)) {
            return scopedAgentCode;
        }
        int separator = scopedAgentCode.indexOf(TENANT_SEPARATOR);
        return separator < 0 ? scopedAgentCode : scopedAgentCode.substring(separator + TENANT_SEPARATOR.length());
    }
}
