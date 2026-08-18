package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 终端用户 JWT 鉴权过滤器：覆盖所有浏览器用户 HTTP 端点。
 *
 * <p>从 {@code Authorization: Bearer <token>} 解析登录态令牌，验签失败直接 401 JSON（不进业务链路）；
 * 成功则把 {@link UserPrincipal} 放入 exchange 属性（键 {@link #PRINCIPAL_ATTR}）供控制器取用。</p>
 *
 * <p>浏览器路径由本过滤器独占鉴权，不再要求浏览器携带服务端 API Key。若上游组件已经建立租户上下文，
 * 则必须与 JWT 中的租户严格一致，不能按过滤器顺序静默覆盖。</p>
 *
 * <p>本类为纯 {@link WebFilter}（不加 {@code @Component}）：由接入方以 {@code @Bean} 显式注册——避免被
 * {@code @WebFluxTest} 切片按 WebFilter 类型自动纳入、却因切片未提供其依赖（{@link UserJwtService}）而加载失败。
 * 切片测试需要它时 {@code @Import} 即可。</p>
 * @author owlzhangfq@gmail.com
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UserAuthWebFilter implements WebFilter {

    /** 用户主体在 exchange 属性中的键。 */
    public static final String PRINCIPAL_ATTR = "cw.user.principal";

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserJwtService jwtService;

    public UserAuthWebFilter(UserJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!CustomerSecurityPaths.requiresUserJwt(path)) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return AuthResponses.unauthorized(exchange, "missing bearer token");
        }
        Optional<UserPrincipal> principal = jwtService.verify(header.substring(BEARER_PREFIX.length()));
        if (principal.isEmpty()) {
            return AuthResponses.unauthorized(exchange, "invalid or expired token");
        }
        String tenantId = principal.get().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return AuthResponses.unauthorized(exchange, "token tenant is missing");
        }
        if (TenantContext.isPresent() && !tenantId.equals(TenantContext.get())) {
            return AuthResponses.forbidden(exchange, "credential tenant mismatch");
        }
        exchange.getAttributes().put(PRINCIPAL_ATTR, principal.get());
        return chainWithTenant(exchange, chain, tenantId);
    }

    /**
     * 把令牌里的租户写入下游上下文。
     *
     * <p>Reactor Context 与 ThreadLocal 都写，理由同 {@code ApiKeyAuthWebFilter}：
     * 前者跨线程边界还原，后者供未发生线程切换时的同步持久层读取。</p>
     */
    private Mono<Void> chainWithTenant(ServerWebExchange exchange, WebFilterChain chain, String tenantId) {
        return Mono.defer(() -> TenantContext.callWith(tenantId, () -> chain.filter(exchange)))
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, tenantId));
    }
}
