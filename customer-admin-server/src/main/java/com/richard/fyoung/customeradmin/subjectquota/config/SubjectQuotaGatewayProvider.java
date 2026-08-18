package com.richard.fyoung.customeradmin.subjectquota.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.subjectquota.jdbc.SubjectQuotaGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 客服端库主体配额门面的惰性提供者。
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——这些表与内容风控三表、租户配额同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置。</p>
 *
 * <p>惰性建连，<b>绝不在 admin 启动期触碰客服端库</b>：后台不该因为客服端库没起来就启动不了。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SubjectQuotaGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(SubjectQuotaGatewayProvider.class);

    private static final String POOL_NAME = "subject-quota-pool";
    private static final int MAX_POOL_SIZE = 3;

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<SubjectQuotaGateway> delegate;

    public SubjectQuotaGatewayProvider(ContentGuardProperties properties) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            SubjectQuotaGatewayFactory.MAPPER_CLASSES,
            SubjectQuotaGatewayFactory.MAPPER_XML_LOCATIONS,
            SubjectQuotaGatewayFactory::build);
    }

    /** 取门面（惰性构建 + 探测 + 缓存）；库不可达抛明确业务异常。 */
    public SubjectQuotaGateway get() {
        try {
            return delegate.get();
        } catch (CrossDbUnavailableException e) {
            log.error("subject quota datasource unavailable, code={}, url={}",
                "SQUOTA-DS-UNAVAILABLE", properties.jdbcUrl(), e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE,
                "客服端库不可达（主体配额等级存放于此）：" + e.rootMessage());
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
