package com.richard.fyoung.customerwork.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 主动服务：订单状态主动通知 / 满意度回访 / 营销触达。
 *
 * <p>消息文案在此组装，推送委托给可替换的 {@link NotificationChannel}（默认日志，生产复用飞书/钉钉等
 * Channel 推送）。由订单状态变更事件、定时任务或运营后台触发。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProactiveNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveNotificationService.class);

    private final NotificationChannel channel;

    public ProactiveNotificationService(NotificationChannel channel) {
        this.channel = channel;
    }

    /** 订单状态主动通知（已发货 / 已签收等）。 */
    public Mono<Void> notifyOrderStatus(String orderId, String status, String target) {
        String msg = "【订单通知】您的订单 " + orderId + " 状态已更新为：" + status + "。如有疑问可随时联系在线客服。";
        log.info("proactive notify order status: order={}, status={}", orderId, status);
        return channel.push(target, msg);
    }

    /** 满意度回访。 */
    public Mono<Void> sendSatisfactionSurvey(String orderId, String target) {
        String msg = "【满意度回访】您关于订单 " + orderId + " 的服务已完成，诚邀您为本次服务打分（1-5 分），"
            + "期待您的反馈以帮助我们做得更好。";
        log.info("proactive satisfaction survey: order={}", orderId);
        return channel.push(target, msg);
    }
}
