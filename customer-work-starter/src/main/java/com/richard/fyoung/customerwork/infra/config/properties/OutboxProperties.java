package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/** 数据库 Outbox 投递配置。 */
@Data
public class OutboxProperties {

    /** auto 跟随 ticket.store-mode；也可显式指定 memory/jdbc。 */
    private String storeMode = "auto";

    private int maxAttempts = 10;

    private long baseBackoffMs = 1_000L;

    private int batchSize = 50;

    private long leaseMs = 30_000L;

    private long scanIntervalMs = 1_000L;

    /** 健康检查进入 DEGRADED 的待投递积压阈值。 */
    private long degradedPendingThreshold = 1_000L;
}
