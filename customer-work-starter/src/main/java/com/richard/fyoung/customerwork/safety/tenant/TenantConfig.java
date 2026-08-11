package com.richard.fyoung.customerwork.safety.tenant;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.micrometer.context.ContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import jakarta.annotation.PostConstruct;

/**
 * 多租户上下文传播装配（仅 {@code customer-work.tenant.enabled=true} 时生效）。
 *
 * <p>做两件事：把 {@link TenantContextThreadLocalAccessor} 注册进 {@link ContextRegistry}，
 * 并开启 Reactor 自动上下文传播。二者缺一不可——只注册不开 Hook，跨线程照样丢租户。</p>
 *
 * <p><b>Hook 是 JVM 全局的</b>，会给所有 reactive 链的线程边界加上 ThreadLocal 存取开销，
 * 因此绑定在多租户开关上：单租户部署不装配本类，运行时行为与升级前完全一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "customer-work.tenant", name = "enabled", havingValue = "true")
public class TenantConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantConfig.class);

    private final CustomerWorkProperties properties;

    public TenantConfig(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void setUpContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TenantContextThreadLocalAccessor());
        if (properties.getTenant().isAutoContextPropagation()) {
            Hooks.enableAutomaticContextPropagation();
            log.info("tenant context propagation enabled (reactor automatic propagation on)");
            return;
        }
        // 关掉自动传播意味着调用方自己保证跨线程边界的租户传递，仅供宿主已自建传播机制时使用
        log.info("tenant context accessor registered, automatic propagation disabled by configuration");
    }
}
