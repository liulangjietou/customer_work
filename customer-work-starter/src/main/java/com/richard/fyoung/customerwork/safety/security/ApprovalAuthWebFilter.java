package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;
import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;

/**
 * 审批操作员身份鉴权过滤器（把关退款审批 approve/deny 的身份来源）。
 *
 * <p>只作用于资金放行的两个变更端点（{@code POST /api/customer/approvals/{id}/approve|deny}），
 * 不影响审批单查询端点（沿用通用 {@link ApiKeyAuthWebFilter}）。开启后，操作员身份由服务端按
 * {@code security.approval-auth.operators} 中的 token→姓名映射解析并写入
 * {@link #RESOLVED_OPERATOR_ATTR} 请求属性，供 Controller 读取——不再信任客户端自报的
 * {@code operator} 参数，堵住"任何人可冒充任意坐席放行退款"的身份伪造漏洞。</p>
 *
 * <p>关闭时（默认）整链路直接放行，{@link #RESOLVED_OPERATOR_ATTR} 不写入，Controller 退化为
 * 沿用旧行为（供本地开发/未启用鉴权环境使用；生产必须开启）。</p>
 * @author owlzhangfq@gmail.com
 */
// 仅响应式栈装配：本类是 WebFlux 的 WebFilter，在 Servlet 栈（customer-admin-server）下
// 既不会生效也不该存在。没有这个条件时，下游 Servlet 模块只能整体 exclude starter 的入口
// 自动装配来躲开它，代价是全部域装配一并让位、几十个 Bean 要手工重装。
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class ApprovalAuthWebFilter implements WebFilter {

    /** 解析出的操作员姓名写入的请求属性键，供 Controller 读取。 */
    public static final String RESOLVED_OPERATOR_ATTR = "customerwork.approval.resolvedOperator";

    private static final Pattern GUARDED_PATH =
        Pattern.compile("^/api/customer/approvals/[^/]+/(approve|deny)$");

    private final CustomerWorkProperties properties;

    public ApprovalAuthWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        SecurityProperties.ApprovalAuth cfg = properties.getSecurity().getApprovalAuth();
        if (!cfg.isEnabled() || !isGuarded(exchange)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(cfg.getHeaderName());
        String operator = token == null ? null : cfg.getOperators().get(token);
        if (operator == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        exchange.getAttributes().put(RESOLVED_OPERATOR_ATTR, operator);
        return chain.filter(exchange);
    }

    /** 仅拦截 POST 到 approve/deny 的两个资金放行端点。 */
    private boolean isGuarded(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.POST
            && GUARDED_PATH.matcher(exchange.getRequest().getPath().value()).matches();
    }
}
