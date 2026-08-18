package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服端库评测记录门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——评测记录与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置（与配额门面同一取舍）。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 *
 * <p>跨库 SqlSessionFactory 与 admin 主库挂同一租户插件，默认只读当前有效租户；平台确需全租户汇总时
 * 必须在已校验运营权限后显式进入 {@code CrossTenantOperations}，避免跨库门面成为隔离旁路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class EvalGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(EvalGatewayProvider.class);

    private static final String POOL_NAME = "eval-run-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<EvalRunStore> delegate;

    public EvalGatewayProvider(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            EvalGatewayFactory.MAPPER_CLASSES,
            EvalGatewayFactory.MAPPER_XML_LOCATIONS,
            tenantPlugins::create,
            EvalGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public EvalRunStore get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("eval datasource unavailable, code={}, url={}",
                "EVAL-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（评测记录存放于此）：" + e.rootMessage());
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
