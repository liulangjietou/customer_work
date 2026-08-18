package com.richard.fyoung.customeradmin.ticket.config;

import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 客服工单坐席服务（8080）HTTP 客户端装配。
 *
 * <p>用 Spring 6.1 内置的 {@link RestClient}；请求工厂设置连接/读取超时（防止 8080 卡死拖垮
 * admin-server 线程）。拦截器在每次请求现签一枚短有效期 {@code X-Agent-Token}
 * （{@link AgentAccessCredential#sign}，agentId=当前登录坐席登录名），8080 侧凭同一密钥校验坐席身份，
 * 避免"客户端自报 agentId 即被信任"的越权风险。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class CustomerWorkClientConfig {

    /** 坐席令牌请求头名（与 8080 契约一致）。 */
    private static final String AGENT_TOKEN_HEADER = "X-Agent-Token";

    /** 单次请求令牌有效期（毫秒）：只需覆盖一次 HTTP 往返，短有效期收紧被截获后的可用窗口。 */
    private static final long REQUEST_TOKEN_TTL_MS = 120_000L;

    @Bean
    public RestClient customerWorkRestClient(CustomerWorkClientProperties properties,
                                             CurrentAgentResolver currentAgentResolver) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());

        ClientHttpRequestInterceptor tokenInterceptor = (request, body, execution) -> {
            String agentId = currentAgentResolver.currentAgentId();
            long expiresAtMs = System.currentTimeMillis() + REQUEST_TOKEN_TTL_MS;
            String tenantId = TenantContext.get();
            String token = tenantId == null
                ? AgentAccessCredential.sign(agentId, expiresAtMs, properties.getAgentSecret())
                : AgentAccessCredential.sign(agentId, tenantId, expiresAtMs, properties.getAgentSecret());
            request.getHeaders().set(AGENT_TOKEN_HEADER, token);
            return execution.execute(request, body);
        };

        return RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(factory)
            .requestInterceptor(tokenInterceptor)
            .build();
    }
}
