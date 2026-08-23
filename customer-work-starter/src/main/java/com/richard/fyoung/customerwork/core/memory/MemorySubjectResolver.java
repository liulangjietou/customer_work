package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

/** 把入口会话解析为显式的长期记忆主体键。 */
@Component
public class MemorySubjectResolver {

    public static final String CUSTOMER_SERVICE_AGENT = "customer-service";
    /**
     * 只有接入层验签后写入的 USER 主体可以跨会话共享；其它入口按完整 SESSION 隔离。
     * 会话 ID 是客户端可控字符串，不能因为形如 {@code u<userId>:conv-*} 就把它当作身份凭据。
     */
    public MemorySubjectKey resolve(String sessionId, String agentId) {
        String tenantId = TenantContext.isPresent() ? TenantContext.require() : TenantContext.DEFAULT;
        QuotaSubject authenticatedSubject = QuotaSubjectContext.get();
        if (authenticatedSubject != null
            && authenticatedSubject.type() == QuotaSubjectType.USER
            && !QuotaSubject.UNKNOWN_ID.equals(authenticatedSubject.id())) {
            return new MemorySubjectKey(tenantId, MemorySubjectType.USER,
                authenticatedSubject.id(), agentId);
        }
        String subjectId = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId;
        return new MemorySubjectKey(tenantId, MemorySubjectType.SESSION, subjectId, agentId);
    }

    /** 用户隐私 API 使用已验签主体直接建键，不接收客户端自报 userId。 */
    public MemorySubjectKey user(String tenantId, String userId) {
        return new MemorySubjectKey(tenantId, MemorySubjectType.USER, userId, CUSTOMER_SERVICE_AGENT);
    }
}
