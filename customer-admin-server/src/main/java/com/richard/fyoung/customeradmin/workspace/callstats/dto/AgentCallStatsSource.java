package com.richard.fyoung.customeradmin.workspace.callstats.dto;

/**
 * 调用日志数据源：ADMIN=后台库（customer_admin，本模块埋点写入的库）；
 * APP=客服端库（agent_scope_customer_work，8080 客服链路写入的库，本模块只读查询）。
 * @author owlzhangfq@gmail.com
 */
public enum AgentCallStatsSource {

    ADMIN, APP;

    /** 宽松解析：空/非法一律回落 {@link #ADMIN}（契约默认值）。 */
    public static AgentCallStatsSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ADMIN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ADMIN;
        }
    }
}
