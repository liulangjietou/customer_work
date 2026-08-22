package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallSegmentMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.NacosRuntimeConfigService;
import com.richard.fyoung.customerwork.capability.eval.EvalArtifactVersionProvider;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体调用日志域装配：按 {@code customer-work.call-log.store-mode} 选择存储实现并装配 Sink 与查询服务。
 *
 * <p>默认 {@code memory}；{@code jdbc} 落地为 {@link MybatisAgentCallLogStore}，Mapper 由独立的
 * {@code CustomerWorkPersistenceConfig}（MyBatis-Plus 环境）统一装配，此处经 {@link ObjectProvider}
 * 惰性取用。Store / Sink / Service 三个 Bean 均 {@code @ConditionalOnMissingBean}，下游可覆盖。
 * 采集中间件 {@code AgentCallTimingMiddleware} 与 {@code ToolKindRegistry} 走 {@code @Component} 扫描装配。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AgentCallLogConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentCallLogConfig.class);

    @Bean
    @ConditionalOnMissingBean(AgentCallLogStore.class)
    public AgentCallLogStore agentCallLogStore(CustomerWorkProperties properties,
                                               ObjectProvider<AgentCallLogMapper> callLogMapperProvider,
                                               ObjectProvider<AgentCallSegmentMapper> segmentMapperProvider) {
        String mode = properties.getCallLog().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("agent call log store: jdbc (MyBatis-Plus, tables=cw_agent_call_log/cw_agent_call_segment)");
            return new MybatisAgentCallLogStore(callLogMapperProvider.getObject(), segmentMapperProvider.getObject());
        }
        log.info("agent call log store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryAgentCallLogStore();
    }

    @Bean
    @ConditionalOnMissingBean(AgentCallRecordSink.class)
    public AgentCallRecordSink agentCallRecordSink(AgentCallLogStore agentCallLogStore) {
        return new StoreAgentCallRecordSink(agentCallLogStore);
    }

    @Bean
    @ConditionalOnMissingBean(AgentCallLogService.class)
    public AgentCallLogService agentCallLogService(AgentCallLogStore agentCallLogStore) {
        return new AgentCallLogService(agentCallLogStore);
    }

    /** 在线调用与离线评测复用同一套制品版本口径，并补充当前已应用的发布修订。 */
    @Bean
    @ConditionalOnMissingBean(AgentCallLineageProvider.class)
    public AgentCallLineageProvider agentCallLineageProvider(
        ObjectProvider<PromptVersionTracker> promptTrackerProvider,
        ObjectProvider<EvalArtifactVersionProvider> artifactVersionProvider,
        ObjectProvider<NacosRuntimeConfigService> runtimeConfigServiceProvider) {
        return new ConfigurationAgentCallLineageProvider(
            promptTrackerProvider, artifactVersionProvider, runtimeConfigServiceProvider);
    }
}
