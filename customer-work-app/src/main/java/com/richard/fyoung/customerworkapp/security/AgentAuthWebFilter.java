package com.richard.fyoung.customerworkapp.security;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.AgentAccessCredential;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 坐席鉴权过滤器：只覆盖 {@code /api/customer/agent/**}。
 *
 * <p>从 {@code X-Agent-Token} 头解析 HMAC 令牌，经 {@link AgentAccessCredential#verify} 校验签名与有效期，
 * 失败 401 JSON；成功把 agentId 放入 exchange 属性（键 {@link #AGENT_ID_ATTR}）供控制器取用——坐席身份
 * 由服务端凭 token 解析而非客户端自报，避免冒充。</p>
 *
 * <p>由 {@code SecurityWebFilterConfig} 以 {@code @Bean} 注册（非 {@code @Component}），理由同 {@code UserAuthWebFilter}。</p>
 * @author owlzhangfq@gmail.com
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AgentAuthWebFilter implements WebFilter {

    /** 坐席 ID 在 exchange 属性中的键。 */
    public static final String AGENT_ID_ATTR = "cw.agent.id";

    private static final String PATH_PREFIX = "/api/customer/agent/";
    private static final String TOKEN_HEADER = "X-Agent-Token";

    private final CustomerWorkProperties properties;

    public AgentAuthWebFilter(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getFirst(TOKEN_HEADER);
        Optional<String> agentId = AgentAccessCredential.verify(
            token, properties.getAgentAccess().getSecret(), System.currentTimeMillis());
        if (agentId.isEmpty()) {
            return AuthResponses.unauthorized(exchange, "invalid or expired agent token");
        }
        exchange.getAttributes().put(AGENT_ID_ATTR, agentId.get());
        return chain.filter(exchange);
    }
}
