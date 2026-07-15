package com.richard.fyoung.customerchannel;

import com.richard.fyoung.customerwork.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 飞书主动通知通道：用已有飞书自定义机器人 webhook（{@link FeishuWebhookNotifier}）把主动服务消息推达，
 * <b>复用 Channel 推送能力</b>。覆盖 starter 的默认 {@code LoggingNotificationChannel}；
 * webhook 未配置时优雅降级为日志。
 * @author owlzhangfq@gmail.com
 */
@Component
@Primary
public class FeishuNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(FeishuNotificationChannel.class);

    private final FeishuWebhookNotifier notifier;

    public FeishuNotificationChannel(FeishuWebhookNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public Mono<Void> push(String target, String message) {
        if (!notifier.isConfigured()) {
            log.info("[notify-feishu] webhook not configured, fallback log. target={}, message={}", target, message);
            return Mono.empty();
        }
        String text = target == null ? message : "@" + target + " " + message;
        return Mono.fromCallable(() -> notifier.push(text))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(resp -> log.info("[notify-feishu] pushed, resp={}", resp))
            .then();
    }
}
