package com.richard.fyoung.customeradmin.billing.config;

import com.richard.fyoung.customeradmin.billing.jdbc.QuotaGateway;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customerwork.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服端库配额门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——配额表与内容风控三表在同一个客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。连接池另建（各自 3 连接）而不是共用：
 * 池子共用会让后台配额操作与词库维护互相排队，两者都是低频操作，多一个小池的代价可以忽略。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class QuotaGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(QuotaGatewayProvider.class);

    private static final String POOL_NAME = "tenant-quota-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<QuotaGateway> delegate;

    public QuotaGatewayProvider(ContentGuardProperties properties) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            QuotaGatewayFactory.MAPPER_CLASSES,
            QuotaGatewayFactory.MAPPER_XML_LOCATIONS,
            QuotaGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public QuotaGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("quota datasource unavailable, code={}, url={}",
                "QUOTA-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（租户配额存放于此）：" + e.rootMessage());
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
