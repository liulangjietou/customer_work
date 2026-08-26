package com.richard.fyoung.customeradmin.billing.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.billing.jdbc.QuotaGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端库配额门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——配额表与内容风控三表在同一个客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。连接池另建（各自 3 连接）而不是共用：
 * 池子共用会让后台配额操作与词库维护互相排队，两者都是低频操作，多一个小池的代价可以忽略。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class QuotaGatewayProvider {

    private final CustomerWorkFacade<QuotaGateway> facade;

    public QuotaGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("tenant-quota-pool", properties, tenantPlugins)
            .mapperClasses(QuotaGatewayFactory.MAPPER_CLASSES)
            .mapperXml(QuotaGatewayFactory.MAPPER_XML_LOCATIONS)
            .readOnly(false)
            .error("QUOTA-DS-UNAVAILABLE", "客服端库不可达（租户配额存放于此）")
            .build(QuotaGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public QuotaGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
