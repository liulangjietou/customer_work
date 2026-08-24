package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessDecision;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessGuard;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * API Key 鉴权过滤器（接入层安全 + 服务端接入方的租户身份来源）。
 *
 * <p>校验请求头中的 API Key 是否合法；健康检查与 Actuator 端点放行。默认关闭，
 * 生产开启 {@code customer-work.security.auth.enabled=true} 并配置结构化 {@code credentials} 后强制鉴权。</p>
 *
 * <p>多租户开启时，结构化凭据的 {@code tenantId} 同时承担租户身份，
 * 鉴权通过即把对应租户写入下游上下文。Key 是接入方唯一不可伪造的凭据，
 * 因此也是唯一可信的租户线索——请求头里带的租户参数一概不采信。</p>
 * @author owlzhangfq@gmail.com
 */
// 仅响应式栈装配：本类是 WebFlux 的 WebFilter，在 Servlet 栈（customer-admin-server）下
// 既不会生效也不该存在。没有这个条件时，下游 Servlet 模块只能整体 exclude starter 的入口
// 自动装配来躲开它，代价是全部域装配一并让位、几十个 Bean 要手工重装。
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthWebFilter implements WebFilter {

    private final CustomerWorkProperties properties;
    private final TenantAccessGuard tenantAccessGuard;
    private final ApiKeyCredentialResolver credentialResolver;

    public ApiKeyAuthWebFilter(CustomerWorkProperties properties) {
        this(properties, null, Clock.systemUTC());
    }

    @Autowired
    public ApiKeyAuthWebFilter(CustomerWorkProperties properties, TenantAccessGuard tenantAccessGuard) {
        this(properties, tenantAccessGuard, Clock.systemUTC());
    }

    ApiKeyAuthWebFilter(CustomerWorkProperties properties, TenantAccessGuard tenantAccessGuard, Clock clock) {
        this.properties = properties;
        this.tenantAccessGuard = tenantAccessGuard;
        this.credentialResolver = new ApiKeyCredentialResolver(clock);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        SecurityProperties.Auth auth = properties.getSecurity().getAuth();
        if (!auth.isEnabled()
            || CustomerSecurityPaths.bypassesApiKey(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String secret = exchange.getRequest().getHeaders().getFirst(auth.getHeaderName());
        String keyId = exchange.getRequest().getHeaders().getFirst(auth.getKeyIdHeaderName());
        ApiKeyCredentialResolver.Resolution resolution = credentialResolver.resolve(keyId, secret,
            exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value(), auth);
        if (resolution.status() == ApiKeyCredentialResolver.Status.SCOPE_DENIED) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        if (resolution.status() == ApiKeyCredentialResolver.Status.ALLOWED) {
            ApiKeyPrincipal principal = resolution.principal();
            TenantAccessDecision decision = checkTenantAccess(principal.tenantId());
            if (!decision.isAllowed()) {
                return AuthResponses.tenantAccessDenied(exchange, decision);
            }
            return chainWithTenant(exchange, chain, principal, decision.accessEpoch());
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private TenantAccessDecision checkTenantAccess(String tenantId) {
        return tenantAccessGuard == null
            ? TenantAccessDecision.allowed(0L)
            : tenantAccessGuard.check(tenantId, null, false);
    }

    /**
     * 把租户放进 Reactor Context，并在本过滤器所在线程同步设置 ThreadLocal。
     *
     * <p>两者都要：Reactor Context 是跨线程边界的权威载体（配合
     * {@code Hooks.enableAutomaticContextPropagation()} 在切到 boundedElastic 时还原 ThreadLocal）；
     * 而同步的 MyBatis 拦截器只认 ThreadLocal，若下游恰好没发生线程切换，就靠这里直接设的这一份。</p>
     */
    private Mono<Void> chainWithTenant(ServerWebExchange exchange, WebFilterChain chain,
                                       ApiKeyPrincipal principal, long accessEpoch) {
        String canonicalTenant = TenantContext.canonicalizeTenantId(principal.tenantId());
        QuotaSubject subject = new QuotaSubject(
            com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType.API_KEY, principal.keyId());
        AgentInvocationIdentity identity = new AgentInvocationIdentity(
            canonicalTenant, subject.type(), subject.id(), true, accessEpoch)
            .withChannel(AgentInvocationIdentity.CHANNEL_API);
        exchange.getAttributes().put(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE, principal);
        return Mono.defer(() -> TenantContext.callWith(canonicalTenant,
                () -> QuotaSubjectContext.callWith(subject,
                    () -> AgentInvocationIdentityContext.callWith(identity, () -> chain.filter(exchange)))))
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, canonicalTenant)
                .put(QuotaSubjectContextThreadLocalAccessor.KEY, subject)
                .put(AgentInvocationIdentityContextThreadLocalAccessor.KEY, identity));
    }

}
