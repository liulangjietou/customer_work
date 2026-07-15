package com.richard.fyoung.customerchannel;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 飞书出站推送入口（Channel · 飞书 · outbound「给我推送消息」）。
 *
 * <p>{@code POST /push/feishu?text=...} 把一条文本主动推送到配置的飞书群（运营通知 / 告警 / 人工转接提醒等）。
 * 底层走飞书自定义机器人 Webhook，见 {@link FeishuWebhookNotifier}。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
public class FeishuPushController {

    private final FeishuWebhookNotifier notifier;

    public FeishuPushController(FeishuWebhookNotifier notifier) {
        this.notifier = notifier;
    }

    @PostMapping("/push/feishu")
    public Map<String, Object> push(@RequestParam("text") String text) {
        String result = notifier.push(text);
        return Map.of("configured", notifier.isConfigured(), "result", result);
    }
}
