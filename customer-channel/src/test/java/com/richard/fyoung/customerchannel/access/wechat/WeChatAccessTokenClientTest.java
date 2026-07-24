package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.support.WebClients;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WeChatAccessTokenClient} 缓存与强刷测试：缓存命中不重复请求微信、forceRefresh 触发再次拉取。
 *
 * <p>用 JDK 内置 {@link HttpServer} 伪造微信 {@code /cgi-bin/token}（零额外依赖），每次返回递增 token。</p>
 * @author owlzhangfq@gmail.com
 */
class WeChatAccessTokenClientTest {

    private HttpServer server;
    private WeChatAccessTokenClient client;
    private final AtomicInteger tokenHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cgi-bin/token", exchange -> {
            int n = tokenHits.incrementAndGet();
            String body = "{\"access_token\":\"tok-" + n + "\",\"expires_in\":7200}";
            respond(exchange, body);
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        client = new WeChatAccessTokenClient(WebClients.builder().baseUrl(baseUrl).build());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void shouldCacheTokenAndNotRefetch() {
        String first = client.getToken("appX", "secretX");
        String second = client.getToken("appX", "secretX");

        assertEquals("tok-1", first);
        assertEquals("tok-1", second, "缓存有效期内复用同一 token");
        assertEquals(1, tokenHits.get(), "仅拉取一次");
    }

    @Test
    void shouldRefetchOnForceRefresh() {
        String first = client.getToken("appX", "secretX");
        String refreshed = client.forceRefresh("appX", "secretX");

        assertEquals("tok-1", first);
        assertEquals("tok-2", refreshed, "强刷拿到新 token");
        assertEquals(2, tokenHits.get());
    }
}
