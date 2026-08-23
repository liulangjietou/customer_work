package com.richard.fyoung.customerwork.safety.tenant;

import lombok.Data;

/**
 * 控制面下发给运行时的租户访问快照。
 *
 * <p>{@code accessEpoch} 是单租户单调递增版本；运行时永不接受更小版本，避免延迟消息把已冻结租户恢复为可用。
 * {@code expireTime} 使用控制面发布的 ISO-8601 本地时间字符串，空表示不限期。</p>
 */
@Data
public class TenantAccessSnapshot {

    private Integer schemaVersion;
    private String tenantId;
    private String status;
    private Long accessEpoch;
    private String expireTime;
    private Long changedAtMs;
}
