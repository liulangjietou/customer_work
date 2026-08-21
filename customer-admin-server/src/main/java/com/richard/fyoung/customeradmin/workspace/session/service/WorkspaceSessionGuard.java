package com.richard.fyoung.customeradmin.workspace.session.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.session.entity.WorkspaceSession;
import com.richard.fyoung.customeradmin.workspace.session.mapper.WorkspaceSessionMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 工作区会话根资源的租户与用户归属守卫。
 *
 * <p>归属校验受数据范围约束：{@code SELF} 只放行自己认领的会话，{@code TENANT}/{@code ALL}
 * 放行本租户内任意会话——租户管理员与控制面用户要能看当前视角内全量，这是数据权限的既定语义。
 * 认领动作本身不受影响，任何范围下都记录真实发起人。</p>
 */
@Service
public class WorkspaceSessionGuard {

    private final WorkspaceSessionMapper mapper;
    private final AdminTenantProperties tenantProperties;

    public WorkspaceSessionGuard(WorkspaceSessionMapper mapper, AdminTenantProperties tenantProperties) {
        this.mapper = mapper;
        this.tenantProperties = tenantProperties;
    }

    /** 首次流式对话原子认领会话；已有归属时只允许原所有者继续使用。 */
    public void claimOrRequire(String agentCode, String sessionId, Long userId) {
        String tenantId = currentTenant();
        String safeSession = WorkspaceRuntimeScope.safeSession(sessionId);
        WorkspaceSession existing = mapper.findByResource(tenantId, agentCode, safeSession);
        if (existing != null) {
            requireOwner(existing, userId);
            return;
        }
        if (mapper.countState(WorkspaceRuntimeScope.agent(agentCode), safeSession) > 0) {
            throw notFound();
        }
        mapper.insertIgnore(tenantId, agentCode, safeSession, userId, System.currentTimeMillis());
        requireOwned(tenantId, agentCode, safeSession, userId);
    }

    /** 读取或变更已有会话前校验归属，未知与跨用户统一按资源不存在处理。 */
    public void requireOwned(String agentCode, String sessionId, Long userId) {
        requireOwned(currentTenant(), agentCode, WorkspaceRuntimeScope.safeSession(sessionId), userId);
    }

    public boolean isOwned(String agentCode, String sessionId, Long userId) {
        WorkspaceSession session = mapper.findByResource(
            currentTenant(), agentCode, WorkspaceRuntimeScope.safeSession(sessionId));
        if (session == null) {
            return false;
        }
        return DataScopeContext.relaxedBeyondSelf() || userId.equals(session.getOwnerUserId());
    }

    private void requireOwned(String tenantId, String agentCode, String sessionId, Long userId) {
        WorkspaceSession session = mapper.findByResource(tenantId, agentCode, sessionId);
        requireOwner(session, userId);
    }

    private void requireOwner(WorkspaceSession session, Long userId) {
        if (session == null) {
            throw notFound();
        }
        // 只有明确处于本租户及以上范围时才放行他人会话；缺上下文一律按原样严格校验
        if (DataScopeContext.relaxedBeyondSelf()) {
            return;
        }
        if (!userId.equals(session.getOwnerUserId())) {
            throw notFound();
        }
    }

    private BizException notFound() {
        return new BizException(ResultCode.RESOURCE_NOT_FOUND, "会话不存在");
    }

    private String currentTenant() {
        return tenantProperties.isEnabled() ? TenantContext.require() : TenantContext.DEFAULT;
    }
}
