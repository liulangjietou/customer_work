package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户配额配置。默认关闭——没配配额时行为与引入配额之前完全一致。
 *
 * <p>只判 token 不判金额：金额需要单价表（在 admin 库），让客服端跨库反查只为算一个
 * 实时金额并不划算；token 本就是成本的直接驱动。金额维度走 T+1 账单与预警。</p>
 */
@Data
public class QuotaProperties {

    /** 是否开启配额判定。关闭时 Guard 一律放行，也不记账。 */
    private boolean enabled = false;

    /** 配额存储：{@code memory} 进程内 / {@code jdbc} 落 {@code cw_tenant_quota}。 */
    private String storeMode = "memory";
}
