package com.richard.fyoung.customerwork.infra.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.properties.NotificationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookNotificationChannelTest {

    @Test
    void stableOperationIdIsForwardedAsIdempotencyKey() throws Exception {
        NotificationProperties properties = properties();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        WebhookNotificationChannel channel = new WebhookNotificationChannel(
            properties, client, new ObjectMapper());

        channel.push("op-1", "user-1", "order shipped").block();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("op-1", request.getValue().headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals("Bearer notify-token",
            request.getValue().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void nonSuccessResponseFailsForDeadLetterCapture() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        WebhookNotificationChannel channel = new WebhookNotificationChannel(
            properties(), client, new ObjectMapper());

        assertThrows(IllegalStateException.class,
            () -> channel.push("op-1", "user-1", "message").block());
    }

    private NotificationProperties properties() {
        NotificationProperties properties = new NotificationProperties();
        properties.setWebhookUrl("https://notify.internal.example/messages");
        properties.setAuthToken("notify-token");
        return properties;
    }
}
