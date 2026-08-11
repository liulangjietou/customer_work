package com.richard.fyoung.customeradmin.contentguard.config;

import com.richard.fyoung.customeradmin.contentguard.runtime.GatewaySensitiveWordHitLogStore;
import com.richard.fyoung.customeradmin.contentguard.runtime.GatewaySensitiveWordStore;
import com.richard.fyoung.customeradmin.contentguard.runtime.SensitiveWordRefreshTask;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.middleware.SensitiveWordMiddleware;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.AsyncSensitiveWordHitSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitLogStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordRefresher;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.richard.fyoung.customerwork.infra.config.properties.Direction;
import com.richard.fyoung.customerwork.infra.config.properties.SensitiveWordProperties;

/**
 * admin workspace 智能体的敏感词过滤装配（admin 排除了 starter 自动装配，这里显式 new 全部组件，
 * 照 {@code AgentCallStatsStoreConfig} 的先例）。
 *
 * <p><b>整块由 {@code admin.content-guard.agent-filter-enabled=true} 门控</b>，默认关闭：
 * 关闭时一个 Bean 都不装配，admin 只提供词库的管理界面，不参与拦截，启动期也不会去碰客服端库。</p>
 *
 * <p>词表来源是客服端库那张 {@code cw_sensitive_word}——与 8080 客服链路<b>同一份真源</b>。
 * 后台维护一次，两侧都生效；不在 admin 库另存副本，因为风控配置的不一致是要出事故的。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.content-guard", name = "agent-filter-enabled", havingValue = "true")
public class AdminSensitiveWordFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSensitiveWordFilterConfig.class);

    /** 命中日志异步队列容量：与 starter 默认值一致。 */
    private static final int HIT_LOG_QUEUE_CAPACITY = 2048;

    @Bean
    public SensitiveWordStore adminSensitiveWordStore(ContentGuardGatewayProvider gatewayProvider) {
        log.info("admin sensitive word store: customer-work database (shared word table)");
        return new GatewaySensitiveWordStore(gatewayProvider);
    }

    @Bean
    public SensitiveWordFilter adminSensitiveWordFilter(SensitiveWordStore adminSensitiveWordStore,
                                                        CustomerWorkProperties adminContentGuardRuntimeProperties) {
        SensitiveWordProperties cfg = adminContentGuardRuntimeProperties.getSensitiveWord();
        return new SensitiveWordFilter(adminSensitiveWordStore, cfg.resolveMaskChar(), cfg.getDefaultAction());
    }

    @Bean
    public SensitiveWordRefresher adminSensitiveWordRefresher(SensitiveWordStore adminSensitiveWordStore,
                                                              SensitiveWordFilter adminSensitiveWordFilter) {
        // refreshEnabled 传 false：admin 不走 starter 的 @Scheduled（没开全局调度），
        // 改由 SensitiveWordRefreshTask 自带的定时器驱动 refreshOnce()
        return new SensitiveWordRefresher(adminSensitiveWordStore, adminSensitiveWordFilter, false);
    }

    @Bean
    public SensitiveWordRefreshTask adminSensitiveWordRefreshTask(SensitiveWordRefresher adminSensitiveWordRefresher,
                                                                  ContentGuardProperties properties) {
        return new SensitiveWordRefreshTask(adminSensitiveWordRefresher, properties.getRefreshIntervalMs());
    }

    /** 命中日志出口：仅 {@code hit-log-enabled=true} 时装配，否则中间件按 null 跳过记录。 */
    @Bean
    @ConditionalOnProperty(prefix = "admin.content-guard", name = "hit-log-enabled", havingValue = "true")
    public SensitiveWordHitSink adminSensitiveWordHitSink(ContentGuardGatewayProvider gatewayProvider) {
        SensitiveWordHitLogStore store = new GatewaySensitiveWordHitLogStore(gatewayProvider);
        return new AsyncSensitiveWordHitSink(store, HIT_LOG_QUEUE_CAPACITY);
    }

    /**
     * 中间件依赖的 starter 配置对象：admin 没有 {@code CustomerWorkProperties} Bean（自动装配被排除），
     * 这里按 {@code admin.content-guard.*} 现场拼一个，只填敏感词这一小节用到的字段。
     */
    @Bean
    public CustomerWorkProperties adminContentGuardRuntimeProperties(ContentGuardProperties properties) {
        CustomerWorkProperties runtime = new CustomerWorkProperties();
        SensitiveWordProperties cfg = runtime.getSensitiveWord();
        cfg.setEnabled(true);
        cfg.setDirection(parseDirection(properties.getDirection()));
        cfg.setDefaultAction(SensitiveWordAction.BLOCK);
        cfg.getHitLog().setEnabled(properties.isHitLogEnabled());
        cfg.getHitLog().setSnippetMaxLength(properties.getHitLogSnippetMaxLength());
        return runtime;
    }

    @Bean
    public SensitiveWordMiddleware adminSensitiveWordMiddleware(
            CustomerWorkProperties adminContentGuardRuntimeProperties,
            SensitiveWordFilter adminSensitiveWordFilter,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<SensitiveWordHitSink> hitSinkProvider) {
        AuditSink auditSink = new LoggingAuditSink();
        log.info("admin sensitive word middleware wired, direction={}",
            adminContentGuardRuntimeProperties.getSensitiveWord().getDirection());
        return new SensitiveWordMiddleware(adminContentGuardRuntimeProperties, adminSensitiveWordFilter,
            auditSink, meterRegistryProvider, hitSinkProvider);
    }

    /** 方向解析：非法值回落 BOTH（两端都过一遍），风控配置写错时宁可多过滤也不少过滤。 */
    private Direction parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return Direction.BOTH;
        }
        try {
            return Direction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.error("invalid content guard direction, fallback to BOTH, code={}, raw={}",
                "CONTENTGUARD-DIRECTION-INVALID", raw);
            return Direction.BOTH;
        }
    }
}
