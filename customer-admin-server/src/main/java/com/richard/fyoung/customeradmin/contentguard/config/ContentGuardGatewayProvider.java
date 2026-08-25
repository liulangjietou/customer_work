package com.richard.fyoung.customeradmin.contentguard.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 客服端库内容风控门面的惰性提供者。
 *
 * <p>惰性建连、探测、缓存、失败不缓存这套通用语义由 starter 的 {@link CrossDbGatewayProvider} 承担；
 * 本类只做两件本域的事：给出连接参数，以及把"库不可达"翻译成
 * {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常——<b>绝不在 admin 启动期触碰该库</b>，
 * 后台不该因为客服端库没起来就启动不了。</p>
 *
 * <p>连接池<b>可写</b>（与 callstats 的只读池不同）：内容风控是后台维护词库与规则的地方，写是它的本职。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
// ContentGuardProperties 只有 @ConfigurationProperties 没有 @Component，这里是它进容器的唯一入口。
// 连接参数已迁到 admin.customer-work-db.*，本类保留的是敏感词那几个业务开关的注册职责。
@EnableConfigurationProperties(ContentGuardProperties.class)
public class ContentGuardGatewayProvider {

    private final CustomerWorkFacade<ContentGuardGateway> facade;

    public ContentGuardGatewayProvider(CustomerWorkDbProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("content-guard-pool", properties, tenantPlugins)
            .mapperClasses(ContentGuardGatewayFactory.MAPPER_CLASSES)
            .mapperXml(ContentGuardGatewayFactory.MAPPER_XML_LOCATIONS)
            .readOnly(false)
            .error("CONTENTGUARD-DS-UNAVAILABLE", "客服端库不可达（敏感词/限流规则存放于此）")
            .build(ContentGuardGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public ContentGuardGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
