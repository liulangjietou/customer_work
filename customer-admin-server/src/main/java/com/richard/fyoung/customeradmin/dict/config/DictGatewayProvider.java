package com.richard.fyoung.customeradmin.dict.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 客服端库字典门面的惰性提供者。
 *
 * <p>惰性建连、探测、缓存、失败不缓存这套通用语义由 starter 的 {@link CrossDbGatewayProvider} 承担；
 * 本类只给出连接参数，并把"库不可达"翻译成 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常，
 * <b>绝不在 admin 启动期触碰该库</b>。</p>
 *
 * <p>连接池<b>可写</b>：字典管理页是这份数据唯一的维护入口，写是它的本职。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@EnableConfigurationProperties(DictProperties.class)
public class DictGatewayProvider {

    private final CustomerWorkFacade<DictGateway> facade;

    public DictGatewayProvider(DictProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("dict-pool", properties, tenantPlugins)
            .mapperClasses(DictGatewayFactory.MAPPER_CLASSES)
            .mapperXml(DictGatewayFactory.MAPPER_XML_LOCATIONS)
            .maxPoolSize(2)
            .readOnly(false)
            .error("DICT-DS-UNAVAILABLE", "客服端库不可达（字典数据存放于此）")
            .build(DictGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public DictGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
