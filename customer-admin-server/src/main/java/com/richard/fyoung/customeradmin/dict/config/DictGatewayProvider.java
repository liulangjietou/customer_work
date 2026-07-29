package com.richard.fyoung.customeradmin.dict.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * 客服端库字典门面的惰性提供者（照 {@code ContentGuardGatewayProvider}）。
 *
 * <p><b>惰性 + 容错</b>：连接池与门面在首次 {@link #get()} 时才构建并做一次连通性探测，库不可达则抛
 * {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常，<b>绝不在 admin 启动期触碰该库</b>。
 * 构建成功后缓存复用（双重检查）；构建失败不缓存，下次 {@link #get()} 重试。</p>
 *
 * <p>连接池<b>可写</b>：字典管理页是这份数据唯一的维护入口，写是它的本职。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@EnableConfigurationProperties(DictProperties.class)
public class DictGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(DictGatewayProvider.class);

    private static final String POOL_NAME = "dict-pool";
    private static final int MAX_POOL_SIZE = 2;
    private static final int MIN_IDLE = 0;
    private static final long CONNECTION_TIMEOUT_MS = 5000L;
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private final DictProperties properties;

    private volatile DictGateway cachedGateway;
    private volatile HikariDataSource dataSource;

    public DictGatewayProvider(DictProperties properties) {
        this.properties = properties;
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public DictGateway get() {
        DictGateway gateway = cachedGateway;
        if (gateway != null) {
            return gateway;
        }
        synchronized (this) {
            if (cachedGateway != null) {
                return cachedGateway;
            }
            HikariDataSource ds = buildAndProbeDataSource();
            DictGateway built = DictGatewayFactory.build(ds);
            this.dataSource = ds;
            this.cachedGateway = built;
            log.info("dict datasource ready, url={}", properties.jdbcUrl());
            return built;
        }
    }

    /** 构建连接池并探测连通性；任何失败关闭半开池并抛 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE}。 */
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
            // 惰性：构造连接池本身不因不可达而抛（探测放在下方显式 getConnection，失败信息更明确可控）
            config.setInitializationFailTimeout(-1L);
            ds = new HikariDataSource(config);
            try (Connection ignored = ds.getConnection()) {
                log.info("dict datasource probe ok");
            }
            return ds;
        } catch (Exception e) {
            if (ds != null) {
                ds.close();
            }
            log.error("dict datasource unavailable, code={}, url={}",
                "DICT-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（字典数据存放于此）：" + rootMessage(e));
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
            log.info("dict datasource closed");
        }
    }
}
