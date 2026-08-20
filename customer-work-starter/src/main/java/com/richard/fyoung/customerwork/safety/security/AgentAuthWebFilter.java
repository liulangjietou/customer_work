package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential.AgentIdentity;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 坐席鉴权过滤器：覆盖 {@code /api/customer/agent/**} 与旧人机切换端点
 * {@code /api/customer/handoffs/**}。
 *
 * <p>从 {@code X-Agent-Token} 头解析 HMAC 令牌，经 {@link AgentAccessCredential#verify} 校验签名与有效期，
 * 失败 401 JSON；成功把 agentId 放入 exchange 属性（键 {@link #AGENT_ID_ATTR}）供控制器取用——坐席身份
 * 由服务端凭 token 解析而非客户端自报，避免冒充。</p>
 *
 * <p>本类为纯 {@link WebFilter}（不加 {@code @Component}），由接入方以 {@code @Bean} 显式注册，理由同
 * {@link UserAuthWebFilter}。</p>
 * @author owlzhangfq@gmail.com
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AgentAuthWebFilter implements WebFilter {

    /** 坐席 ID 在 exchange 属性中的键。 */
    public static final String AGENT_ID_ATTR = "cw.agent.id";

    private final CustomerWorkProperties properties;

    public AgentAuthWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!CustomerSecurityPaths.requiresAgentToken(path)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(HttpAuthConstants.AGENT_TOKEN_HEADER);
        Optional<AgentIdentity> identity = AgentAccessCredential.verifyIdentity(
            token, properties.getAgentAccess().getSecret(), System.currentTimeMillis());
        if (identity.isEmpty()) {
            return AuthResponses.unauthorized(exchange, "invalid or expired agent token");
        }
        AgentIdentity authenticated = identity.get();
        exchange.getAttributes().put(AGENT_ID_ATTR, authenticated.agentId());
        if (!properties.getTenant().isEnabled()) {
            return chain.filter(exchange);
        }
        if (authenticated.tenantId() == null || authenticated.tenantId().isBlank()) {
            return AuthResponses.unauthorized(exchange, "agent token tenant is missing");
        }
        if (TenantContext.isPresent() && !authenticated.tenantId().equals(TenantContext.get())) {
            return AuthResponses.forbidden(exchange, "credential tenant mismatch");
        }
        return chainWithTenant(exchange, chain, authenticated.tenantId());
    }

    private Mono<Void> chainWithTenant(ServerWebExchange exchange, WebFilterChain chain, String tenantId) {
        return Mono.defer(() -> TenantContext.callWith(tenantId, () -> chain.filter(exchange)))
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }
}
