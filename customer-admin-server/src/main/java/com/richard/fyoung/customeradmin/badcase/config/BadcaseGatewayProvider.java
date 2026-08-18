package com.richard.fyoung.customeradmin.badcase.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseService;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服端库 badcase 回流服务的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——badcase 与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置（与配额、评测门面同一取舍）。</p>
 *
 * <p>惰性建连，绝不在 admin 启动期触碰客服端库。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class BadcaseGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(BadcaseGatewayProvider.class);

    private static final String POOL_NAME = "badcase-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<BadcaseService> delegate;

    public BadcaseGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            BadcaseGatewayFactory.MAPPER_CLASSES,
            BadcaseGatewayFactory.MAPPER_XML_LOCATIONS,
            tenantPlugins::create,
            BadcaseGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public BadcaseService get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("badcase datasource unavailable, code={}, url={}",
                "BADCASE-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（badcase 与回流目标存放于此）：" + e.rootMessage());
        }
    }

    private CrossDbConnectionSettings connectionSettings() {
        return CrossDbConnectionSettings.builder(POOL_NAME, properties.jdbcUrl())
            .credentials(properties.getUsername(), properties.getPassword())
            .maximumPoolSize(MAX_POOL_SIZE)
            .build();
    }

    @PreDestroy
    public void close() {
        delegate.close();
    }
}
