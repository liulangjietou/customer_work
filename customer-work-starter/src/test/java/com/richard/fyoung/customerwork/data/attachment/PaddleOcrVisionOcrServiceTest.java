package com.richard.fyoung.customerwork.data.attachment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PaddleOCR OCR 服务单测：用 JDK 内置 {@link HttpServer} 起一个假 PaddleX serving，返回真实抓到的响应
 * JSON 结构（{@code result.ocrResults[].prunedResult.rec_texts}），覆盖正常识别 / 多行阅读顺序拼接 /
 * 非 200 / 业务 errorCode 非 0 / 连接拒绝。全程离线，不依赖真实容器。
 * @author owlzhangfq@gmail.com
 */
class PaddleOcrVisionOcrServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个假 serving，固定返回给定 body 与状态码，返回其 base-url。 */
    private String startFakeServer(int statusCode, String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 官方 PaddleX OCR pipeline 响应结构（多行 rec_texts）。 */
    private static String okResponse(String... lines) {
        StringBuilder texts = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                texts.append(',');
            }
            texts.append('"').append(lines[i]).append('"');
        }
        return "{\"logId\":\"x\",\"errorCode\":0,\"errorMsg\":\"Success\",\"result\":{\"ocrResults\":[{"
            + "\"prunedResult\":{\"rec_texts\":[" + texts + "],\"rec_scores\":[0.99]}}]}}";
    }

    @Test
    void recognize_shouldReturnJoinedTextInReadingOrder() throws IOException {
        String baseUrl = startFakeServer(200, okResponse("PaddleOCR 测试", "Hello World 12345", "智能客服附件识别"));
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 10);

        String text = service.recognize(new byte[]{1, 2, 3}, "image/png");

        assertEquals("PaddleOCR 测试\nHello World 12345\n智能客服附件识别", text);
    }

    @Test
    void recognize_shouldTrimBlankLines() throws IOException {
        String baseUrl = startFakeServer(200, okResponse("line1", "", "line2"));
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 10);

        assertEquals("line1\nline2", service.recognize(new byte[]{1}, "image/png"));
    }

    @Test
    void recognize_shouldThrowOnNon200() throws IOException {
        String baseUrl = startFakeServer(500, "{}");
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 10);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.recognize(new byte[]{1}, "image/png"));
        assertTrue(ex.getMessage().contains("non-200"));
    }

    @Test
    void recognize_shouldThrowOnBusinessError() throws IOException {
        String baseUrl = startFakeServer(200, "{\"errorCode\":1,\"errorMsg\":\"bad file\"}");
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 10);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.recognize(new byte[]{1}, "image/png"));
        assertTrue(ex.getMessage().contains("business error"));
    }

    @Test
    void recognize_shouldThrowOnEmptyOcrResults() throws IOException {
        String baseUrl = startFakeServer(200, "{\"errorCode\":0,\"result\":{\"ocrResults\":[]}}");
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 10);

        assertThrows(IllegalStateException.class, () -> service.recognize(new byte[]{1}, "image/png"));
    }

    @Test
    void recognize_shouldThrowOnConnectionRefused() {
        // 指向一个未监听的端口，触发连接拒绝
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService("http://127.0.0.1:1", "/ocr", 3);

        assertThrows(IllegalStateException.class, () -> service.recognize(new byte[]{1}, "image/png"));
    }
}
