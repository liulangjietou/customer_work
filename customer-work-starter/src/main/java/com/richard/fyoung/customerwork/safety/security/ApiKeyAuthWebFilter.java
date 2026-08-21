package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * API Key 鉴权过滤器（接入层安全 + 服务端接入方的租户身份来源）。
 *
 * <p>校验请求头中的 API Key 是否合法；健康检查与 Actuator 端点放行。默认关闭，
 * 生产开启 {@code customer-work.security.auth.enabled=true} 并配置 {@code api-keys} 后强制鉴权。</p>
 *
 * <p>多租户开启时，Key 同时承担租户身份：{@code tenant-keys} 里配置 Key→租户映射，
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

    public ApiKeyAuthWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        SecurityProperties.Auth auth = properties.getSecurity().getAuth();
        if (!auth.isEnabled()
            || CustomerSecurityPaths.bypassesApiKey(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        String provided = exchange.getRequest().getHeaders().getFirst(auth.getHeaderName());
        String tenantId = resolveTenant(provided, auth);
        if (TenantContext.isValidTenantId(tenantId)) {
            return chainWithTenant(exchange, chain, tenantId);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * 校验 Key 并解析其租户，非法返回 {@code null}。
     *
     * <p>常量时间校验：用 {@link MessageDigest#isEqual} 比对，且遍历所有配置的 Key 不做短路返回，
     * 避免按"命中位置 / 前缀匹配长度"产生可被测量的耗时差异（时序侧信道）。
     * 加入租户映射后同样逐条走完——命中后仅记录结果，不提前结束循环。</p>
     */
    private String resolveTenant(String provided, SecurityProperties.Auth auth) {
        if (provided == null) {
            return null;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        String matchedTenant = null;

        Map<String, String> tenantKeys = auth.getTenantKeys();
        if (tenantKeys != null) {
            for (Map.Entry<String, String> entry : tenantKeys.entrySet()) {
                if (constantTimeEquals(providedBytes, entry.getKey())) {
                    matchedTenant = entry.getValue();
                }
            }
        }

        List<String> validKeys = auth.getApiKeys();
        if (validKeys != null) {
            for (String key : validKeys) {
                if (constantTimeEquals(providedBytes, key)) {
                    // 未配租户的 Key 归默认租户：单租户部署升级后行为不变
                    if (matchedTenant == null) {
                        matchedTenant = TenantContext.DEFAULT;
                    }
                }
            }
        }
        return matchedTenant;
    }

    private boolean constantTimeEquals(byte[] providedBytes, String candidate) {
        return candidate != null
            && MessageDigest.isEqual(providedBytes, candidate.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把租户放进 Reactor Context，并在本过滤器所在线程同步设置 ThreadLocal。
     *
     * <p>两者都要：Reactor Context 是跨线程边界的权威载体（配合
     * {@code Hooks.enableAutomaticContextPropagation()} 在切到 boundedElastic 时还原 ThreadLocal）；
     * 而同步的 MyBatis 拦截器只认 ThreadLocal，若下游恰好没发生线程切换，就靠这里直接设的这一份。</p>
     */
    private Mono<Void> chainWithTenant(ServerWebExchange exchange, WebFilterChain chain, String tenantId) {
        String canonicalTenant = TenantContext.canonicalizeTenantId(tenantId);
        return Mono.defer(() -> TenantContext.callWith(canonicalTenant, () -> chain.filter(exchange)))
            .contextWrite(ctx -> ctx.put(TenantContextThreadLocalAccessor.KEY, canonicalTenant));
    }

}
