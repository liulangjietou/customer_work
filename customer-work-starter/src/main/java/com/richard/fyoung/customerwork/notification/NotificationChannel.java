package com.richard.fyoung.customerwork.notification;

import reactor.core.publisher.Mono;

/**
 * 主动通知出站通道（扩展点）：把主动服务消息推达用户。
 *
 * <p>默认 {@link LoggingNotificationChannel}（仅日志）；接入真实推送时声明自定义 Bean 即可覆盖——
 * 例如 customer-channel 提供基于飞书 webhook 的实现，<b>复用已有 Channel 推送能力</b>。</p>
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
}
