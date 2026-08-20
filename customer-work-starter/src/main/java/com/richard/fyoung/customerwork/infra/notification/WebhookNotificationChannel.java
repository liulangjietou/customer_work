package com.richard.fyoung.customerwork.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.richard.fyoung.customerwork.infra.config.properties.NotificationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通用通知网关出站通道：稳定操作号同时作为下游幂等键。 */
public class WebhookNotificationChannel implements NotificationChannel {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final NotificationProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebhookNotificationChannel(NotificationProperties properties) {
        this(properties, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .build(), new ObjectMapper());
    }

    WebhookNotificationChannel(NotificationProperties properties, HttpClient httpClient,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> push(String target, String message) {
        return push(java.util.UUID.randomUUID().toString(), target, message);
    }

    @Override
    public Mono<Void> push(String operationId, String target, String message) {
        return Mono.fromCallable(() -> {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("operationId", operationId);
                payload.put("target", target);
                payload.put("message", message);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header(IDEMPOTENCY_KEY, operationId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
                if (StringUtils.hasText(properties.getAuthToken())) {
                    builder.header(HttpHeaders.AUTHORIZATION, HttpAuthConstants.BEARER_PREFIX + properties.getAuthToken());
                }
                HttpResponse<Void> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("notification webhook rejected, status=" + response.statusCode());
                }
                return response;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }
}
