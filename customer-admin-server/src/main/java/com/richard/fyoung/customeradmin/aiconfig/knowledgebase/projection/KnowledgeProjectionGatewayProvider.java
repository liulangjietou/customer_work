package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 知识投影门面：惰性建池、探测、首次访问前执行客服端 Flyway、库不可达转业务异常、销毁关池。
 *
 * <p>复用 {@code admin.content-guard.*} 的连接属性——与既有的 6 个门面同一套配置来源，
 * 不为一个新门面再引入一组连接参数。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeProjectionGatewayProvider {

    private final CustomerWorkFacade<KnowledgeProjectionGateway> facade;

    public KnowledgeProjectionGatewayProvider(CustomerWorkDbProperties properties,
                                              AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("knowledge-projection-pool", properties, tenantPlugins)
            .mapperClasses(KnowledgeProjectionGatewayFactory.MAPPER_CLASSES)
            .mapperXml(KnowledgeProjectionGatewayFactory.MAPPER_XML_LOCATIONS)
            .readOnly(false)
            .error("KNOWLEDGE-PROJECTION-DS-UNAVAILABLE", "客服端库不可达（受管知识库分片存放于此）")
            .build(KnowledgeProjectionGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public KnowledgeProjectionGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
