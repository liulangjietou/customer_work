package com.richard.fyoung.customerwork.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 模型连通性探活器：按厂商各自的<b>最小探活协议</b>直连一次，验证 baseUrl / apiKey / modelName 是否可用。
 *
 * <p>与 {@link com.richard.fyoung.customerwork.infra.config.ChatModelFactory}（构建长生命周期的对话模型实例）
 * 分工明确：本类只发一条固定 prompt 的短生命周期探测请求，不经过各厂商 SDK。HTTP 客户端的 DNS
 * 解析由 {@link ModelEndpointPolicy} 接管：校验后的同一组地址直接用于建连，且禁止自动重定向，避免
 * 模型凭据经 DNS rebinding 或 3xx 跳转被带到未授权目标。</p>
 *
 * <p>支持 openai（及全部 OpenAI 兼容端点）/ dashscope / anthropic / gemini 四种协议；未知或空 provider
 * 按 OpenAI 兼容协议探活——provider 合法性由调用方收口（防御一处），本类不重复校验。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChatModelProber {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProber.class);

    /** 探活超时的日志错误码。 */
    private static final String CODE_PROBE_TIMEOUT = "MODEL-TEST-TIMEOUT";
    /** 探活失败的日志错误码。 */
    private static final String CODE_PROBE_FAIL = "MODEL-TEST-FAIL";

    /** 单次探测超时默认值（5~10s 区间内取值）。 */
    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String TEST_PROMPT = "你好";
    private static final int TEST_MAX_TOKENS = 8;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final okhttp3.MediaType JSON_MEDIA_TYPE = okhttp3.MediaType.get(MediaType.APPLICATION_JSON_VALUE);

    // ---- 各厂商探活端点路径 / 专有鉴权头（通用头名走 Spring HttpHeaders）----
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DASHSCOPE_TEXT_GENERATION_PATH = "/api/v1/services/aigc/text-generation/generation";
    private static final String ANTHROPIC_MESSAGES_PATH = "/v1/messages";
    private static final String GEMINI_GENERATE_CONTENT_PATH_TEMPLATE = "/v1beta/models/%s:generateContent";

    private static final String HEADER_ANTHROPIC_API_KEY = "x-api-key";
    private static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    private static final String HEADER_GEMINI_API_KEY = "x-goog-api-key";
    /** Anthropic Messages API 版本（原生协议必填头）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final OkHttpClient httpClient;
    private final ModelEndpointPolicy endpointPolicy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration testTimeout;

    public ChatModelProber() {
        this(DEFAULT_TEST_TIMEOUT, new ModelEndpointPolicy(List::of));
    }

    public ChatModelProber(ModelEndpointPolicy endpointPolicy) {
        this(DEFAULT_TEST_TIMEOUT, endpointPolicy);
    }

    /** @param testTimeout 单次探测超时（单测注入短超时，避免真实等待默认 8s） */
    public ChatModelProber(Duration testTimeout) {
        this(testTimeout, new ModelEndpointPolicy(List::of));
    }

    public ChatModelProber(Duration testTimeout, ModelEndpointPolicy endpointPolicy) {
        this(testTimeout, endpointPolicy, null);
    }

    /** 包内测试构造：通过拦截器离线验证请求契约，不放宽生产端点策略。 */
    ChatModelProber(Duration testTimeout, ModelEndpointPolicy endpointPolicy, Interceptor interceptor) {
        this.testTimeout = testTimeout;
        this.endpointPolicy = endpointPolicy;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(testTimeout)
            .writeTimeout(testTimeout)
            .callTimeout(testTimeout)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(hostname -> endpointPolicy.resolveForConnection(hostname));
        if (interceptor != null) {
            builder.addInterceptor(interceptor);
        }
        this.httpClient = builder.build();
    }

    /**
     * 按厂商发一条固定 prompt 的最小探活请求。HTTP 2xx 且返回结构符合该厂商成功响应形态 → 成功；
     * 否则失败（消息带 HTTP 状态码与截断后的响应体）；超时即失败。
     */
    public ProbeResult probe(String provider, String baseUrl, String apiKey, String modelName) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        try {
            String normalizedBaseUrl = endpointPolicy.validateAndNormalizeBaseUrl(baseUrl);
            Request request = buildProbeRequest(normalized, normalizedBaseUrl, apiKey, modelName);
            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String body = responseBody == null ? "" : responseBody.string();
                if (isSuccessStatus(response.code()) && hasValidResponseStructure(normalized, body)) {
                    return new ProbeResult(true, null);
                }
                return new ProbeResult(false, "HTTP " + response.code() + ": " + truncate(body));
            }
        } catch (InterruptedIOException e) {
            log.error("model connectivity test timeout, code={}, provider={}", CODE_PROBE_TIMEOUT, normalized, e);
            return new ProbeResult(false, "连通性测试超时（>" + testTimeout.getSeconds() + "s）");
        } catch (Exception e) {
            log.error("model connectivity test failed, code={}, provider={}", CODE_PROBE_FAIL, normalized, e);
            return new ProbeResult(false, truncate(e.getMessage()));
        }
    }

    // ==================== 各厂商探活请求构建 ====================

    private Request buildProbeRequest(String provider, String baseUrl, String apiKey, String modelName)
            throws Exception {
        switch (provider) {
            case ModelProviders.DASHSCOPE:
                return dashScopeProbe(baseUrl, apiKey, modelName);
            case ModelProviders.ANTHROPIC:
                return anthropicProbe(baseUrl, apiKey, modelName);
            case ModelProviders.GEMINI:
                return geminiProbe(baseUrl, apiKey, modelName);
            default:
                return openAiProbe(baseUrl, apiKey, modelName);
        }
    }

    /** OpenAI 兼容：POST {base}/chat/completions，Bearer 鉴权，成功响应含 choices 数组。 */
    private Request openAiProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", modelName,
            "messages", List.of(Map.of("role", "user", "content", TEST_PROMPT)),
            "max_tokens", TEST_MAX_TOKENS));
        return baseRequest(appendPath(baseUrl, OPENAI_CHAT_COMPLETIONS_PATH), body)
            .header(HttpHeaders.AUTHORIZATION, HttpAuthConstants.BEARER_PREFIX + apiKey)
            .build();
    }

    /** 百炼 DashScope 原生：POST {base}/api/v1/.../generation，Bearer 鉴权，成功响应含 output 对象。 */
    private Request dashScopeProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", modelName,
            "input", Map.of("messages", List.of(Map.of("role", "user", "content", TEST_PROMPT))),
            "parameters", Map.of("max_tokens", TEST_MAX_TOKENS, "result_format", "message")));
        return baseRequest(appendPath(baseUrl, DASHSCOPE_TEXT_GENERATION_PATH), body)
            .header(HttpHeaders.AUTHORIZATION, HttpAuthConstants.BEARER_PREFIX + apiKey)
            .build();
    }

    /** Anthropic 原生：POST {base}/v1/messages，x-api-key + anthropic-version 鉴权，成功响应含 content 数组。 */
    private Request anthropicProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", modelName,
            "max_tokens", TEST_MAX_TOKENS,
            "messages", List.of(Map.of("role", "user", "content", TEST_PROMPT))));
        return baseRequest(appendPath(baseUrl, ANTHROPIC_MESSAGES_PATH), body)
            .header(HEADER_ANTHROPIC_API_KEY, apiKey)
            .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
            .build();
    }

    /**
     * Gemini：POST {base}/v1beta/models/{model}:generateContent，x-goog-api-key 头鉴权
     * （官方支持的头式鉴权；不用 ?key= 查询串，避免 key 出现在 URL 里被异常信息/日志带出），
     * 成功响应含 candidates 数组。
     */
    private Request geminiProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", TEST_PROMPT))))));
        String path = String.format(GEMINI_GENERATE_CONTENT_PATH_TEMPLATE, encodePathSegment(modelName));
        return baseRequest(appendPath(baseUrl, path), body)
            .header(HEADER_GEMINI_API_KEY, apiKey)
            .build();
    }

    /** 公共请求骨架：JSON POST + 超时。鉴权头由各厂商分支追加。 */
    private Request.Builder baseRequest(String url, String body) {
        return new Request.Builder()
            .url(url)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .post(RequestBody.create(body, JSON_MEDIA_TYPE));
    }

    // ==================== 各厂商成功响应结构校验 ====================

    private boolean hasValidResponseStructure(String provider, String responseBody) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            switch (provider) {
                case ModelProviders.DASHSCOPE:
                    // 原生响应体形如 {"output":{...},"usage":{...}}
                    return node.has("output") && node.get("output").isObject();
                case ModelProviders.ANTHROPIC:
                    // 原生响应体形如 {"content":[...],"role":"assistant"}
                    return node.has("content") && node.get("content").isArray();
                case ModelProviders.GEMINI:
                    // 原生响应体形如 {"candidates":[...]}
                    return node.has("candidates") && node.get("candidates").isArray();
                default:
                    return node.has("choices") && node.get("choices").isArray();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSuccessStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 去掉末尾 '/' 后拼接厂商端点路径；若 baseUrl 已带该路径则不重复拼接。 */
    private String appendPath(String baseUrl, String path) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed.endsWith(path) ? trimmed : trimmed + path;
    }

    /** RFC 3986 path segment：只保留 unreserved 字符，其余按 UTF-8 百分号编码。 */
    private String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte valueByte : bytes) {
            int unsigned = valueByte & 0xff;
            char character = (char) unsigned;
            if (character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '-' || character == '.' || character == '_' || character == '~') {
                encoded.append(character);
            } else {
                encoded.append('%');
                String hex = Integer.toHexString(unsigned).toUpperCase(Locale.ROOT);
                if (hex.length() == 1) {
                    encoded.append('0');
                }
                encoded.append(hex);
            }
        }
        return encoded.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() > MAX_ERROR_MESSAGE_LENGTH ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : text;
    }

    /** 测试与安全审计可见：两个方向的自动重定向都必须保持关闭。 */
    boolean followsRedirects() {
        return httpClient.followRedirects() || httpClient.followSslRedirects();
    }

    /**
     * 探活结果：{@code success=false} 时 {@code message} 为失败原因（HTTP 状态码 / 超时 / 异常信息，
     * 已截断到 {@value #MAX_ERROR_MESSAGE_LENGTH} 字符）；成功时 {@code message} 为 null。
     */
    public record ProbeResult(boolean success, String message) {
    }
}
