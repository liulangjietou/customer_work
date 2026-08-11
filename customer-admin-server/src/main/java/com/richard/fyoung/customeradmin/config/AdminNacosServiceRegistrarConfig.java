package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customerwork.infra.config.NacosRegistration;
import com.richard.fyoung.customerwork.infra.config.NacosServiceRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * admin-server 侧 Nacos 服务注册装配。
 *
 * <p>admin 排除了 starter 的自动装配，故在此显式接一个 starter 的 {@link NacosServiceRegistrar}（可复用普通类），
 * 复用 admin 现有的 {@code admin.runtime-publish.nacos.*} 连接配置。装配条件为两级：
 * 类级 {@code enabled=true}（Nacos 总开关）+ 方法级 {@code register-enabled≠false}（服务注册独立子开关，
 * 默认 true）——保持「启用 nacos 即自动注册」的默认，同时支持「只发布运行时配置、不注册服务」拆分场景。
 * 服务名固定 {@code customer-admin-server}，供网关 {@code lb://} 发现路由。</p>
 *
 * <p>Fail-safe 由 {@link NacosServiceRegistrar} 内部兜底（Nacos 不可达不阻断启动）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "admin.runtime-publish.nacos", name = "enabled", havingValue = "true")
public class AdminNacosServiceRegistrarConfig {

    /** admin 服务名（网关按此名路由）。 */
    private static final String SERVICE_NAME = "customer-admin-server";
    private static final String SCHEME_HTTP = "http";

    @Bean
    @ConditionalOnProperty(prefix = "admin.runtime-publish.nacos", name = "register-enabled",
        havingValue = "true", matchIfMissing = true)
    public NacosServiceRegistrar adminNacosServiceRegistrar(
            RuntimePublishProperties properties,
            @Value("${server.port:8082}") int serverPort) {
        RuntimePublishProperties.Nacos cfg = properties.getNacos();
        NacosRegistration registration = NacosRegistration.builder()
            .serverAddr(cfg.getServerAddr())
            .namespace(cfg.getNamespace())
            .group(cfg.getGroup())
            .username(cfg.getUsername())
            .password(cfg.getPassword())
            .serviceName(SERVICE_NAME)
            .ip(cfg.getInstanceIp())
            .port(serverPort)
            .scheme(SCHEME_HTTP)
            .contextPath("")
            .build();
        return new NacosServiceRegistrar(registration);
    }
}
