package com.richard.fyoung.customerwork.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 基于自建 PaddleOCR 开源 serving 的 OCR 实现（对接官方 PaddleX 3.x OCR pipeline 的 basic serving REST 契约）。
 *
 * <p><b>契约：</b>{@code POST {base-url}/ocr}，请求体 {@code {"file":"<图片base64>","fileType":1}}，
 * 成功响应 {@code {"errorCode":0,"result":{"ocrResults":[{"prunedResult":{"rec_texts":[...],"rec_scores":[...]}}]}}}；
 * 识别文本行 {@code rec_texts} 已按阅读顺序（自上而下）排好，本类按行拼接返回。</p>
 *
 * <p><b>容错：</b>用 JDK 内置 {@link HttpClient}（不引新依赖）。超时 / 非 200 / {@code errorCode!=0} / 服务不可达
 * 一律抛异常，由编排层 {@link AttachmentParseService} 统一落 FAILED（既有链路，本类不吞异常）。
 * 相比视觉大模型，PaddleOCR 免 Key、数据不出内网，适合私有化；编排见 {@code docker/paddleocr/}。</p>
 * @author owlzhangfq@gmail.com
 */
public class PaddleOcrVisionOcrService implements VisionOcrService {

    private static final Logger log = LoggerFactory.getLogger(PaddleOcrVisionOcrService.class);

    /** 解析失败错误码（日志占位符首参，遵循项目规范）。 */
    private static final String ERR_CODE = "ATTACHMENT-PADDLE-OCR-FAIL";
    /** 图片文件类型标识（PaddleX 契约：0=PDF，1=图片）。 */
    private static final int FILE_TYPE_IMAGE = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI ocrEndpoint;
    private final long timeoutSeconds;

    /**
     * @param baseUrl        serving 根地址（如 {@code http://localhost:8868}）
     * @param ocrPath        OCR 端点路径（官方固定 {@code /ocr}）
     * @param timeoutSeconds 请求超时（秒）
     */
    public PaddleOcrVisionOcrService(String baseUrl, String ocrPath, long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.ocrEndpoint = URI.create(trimTrailingSlash(baseUrl) + normalizePath(ocrPath));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
    }

    @Override
    public String recognize(byte[] imageBytes, String mimeType) {
        long startMs = System.currentTimeMillis();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        // 请求体：file=图片base64，fileType=1（图片）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", base64);
        body.put("fileType", FILE_TYPE_IMAGE);

        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(ocrEndpoint)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("paddleocr serving returned non-200 status: " + response.statusCode());
            }
            String text = extractText(response.body());
            // 成功留痕：确认本次识别走的是 paddleocr 引擎，并留识别规模/耗时供生产排查
            log.info("paddleocr recognize ok, imageBytes={}, chars={}, costMs={}",
                imageBytes.length, text.length(), System.currentTimeMillis() - startMs);
            return text;
        } catch (IllegalStateException e) {
            // 已是明确业务异常，直接上抛
            log.error("paddleocr recognize failed, code={}, endpoint={}", ERR_CODE, ocrEndpoint, e);
            throw e;
        } catch (Exception e) {
            // 超时 / 连接拒绝 / IO / JSON 解析等，统一包装上抛（编排层落 FAILED）
            log.error("paddleocr recognize failed, code={}, endpoint={}", ERR_CODE, ocrEndpoint, e);
            throw new IllegalStateException("paddleocr recognize failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 PaddleX 响应 JSON 抽取识别文本：校验 errorCode，遍历 ocrResults 的 prunedResult.rec_texts 按阅读顺序拼接。
     */
    private String extractText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode errorCode = root.get("errorCode");
        if (errorCode != null && errorCode.asInt() != 0) {
            String msg = root.path("errorMsg").asText("");
            throw new IllegalStateException("paddleocr serving business error, errorCode=" + errorCode.asInt() + ", errorMsg=" + msg);
        }
        JsonNode ocrResults = root.path("result").path("ocrResults");
        if (!ocrResults.isArray() || ocrResults.isEmpty()) {
            throw new IllegalStateException("paddleocr serving returned empty ocrResults");
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (JsonNode ocrResult : ocrResults) {
            JsonNode recTexts = ocrResult.path("prunedResult").path("rec_texts");
            if (recTexts.isArray()) {
                for (JsonNode line : recTexts) {
                    String text = line.asText("");
                    if (StringUtils.hasText(text)) {
                        joiner.add(text);
                    }
                }
            }
        }
        String result = joiner.toString();
        if (!StringUtils.hasText(result)) {
            throw new IllegalStateException("paddleocr serving recognized no text");
        }
        return result;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
