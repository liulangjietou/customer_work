package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link EvalTriggerClient} 结构化 API Key 请求头契约测试。 */
class EvalTriggerClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendKeyIdAndSecretTogether() throws Exception {
        AtomicReference<String> keyId = new AtomicReference<>();
        AtomicReference<String> secret = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/customer/eval/intent", exchange -> {
            keyId.set(exchange.getRequestHeaders().getFirst(HttpAuthConstants.API_KEY_ID_HEADER));
            secret.set(exchange.getRequestHeaders().getFirst(HttpAuthConstants.API_KEY_HEADER));
            byte[] response = ("{\"current\":null,\"baseline\":null,"
                + "\"regressions\":[],\"fixes\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        CustomerWorkClientProperties properties = new CustomerWorkClientProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setApiKeyId("admin-eval");
        properties.setApiKey("raw-secret");

        new EvalTriggerClient(properties).trigger(EvalType.INTENT, "manual");

        assertEquals("admin-eval", keyId.get());
        assertEquals("raw-secret", secret.get());
    }
}
