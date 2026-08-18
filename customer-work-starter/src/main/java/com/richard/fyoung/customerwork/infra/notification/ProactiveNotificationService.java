package com.richard.fyoung.customerwork.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 主动服务：订单状态主动通知 / 满意度回访 / 营销触达。
 *
 * <p>消息文案在此组装，推送委托给可替换的 {@link NotificationChannel}。生产配置真实 Webhook，
 * 首次失败进入死信队列自动补偿。由订单状态变更事件、定时任务或运营后台触发。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProactiveNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveNotificationService.class);

    private final NotificationChannel channel;
    private final DeadLetterService deadLetterService;
    private final ObjectMapper objectMapper;

    public ProactiveNotificationService(NotificationChannel channel) {
        this(channel, null, new ObjectMapper());
    }

    @Autowired
    public ProactiveNotificationService(NotificationChannel channel,
                                        DeadLetterService deadLetterService,
                                        ObjectMapper objectMapper) {
        this.channel = channel;
        this.deadLetterService = deadLetterService;
        this.objectMapper = objectMapper;
    }

    /** 订单状态主动通知（已发货 / 已签收等）。 */
    public Mono<Void> notifyOrderStatus(String orderId, String status, String target) {
        String msg = "【订单通知】您的订单 " + orderId + " 状态已更新为：" + status + "。如有疑问可随时联系在线客服。";
        log.info("proactive notify order status: order={}, status={}", orderId, status);
        return send(orderId, target, msg);
    }

    /** 满意度回访。 */
    public Mono<Void> sendSatisfactionSurvey(String orderId, String target) {
        String msg = "【满意度回访】您关于订单 " + orderId + " 的服务已完成，诚邀您为本次服务打分（1-5 分），"
            + "期待您的反馈以帮助我们做得更好。";
        log.info("proactive satisfaction survey: order={}", orderId);
        return send(orderId, target, msg);
    }

    /** 首次失败可靠落死信后返回已受理；死信也落不下时保留原异常，不能伪报成功。 */
    private Mono<Void> send(String bizKey, String target, String message) {
        String operationId = UUID.randomUUID().toString();
        return Mono.defer(() -> channel.push(operationId, target, message))
            .onErrorResume(error -> recordFailure(operationId, bizKey, target, message, error));
    }

    private Mono<Void> recordFailure(String operationId, String bizKey, String target,
                                     String message, Throwable error) {
        if (deadLetterService == null) {
            return Mono.error(error);
        }
        try {
            String payload = objectMapper.writeValueAsString(
                new NotificationDeadLetterPayload(operationId, target, message, bizKey));
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (deadLetterService.record(ProactiveNotificationDeadLetterHandler.TYPE,
                payload, bizKey, reason).isPresent()) {
                log.info("proactive notification queued for retry: operationId={}, bizKey={}",
                    operationId, bizKey);
                return Mono.empty();
            }
        } catch (Exception recordError) {
            error.addSuppressed(recordError);
        }
        return Mono.error(error);
    }
}
