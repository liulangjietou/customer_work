package com.richard.fyoung.customerwork.infra.notification;

/** 主动通知死信的自包含载荷；operationId 在首次发送与所有重投中保持不变。 */
public record NotificationDeadLetterPayload(
    String operationId,
    String target,
    String message,
    String bizKey
) {
}
