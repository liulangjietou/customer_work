package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkFacade;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * APP 数据源（客服端库 {@code agent_scope_customer_work}）调用统计门面的惰性提供者。
 *
 * <p>惰性建连、探测、缓存、失败不缓存这套通用语义由 starter 的 {@link CrossDbGatewayProvider} 承担；
 * 本类只给出连接参数，并把"库不可达"翻译成 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常，
 * 绝不在 admin 启动期触碰该库。</p>
 *
 * <p>连接池<b>只读</b>：这里只查客服端的调用日志，写入是 8080 那边的事。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AppAgentCallStatsGatewayProvider {

    private final CustomerWorkFacade<AgentCallStatsGateway> facade;

    public AppAgentCallStatsGatewayProvider(CustomerWorkDbProperties properties,
                                            AdminCrossDbTenantPlugins tenantPlugins) {
        this.facade = CustomerWorkFacade.builder("agent-call-stats-app-pool", properties, tenantPlugins)
            .mapperClasses(AgentCallStatsGatewayFactory.MAPPER_CLASSES)
            .mapperXml(AgentCallStatsGatewayFactory.MAPPER_XML_LOCATIONS)
            .readOnly(true)
            .error("CALLSTATS-APP-DS-UNAVAILABLE", "客服端调用日志库不可达")
            .build(AgentCallStatsGatewayFactory::build);
    }

    /** 取门面（惰性建连 + 探测 + 缓存）；库不可达抛带业务语义的异常。 */
    public AgentCallStatsGateway get() {
        return facade.get();
    }

    @PreDestroy
    public void close() {
        facade.close();
    }
}
