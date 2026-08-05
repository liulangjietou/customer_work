package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customerwork.calllog.AgentCallRecordSink;
import com.richard.fyoung.customerwork.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.calllog.StoreAgentCallRecordSink;
import com.richard.fyoung.customerwork.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 智能体调用耗时统计的存储与采集装配（admin 排除了 starter 自动装配，故这里显式 new 全部 starter 组件）。
 *
 * <p>ADMIN 门面用 admin 主数据源现场装配（{@link AgentCallStatsGatewayFactory}，专用 SqlSessionFactory，
 * 不污染主 MyBatis 环境）；采集侧把 {@link ToolKindRegistry} + {@link AgentCallTimingMiddleware} +
 * {@link AgentCallRecordSink}（落 ADMIN 门面）三件套装成 Bean，供 {@code AdminAgentInstanceFactory} 挂到
 * workspace 智能体链路上。APP 门面（客服端库只读）由 {@link AppAgentCallStatsGatewayProvider} 惰性提供，
 * 不在此装配。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(AgentCallStatsAppProperties.class)
public class AgentCallStatsStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentCallStatsStoreConfig.class);

    /** ADMIN 数据源门面：用 admin 主数据源，启动期只解析 XML/建工厂，不连库。 */
    @Bean
    public AgentCallStatsGateway adminAgentCallStatsGateway(DataSource dataSource) {
        log.info("agent call stats ADMIN gateway wired on primary datasource");
        return AgentCallStatsGatewayFactory.build(dataSource);
    }

    /** 工具名→类别登记表：供 AdminAgentInstanceFactory 装配 MCP/Skill 时登记，采集时 onActing 归类。 */
    @Bean
    public ToolKindRegistry agentCallToolKindRegistry() {
        return new ToolKindRegistry();
    }

    /** 采集落库 Sink（异步、单线程有界队列），指向 ADMIN 门面的 Store。DisposableBean，容器关闭优雅停池。 */
    @Bean
    public AgentCallRecordSink agentCallRecordSink(AgentCallStatsGateway adminAgentCallStatsGateway) {
        return new StoreAgentCallRecordSink(adminAgentCallStatsGateway.store());
    }

    /**
     * 分段耗时采集中间件：开关经 {@code customer-work.call-log.enabled}（默认开）绑定到配置，
     * 运维可通过配置/环境变量关闭采集；storeMode 在 admin 侧无意义（Store 由本类显式装配），不绑定。
     */
    @Bean
    public AgentCallTimingMiddleware agentCallTimingMiddleware(
            @Value("${customer-work.call-log.enabled:true}") boolean callLogEnabled,
            ToolKindRegistry agentCallToolKindRegistry,
            AgentCallRecordSink agentCallRecordSink,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getCallLog().setEnabled(callLogEnabled);
        // meterRegistry 可选注入：容器里没有 Micrometer 时中间件降级为只落库、不出 token 指标
        return new AgentCallTimingMiddleware(properties, agentCallToolKindRegistry,
            agentCallRecordSink, meterRegistryProvider);
    }
}
