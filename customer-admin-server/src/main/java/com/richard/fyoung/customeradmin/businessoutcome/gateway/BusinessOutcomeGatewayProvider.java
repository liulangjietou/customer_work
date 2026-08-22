package com.richard.fyoung.customeradmin.businessoutcome.gateway;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端业务结果只读门面，惰性连接，客服库不可用不影响 Admin 启动。
 */
@Component
public class BusinessOutcomeGatewayProvider {

    private final CustomerWorkFacade<BusinessOutcomeGateway> facade;

    public BusinessOutcomeGatewayProvider(ContentGuardProperties properties,
                                          AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("business-outcome-pool", properties, tenantPlugins)
            .mapperClasses(BusinessOutcomeGatewayFactory.MAPPER_CLASSES)
            .maxPoolSize(4)
            .error("BUSINESS-OUTCOME-DS-UNAVAILABLE", "客服端库不可达（业务结果事实存放于此）")
            .build(BusinessOutcomeGatewayFactory::build);
    }

    public BusinessOutcomeGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
