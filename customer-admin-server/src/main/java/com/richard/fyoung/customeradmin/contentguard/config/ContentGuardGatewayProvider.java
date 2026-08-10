package com.richard.fyoung.customeradmin.contentguard.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 客服端库内容风控门面的惰性提供者。
 *
 * <p>惰性建连、探测、缓存、失败不缓存这套通用语义由 starter 的 {@link CrossDbGatewayProvider} 承担；
 * 本类只做两件本域的事：给出连接参数，以及把"库不可达"翻译成
 * {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常——<b>绝不在 admin 启动期触碰该库</b>，
 * 后台不该因为客服端库没起来就启动不了。</p>
 *
 * <p>连接池<b>可写</b>（与 callstats 的只读池不同）：内容风控是后台维护词库与规则的地方，写是它的本职。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@EnableConfigurationProperties(ContentGuardProperties.class)
public class ContentGuardGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(ContentGuardGatewayProvider.class);

    private static final String POOL_NAME = "content-guard-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<ContentGuardGateway> delegate;

    public ContentGuardGatewayProvider(ContentGuardProperties properties) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            ContentGuardGatewayFactory.MAPPER_CLASSES,
            ContentGuardGatewayFactory.MAPPER_XML_LOCATIONS,
            ContentGuardGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public ContentGuardGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("content guard datasource unavailable, code={}, url={}",
                "CONTENTGUARD-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（敏感词/限流规则存放于此）：" + e.rootMessage());
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
