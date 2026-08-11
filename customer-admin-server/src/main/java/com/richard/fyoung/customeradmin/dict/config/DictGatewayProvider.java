package com.richard.fyoung.customeradmin.dict.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
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
 * 客服端库字典门面的惰性提供者。
 *
 * <p>惰性建连、探测、缓存、失败不缓存这套通用语义由 starter 的 {@link CrossDbGatewayProvider} 承担；
 * 本类只给出连接参数，并把"库不可达"翻译成 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 业务异常，
 * <b>绝不在 admin 启动期触碰该库</b>。</p>
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

    private final DictProperties properties;
    private final CrossDbGatewayProvider<DictGateway> delegate;

    public DictGatewayProvider(DictProperties properties) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            DictGatewayFactory.MAPPER_CLASSES,
            DictGatewayFactory.MAPPER_XML_LOCATIONS,
            DictGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public DictGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("dict datasource unavailable, code={}, url={}",
                "DICT-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（字典数据存放于此）：" + e.rootMessage());
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
