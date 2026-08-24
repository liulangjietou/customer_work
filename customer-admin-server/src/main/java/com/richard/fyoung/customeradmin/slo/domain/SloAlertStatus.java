package com.richard.fyoung.customeradmin.slo.domain;

/** SLO 告警生命周期；恢复是终态，再次燃烧会创建新的告警周期。 */
public enum SloAlertStatus {
    OPEN,
    ACKED,
    RESOLVED
}
