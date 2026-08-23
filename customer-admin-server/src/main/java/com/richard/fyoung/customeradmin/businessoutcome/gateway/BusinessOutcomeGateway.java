package com.richard.fyoung.customeradmin.businessoutcome.gateway;

import com.richard.fyoung.customeradmin.businessoutcome.mapper.BusinessOutcomeMapper;

/** 客服端业务结果只读门面。 */
public record BusinessOutcomeGateway(BusinessOutcomeMapper mapper) {
}
