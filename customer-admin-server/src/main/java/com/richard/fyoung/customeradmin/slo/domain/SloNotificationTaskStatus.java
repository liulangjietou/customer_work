package com.richard.fyoung.customeradmin.slo.domain;

/** SLO 站内通知任务状态；失败任务回到 PENDING，过期 PROCESSING 可被其他副本重新领取。 */
public enum SloNotificationTaskStatus {
    PENDING,
    PROCESSING,
    DELIVERED
}
