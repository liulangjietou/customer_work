package com.richard.fyoung.customeradmin.tenant;

import com.richard.fyoung.customerwork.safety.tenant.ExactTenantSql;

/** MyBatis Wrapper 中的精确租户条件；CAST 写法同时兼容 MySQL 与租户插件的 SQL 解析器。 */
public final class TenantSqlConditions {
    /** 仍由调用方传入可信 TenantContext，{0} 由 MyBatis 绑定而非字符串拼接。 */
    public static final String EXACT_TENANT = ExactTenantSql.CONDITION;

    private TenantSqlConditions() {
    }
}
