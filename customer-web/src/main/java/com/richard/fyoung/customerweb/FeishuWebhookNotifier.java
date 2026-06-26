package com.richard.fyoung.customerweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 飞书出站推送器（Channel · 飞书 · outbound「给我推送消息」）。
 *
 * <p>通过飞书<b>自定义机器人 Webhook</b>把文本消息推送到飞书群（运营/告警/主动通知场景）。
 * 若机器人配置了"关键词"安全校验，消息会自动带上配置的 {@code webhookKeyword} 以放行。</p>
 *
 * <p>配置 {@code customer-web.channel.feishu.webhook-url}（建议用环境变量 {@code FEISHU_WEBHOOK_URL} 注入）。
 * 未配置时 {@link #push(String)} 直接返回失败说明，不抛异常。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class FeishuWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookNotifier.class);

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public FeishuWebhookNotifier(FeishuProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getWebhookUrl());
    }

    /**
     * 推送一条文本消息到飞书群。
     *
     * @return 推送结果说明（成功为 {@code ok}，否则为失败原因），不抛异常
     */
    public String push(String text) {
        if (!isConfigured()) {
            return "skipped: webhook-url not configured";
        }
        try {
            // 关键词安全校验：消息须含配置的关键词，自动前缀
            String content = StringUtils.hasText(properties.getWebhookKeyword())
                ? properties.getWebhookKeyword() + " " + text : text;
            ObjectNode body = objectMapper.createObjectNode();
            body.put("msg_type", "text");
            body.putObject("content").put("text", content);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getWebhookUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = objectMapper.readTree(resp.body()).path("code").asInt(-1);
            if (code == 0) {
                log.info("[Feishu] webhook push ok");
                return "ok";
            }
            log.error("[Feishu] webhook push rejected, code={} body={}", "FEISHU_PUSH_REJECTED", resp.body());
            return "failed: " + resp.body();
        } catch (Exception e) {
            log.error("[Feishu] webhook push failed, code={}", "FEISHU_PUSH_ERROR", e);
            return "failed: " + e.getMessage();
        }
    }
}
