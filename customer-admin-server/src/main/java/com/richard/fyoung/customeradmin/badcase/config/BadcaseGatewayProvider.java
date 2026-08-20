package com.richard.fyoung.customeradmin.badcase.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端库 badcase 回流服务的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——badcase 与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置（与配额、评测门面同一取舍）。</p>
 *
 * <p>惰性建连，绝不在 admin 启动期触碰客服端库。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class BadcaseGatewayProvider {

    private final CustomerWorkFacade<BadcaseService> facade;

    public BadcaseGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("badcase-pool", properties, tenantPlugins)
            .mapperClasses(BadcaseGatewayFactory.MAPPER_CLASSES)
            .mapperXml(BadcaseGatewayFactory.MAPPER_XML_LOCATIONS)
            .error("BADCASE-DS-UNAVAILABLE", "客服端库不可达（badcase 与回流目标存放于此）")
            .build(BadcaseGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public BadcaseService get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
