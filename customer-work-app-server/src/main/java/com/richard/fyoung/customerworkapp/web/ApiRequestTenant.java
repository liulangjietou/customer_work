package com.richard.fyoung.customerworkapp.web;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.ApiKeyPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/** 运营 API 的请求租户边界：使用已鉴权凭据，只有明确的本地匿名模式可进入默认租户。 */
@Component
public final class ApiRequestTenant {
    private final CustomerWorkProperties properties;

    public ApiRequestTenant(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    /** 调度前冻结可信租户；业务执行方用 TenantContext.callWith 恢复，不读取请求参数或线程残留值。 */
    public String require(ServerWebExchange exchange) {
        ApiKeyPrincipal principal = exchange.getAttribute(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE);
        if (principal != null) {
            return TenantContext.canonicalizeTenantId(principal.tenantId());
        }
        if (isAnonymousLocalMode(exchange)) {
            return TenantContext.DEFAULT;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key identity is required");
    }

    /** 只有无可信主体且两个开关都关闭时，入口才可保留明确的匿名兼容行为。 */
    public boolean isAnonymousLocalMode(ServerWebExchange exchange) {
        return exchange.getAttribute(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE) == null
            && !properties.getSecurity().getAuth().isEnabled()
            && !properties.getTenant().isEnabled();
    }
}
