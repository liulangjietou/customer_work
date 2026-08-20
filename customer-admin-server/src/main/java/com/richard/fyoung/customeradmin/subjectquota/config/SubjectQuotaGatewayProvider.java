package com.richard.fyoung.customeradmin.subjectquota.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.subjectquota.jdbc.SubjectQuotaGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端库主体配额门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——这些表与内容风控三表、租户配额同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SubjectQuotaGatewayProvider {

    private final CustomerWorkFacade<SubjectQuotaGateway> facade;

    public SubjectQuotaGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("subject-quota-pool", properties, tenantPlugins)
            .mapperClasses(SubjectQuotaGatewayFactory.MAPPER_CLASSES)
            .mapperXml(SubjectQuotaGatewayFactory.MAPPER_XML_LOCATIONS)
            .error("SQUOTA-DS-UNAVAILABLE", "客服端库不可达（主体配额等级存放于此）")
            .build(SubjectQuotaGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public SubjectQuotaGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
