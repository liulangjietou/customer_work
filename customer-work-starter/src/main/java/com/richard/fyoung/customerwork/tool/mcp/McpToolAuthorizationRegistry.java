package com.richard.fyoung.customerwork.tool.mcp;

import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具名到允许主体的运行时登记表。
 *
 * <p>后台按 {@code WorkspaceRuntimeScope.agent(agentCode)} 隔离；客服运行时一个进程只消费当前
 * dataId，使用固定作用域。作用域参与键计算，避免不同智能体恰好暴露同名工具时策略互相覆盖。</p>
 */
@Component
public class McpToolAuthorizationRegistry {

    public static final String CUSTOMER_RUNTIME_SCOPE = "customer-runtime";

    private final Map<ToolKey, Set<QuotaSubjectType>> policies = new ConcurrentHashMap<>();

    public void register(String scope, Collection<String> toolNames, Collection<String> allowedSubjectTypes) {
        Set<QuotaSubjectType> allowed = McpSubjectPolicy.parse(allowedSubjectTypes);
        if (toolNames == null) {
            return;
        }
        for (String toolName : toolNames) {
            if (toolName != null && !toolName.isBlank()) {
                policies.put(new ToolKey(normalizeScope(scope), toolName), allowed);
            }
        }
    }

    public void clearScope(String scope) {
        String normalized = normalizeScope(scope);
        policies.keySet().removeIf(key -> key.scope().equals(normalized));
    }

    public Set<QuotaSubjectType> policyFor(String scope, String toolName) {
        if (toolName == null) {
            return null;
        }
        return policies.get(new ToolKey(normalizeScope(scope), toolName));
    }

    private static String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? CUSTOMER_RUNTIME_SCOPE : scope;
    }

    private record ToolKey(String scope, String toolName) {
    }
}
