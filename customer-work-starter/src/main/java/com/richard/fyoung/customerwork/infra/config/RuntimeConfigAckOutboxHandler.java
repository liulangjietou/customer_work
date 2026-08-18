package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.data.outbox.OutboxHandler;
import com.richard.fyoung.customerwork.data.outbox.OutboxMessage;
import com.richard.fyoung.customerwork.infra.config.properties.NacosProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 运行时配置 ACK 的 Outbox 投递器：失败抛出，由 Outbox 指数退避并跨重启续投。 */
@Component
public class RuntimeConfigAckOutboxHandler implements OutboxHandler {

    public static final String TYPE = "runtime-config-ack";

    private final CustomerWorkProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public RuntimeConfigAckOutboxHandler(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxMessage message) throws Exception {
        NacosProperties nacos = properties.getNacos();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(nacos.getRuntimeConfigAckUrl()))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(message.getPayload()));
        if (StringUtils.hasText(nacos.getRuntimeConfigAckToken())) {
            builder.header("X-Open-Api-Token", nacos.getRuntimeConfigAckToken());
        }
        HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("runtime config ACK rejected, status=" + response.statusCode());
        }
    }
}
