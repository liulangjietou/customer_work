package com.richard.fyoung.customeradmin.ops.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 运营闭环门面的惰性提供者。
 *
 * <p>连接参数复用 {@link CustomerWorkDbProperties}——这几张表与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。</p>
 *
 * <p>惰性建连，绝不在 admin 启动期触碰客服端库：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class OpsGatewayProvider {

    private final CustomerWorkFacade<OpsGateway> facade;

    public OpsGatewayProvider(CustomerWorkDbProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("ops-closed-loop-pool", properties, tenantPlugins)
            .mapperClasses(OpsGatewayFactory.MAPPER_CLASSES)
            .mapperXml(OpsGatewayFactory.MAPPER_XML_LOCATIONS)
            .maxPoolSize(4)
            .readOnly(false)
            .error("OPS-DS-UNAVAILABLE", "客服端库不可达（运营闭环数据存放于此）")
            .build(OpsGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public OpsGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
