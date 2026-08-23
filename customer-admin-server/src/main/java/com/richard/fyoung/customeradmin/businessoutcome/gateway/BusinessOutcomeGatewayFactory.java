package com.richard.fyoung.customeradmin.businessoutcome.gateway;

import com.richard.fyoung.customeradmin.businessoutcome.mapper.BusinessOutcomeMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;

import java.util.List;

/** 业务结果跨库门面装配差异。 */
public final class BusinessOutcomeGatewayFactory {

    public static final List<Class<?>> MAPPER_CLASSES = List.of(BusinessOutcomeMapper.class);

    private BusinessOutcomeGatewayFactory() {
    }

    public static BusinessOutcomeGateway build(CrossDbGateway gateway) {
        return new BusinessOutcomeGateway(gateway.getMapper(BusinessOutcomeMapper.class));
    }
}
