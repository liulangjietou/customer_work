package com.richard.fyoung.customerwork.infra.notification;

import reactor.core.publisher.Mono;

/**
 * 主动通知出站通道（扩展点）：把主动服务消息推达用户。
 *
 * <p>开发环境默认 {@link LoggingNotificationChannel}（仅日志）；生产配置通用 Webhook，或声明自定义 Bean
 * 覆盖，例如 customer-channel 提供的飞书实现。</p>
 * @author owlzhangfq@gmail.com
 */
public interface NotificationChannel {

    /**
     * 推送一条消息。
     *
     * @param target  接收方标识（手机号 / openId / 群标识等，按具体通道语义解释）
     * @param message 消息正文
     */
    Mono<Void> push(String target, String message);

    /**
     * 带稳定操作号的投递。需要可靠重投的生产通道应覆盖本方法，并把 operationId 传给下游作为幂等键；
     * 旧通道默认回落两参方法，保持二进制兼容但只具备至少一次语义。
     */
    default Mono<Void> push(String operationId, String target, String message) {
        return push(target, message);
    }
}
