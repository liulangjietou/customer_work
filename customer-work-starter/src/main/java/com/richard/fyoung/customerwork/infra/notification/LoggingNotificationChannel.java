package com.richard.fyoung.customerwork.infra.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 默认主动通知通道：仅写日志（开箱即用、离线可跑）。生产以真实通道 Bean 覆盖（如飞书 webhook）。
 * @author owlzhangfq@gmail.com
 */
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationChannel.class);

    @Override
    public Mono<Void> push(String target, String message) {
        log.info("[notify] target={}, message={}", target, message);
        return Mono.empty();
    }
}
