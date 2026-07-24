package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * APP 数据源（客服端库 {@code agent_scope_customer_work}）调用统计门面的惰性提供者。
 *
 * <p><b>惰性 + 容错</b>：连接池与门面在首次 {@link #get()} 时才构建并做一次连通性探测（open+close 一条连接），
 * 库不可达则抛 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常并返回明确信息，绝不在 admin 启动期
 * 触碰该库。构建成功后缓存复用（双重检查）；构建失败不缓存，下次 {@link #get()} 重试（覆盖库稍后恢复的场景）。
 * 连接池只读、参数保守（参考 {@code SqlDatasourceConnectionManager}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AppAgentCallStatsGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(AppAgentCallStatsGatewayProvider.class);

    private static final String POOL_NAME = "agent-call-stats-app-pool";
    private static final int MAX_POOL_SIZE = 3;
    private static final int MIN_IDLE = 0;
    private static final long CONNECTION_TIMEOUT_MS = 5000L;
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private final AgentCallStatsAppProperties properties;

    private volatile AgentCallStatsGateway cachedGateway;
    private volatile HikariDataSource dataSource;

    public AppAgentCallStatsGatewayProvider(AgentCallStatsAppProperties properties) {
        this.properties = properties;
    }

    /** 取 APP 门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public AgentCallStatsGateway get() {
        AgentCallStatsGateway gateway = cachedGateway;
        if (gateway != null) {
            return gateway;
        }
        synchronized (this) {
            if (cachedGateway != null) {
                return cachedGateway;
            }
            HikariDataSource ds = buildAndProbeDataSource();
            AgentCallStatsGateway built = AgentCallStatsGatewayFactory.build(ds);
            this.dataSource = ds;
            this.cachedGateway = built;
            log.info("agent call stats APP datasource ready, url={}", properties.jdbcUrl());
            return built;
        }
    }

    /** 构建只读连接池并探测连通性；任何失败关闭半开池并抛 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE}。 */
    private HikariDataSource buildAndProbeDataSource() {
        HikariDataSource ds = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName(POOL_NAME);
            config.setDriverClassName(DRIVER_CLASS);
            config.setJdbcUrl(properties.jdbcUrl());
            config.setUsername(properties.getUsername());
            config.setPassword(properties.getPassword());
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(MIN_IDLE);
            config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
            config.setReadOnly(true);
            // 惰性：构造连接池本身不因不可达而抛（探测放在下方显式 getConnection，失败信息更明确可控）
            config.setInitializationFailTimeout(-1L);
            ds = new HikariDataSource(config);
            try (Connection ignored = ds.getConnection()) {
                // 探测一次：拿到连接即视为可达，随即归还
                log.info("agent call stats APP datasource probe ok");
            }
            return ds;
        } catch (Exception e) {
            if (ds != null) {
                ds.close();
            }
            log.error("agent call stats APP datasource unavailable, code={}, url={}",
                "CALLSTATS-APP-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端调用日志库不可达：" + rootMessage(e));
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            log.info("agent call stats APP datasource closed");
        }
    }
}
