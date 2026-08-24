package com.richard.fyoung.customeradmin.billing.gateway;

import com.richard.fyoung.customeradmin.billing.mapper.CustomerUsageFactMapper;

/** 客服端模型调用金额事实的惰性只读门面。 */
public record CustomerUsageFactGateway(CustomerUsageFactMapper mapper) {
}
