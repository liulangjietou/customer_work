package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.support.WebClients;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeChatCustomerMessageSender} 测试：分段静态方法、40001 强刷重试、超长分段多条发送。
 *
 * <p>用 JDK 内置 {@link HttpServer} 伪造微信 token/客服消息接口。</p>
 * @author owlzhangfq@gmail.com
 */
class WeChatCustomerMessageSenderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger tokenHits = new AtomicInteger();
    private final AtomicInteger sendHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private WeChatCustomerMessageSender newSender() {
        WeChatAccessTokenClient tokenClient =
            new WeChatAccessTokenClient(WebClients.builder().baseUrl(baseUrl).build());
        return new WeChatCustomerMessageSender(WebClients.builder().baseUrl(baseUrl).build(), tokenClient);
    }

    @Test
    void shouldSplitBySegmentMax() {
        List<String> segs = WeChatCustomerMessageSender.segments("abcdefg", 3);

        assertEquals(List.of("abc", "def", "g"), segs);
    }

    @Test
    void shouldForceRefreshOnInvalidTokenAndRetry() {
        server.createContext("/cgi-bin/token", exchange -> {
            int n = tokenHits.incrementAndGet();
            respond(exchange, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":7200}");
        });
        // 第一版 token(tok-1) 返回 40001；换新 token(tok-2) 后返回 0
        server.createContext("/cgi-bin/message/custom/send", exchange -> {
            sendHits.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            String errcode = query != null && query.contains("access_token=tok-1") ? "40001" : "0";
            respond(exchange, "{\"errcode\":" + errcode + ",\"errmsg\":\"ok\"}");
        });
        server.start();

        newSender().send("appX", "secretX", "openid-1", "hello");

        assertEquals(2, sendHits.get(), "首发遇 40001 后强刷重试一次");
        assertEquals(2, tokenHits.get(), "token 拉取一次 + 强刷一次");
    }

    @Test
    void shouldSendMultipleSegmentsForLongText() {
        server.createContext("/cgi-bin/token", exchange ->
            respond(exchange, "{\"access_token\":\"tok-a\",\"expires_in\":7200}"));
        server.createContext("/cgi-bin/message/custom/send", exchange -> {
            sendHits.incrementAndGet();
            respond(exchange, "{\"errcode\":0}");
        });
        server.start();

        // 2500 字符 → 按 1000 分段 → 3 条
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            sb.append('x');
        }
        newSender().send("appX", "secretX", "openid-1", sb.toString());

        assertEquals(3, sendHits.get(), "超长文本分 3 段发送");
    }

    @Test
    void shouldSkipBlankContent() {
        server.createContext("/cgi-bin/message/custom/send", exchange -> {
            sendHits.incrementAndGet();
            respond(exchange, "{\"errcode\":0}");
        });
        server.start();

        newSender().send("appX", "secretX", "openid-1", "   ");

        assertTrue(sendHits.get() == 0, "空白内容不发送");
    }
}
