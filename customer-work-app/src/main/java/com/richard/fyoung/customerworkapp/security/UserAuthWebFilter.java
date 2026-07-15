package com.richard.fyoung.customerworkapp.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 终端用户 JWT 鉴权过滤器：只覆盖 {@code /api/customer/user/**}。
 *
 * <p>从 {@code Authorization: Bearer <token>} 解析登录态令牌，验签失败直接 401 JSON（不进业务链路）；
 * 成功则把 {@link UserPrincipal} 放入 exchange 属性（键 {@link #PRINCIPAL_ATTR}）供控制器取用。</p>
 *
 * <p>Order 排在 {@code ApiKeyAuthWebFilter}（{@code HIGHEST_PRECEDENCE + 10}）之后：API Key 是接入层
 * 基础设施闸门（默认关闭），用户登录态鉴权是其后的业务身份闸门，两者正交共存。</p>
 *
 * <p>由 {@code SecurityWebFilterConfig} 以 {@code @Bean} 注册（非 {@code @Component}）——避免被 {@code @WebFluxTest}
 * 切片自动纳入而在无 {@code UserJwtService} 的控制器切片里加载失败。</p>
 * @author owlzhangfq@gmail.com
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UserAuthWebFilter implements WebFilter {

    /** 用户主体在 exchange 属性中的键。 */
    public static final String PRINCIPAL_ATTR = "cw.user.principal";

    private static final String PATH_PREFIX = "/api/customer/user/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserJwtService jwtService;

    public UserAuthWebFilter(UserJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PATH_PREFIX)) {
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
        exchange.getAttributes().put(PRINCIPAL_ATTR, principal.get());
        return chain.filter(exchange);
    }
}
