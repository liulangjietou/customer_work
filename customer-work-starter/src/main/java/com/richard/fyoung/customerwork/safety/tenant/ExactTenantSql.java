package com.richard.fyoung.customerwork.safety.tenant;

/** 显式跨租户查询的参数化租户条件；数据库默认排序规则不能改变租户标识的相等语义。 */
public final class ExactTenantSql {
    public static final String CONDITION = "CAST(tenant_id AS BINARY) = CAST({0} AS BINARY)";

    private ExactTenantSql() {
    }
}
