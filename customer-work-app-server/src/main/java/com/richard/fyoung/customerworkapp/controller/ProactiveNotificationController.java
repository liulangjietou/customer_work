package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.infra.notification.ProactiveNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 主动服务端点：订单状态主动通知 / 满意度回访（由订单事件、定时任务或运营后台触发）。
 *
 * <p>推送复用 {@code NotificationChannel}（customer-channel 默认绑定飞书 webhook；未配置则降级日志）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/notify")
@Tag(name = "主动服务", description = "订单状态通知 / 满意度回访（复用 Channel 推送）")
public class ProactiveNotificationController {

    private final ProactiveNotificationService notificationService;

    public ProactiveNotificationController(ProactiveNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "订单状态主动通知")
    @PostMapping("/order-status")
    public Mono<String> notifyOrderStatus(@RequestParam String orderId,
                                          @RequestParam String status,
                                          @RequestParam String target) {
        return notificationService.notifyOrderStatus(orderId, status, target)
            .thenReturn("已推送订单 " + orderId + " 状态通知");
    }

    @Operation(summary = "满意度回访")
    @PostMapping("/survey")
    public Mono<String> sendSurvey(@RequestParam String orderId,
                                   @RequestParam String target) {
        return notificationService.sendSatisfactionSurvey(orderId, target)
            .thenReturn("已推送订单 " + orderId + " 满意度回访");
    }
}
