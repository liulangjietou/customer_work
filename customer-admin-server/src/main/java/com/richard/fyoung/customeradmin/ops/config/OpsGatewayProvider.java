package com.richard.fyoung.customeradmin.ops.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运营闭环门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——这几张表与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。</p>
 *
 * <p>惰性建连，绝不在 admin 启动期触碰客服端库：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class OpsGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(OpsGatewayProvider.class);

    private static final String POOL_NAME = "ops-closed-loop-pool";

    /** 五个域共用：都是运营低频查询，比单域门面略放宽一点即可。 */
    private static final int MAX_POOL_SIZE = 4;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<OpsGateway> delegate;

    public OpsGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            OpsGatewayFactory.MAPPER_CLASSES,
            OpsGatewayFactory.MAPPER_XML_LOCATIONS,
            tenantPlugins::create,
            OpsGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public OpsGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("ops datasource unavailable, code={}, url={}",
                "OPS-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（运营闭环数据存放于此）：" + e.rootMessage());
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
