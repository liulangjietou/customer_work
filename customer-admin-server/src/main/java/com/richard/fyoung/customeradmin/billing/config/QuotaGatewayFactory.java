package com.richard.fyoung.customeradmin.billing.config;

import com.richard.fyoung.customeradmin.billing.jdbc.QuotaGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.safety.quota.mapper.TenantQuotaMapper;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成配额门面。
 *
 * <p>{@link TenantQuotaMapper} 没有自己的 XML（配额只用 BaseMapper 的 CRUD），故走接口注册。</p>
 * @author owlzhangfq@gmail.com
 */
final class QuotaGatewayFactory {

    static final List<Class<?>> MAPPER_CLASSES = List.of(TenantQuotaMapper.class);

    static final List<String> MAPPER_XML_LOCATIONS = List.of();

    private QuotaGatewayFactory() {
    }

    static QuotaGateway build(CrossDbGateway gateway) {
        return new QuotaGateway(gateway.getMapper(TenantQuotaMapper.class));
    }
}
