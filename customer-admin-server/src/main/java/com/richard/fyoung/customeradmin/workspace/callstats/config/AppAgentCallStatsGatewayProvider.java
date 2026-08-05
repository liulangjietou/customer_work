package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customerwork.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AppAgentCallStatsGatewayProvider.class);

    private static final String POOL_NAME = "agent-call-stats-app-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final AgentCallStatsAppProperties properties;
    private final CrossDbGatewayProvider<AgentCallStatsGateway> delegate;

    public AppAgentCallStatsGatewayProvider(AgentCallStatsAppProperties properties) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            AgentCallStatsGatewayFactory.MAPPER_CLASSES,
            AgentCallStatsGatewayFactory.MAPPER_XML_LOCATIONS,
            AgentCallStatsGatewayFactory::build);
    }

    /** 取 APP 门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public AgentCallStatsGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("agent call stats APP datasource unavailable, code={}, url={}",
                "CALLSTATS-APP-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端调用日志库不可达：" + e.rootMessage());
        }
    }

    private CrossDbConnectionSettings connectionSettings() {
        return CrossDbConnectionSettings.builder(POOL_NAME, properties.jdbcUrl())
            .credentials(properties.getUsername(), properties.getPassword())
            .maximumPoolSize(MAX_POOL_SIZE)
            .readOnly(true)
            .build();
    }

    @PreDestroy
    public void close() {
        delegate.close();
    }
}
