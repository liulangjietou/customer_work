package com.richard.fyoung.customeradmin.billing.gateway;

import com.richard.fyoung.customeradmin.billing.mapper.CustomerUsageFactMapper;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客服端模型用量事实门面。复用统一客服端库连接参数，惰性建连，客服库不可达不阻断 Admin 启动。
 */
@Component
public class CustomerUsageFactGatewayProvider {

    private final CustomerWorkFacade<CustomerUsageFactGateway> facade;

    public CustomerUsageFactGatewayProvider(ContentGuardProperties properties,
                                            AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("billing-usage-fact-pool", properties, tenantPlugins)
            .mapperClasses(List.of(CustomerUsageFactMapper.class))
            .maxPoolSize(2)
            .readOnly(false)
            .error("BILLING-USAGE-DS-UNAVAILABLE", "客服端库不可达（模型调用金额事实存放于此）")
            .build(gateway -> new CustomerUsageFactGateway(
                gateway.getMapper(CustomerUsageFactMapper.class)));
    }

    public CustomerUsageFactGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
