package com.richard.fyoung.customeradmin.tenant;

import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/** admin 多租户开启时，为 SSE/异步智能体链路注册 Reactor 租户上下文传播。 */
@Configuration
@ConditionalOnProperty(prefix = "admin.tenant", name = "enabled", havingValue = "true")
public class AdminTenantContextPropagationConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantContextPropagationConfig.class);

    @PostConstruct
    public void setUp() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new TenantContextThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
        log.info("admin tenant context propagation enabled");
    }
}
