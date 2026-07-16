package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 模型运行时构建：连通性测试（{@link #testConnectivity}）+ 现场构建真实 {@link Model} 实例
 * （{@link #buildModel}，供动态智能体运行时注入 {@code ReActAgent}）。
 *
 * <p>不复用 {@code customer-work-spring-boot-starter} 的 {@code ModelConfig}——那是「应用启动时读 yml
 * 建一个默认 Model Bean」的模式；这里是「按 {@code ai_model_config} 任意一行现场构建」的动态场景。</p>
 *
 * <p>四家厂商全部走 AgentScope 框架自带的原生 ChatModel 实现（OpenAI 兼容 / 百炼 DashScope 原生 /
 * Anthropic Claude 原生 / Google Gemini）；连通性测试则按厂商各自的最小探活协议直连（用 JDK 内置
 * {@link HttpClient}，不引入额外 HTTP 客户端依赖，与短生命周期探测请求的定位一致）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AdminModelFactory.class);

    /** 单次探测超时（需求文档 3.1：5~10s），落在区间内。 */
    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofSeconds(8);
    private static final String TEST_PROMPT = "你好";
    private static final int TEST_MAX_TOKENS = 8;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    // ---- 各厂商探活端点路径 / 鉴权头常量（避免魔法值）----
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DASHSCOPE_TEXT_GENERATION_PATH = "/api/v1/services/aigc/text-generation/generation";
    private static final String ANTHROPIC_MESSAGES_PATH = "/v1/messages";
    private static final String GEMINI_GENERATE_CONTENT_PATH_TEMPLATE = "/v1beta/models/%s:generateContent";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ANTHROPIC_API_KEY = "x-api-key";
    private static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    private static final String HEADER_GEMINI_API_KEY = "x-goog-api-key";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String BEARER_PREFIX = "Bearer ";
    /** Anthropic Messages API 版本（原生协议必填头）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration testTimeout;

    public AdminModelFactory() {
        this(DEFAULT_TEST_TIMEOUT);
    }

    /** 包内可见，供单测注入短超时，避免真实等待 8s（生产始终走无参构造）。 */
    AdminModelFactory(Duration testTimeout) {
        this.testTimeout = testTimeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * 按厂商发一条固定 prompt 的最小探活请求，验证连通性。HTTP 2xx 且返回结构符合该厂商成功响应形态
     * → 成功；否则失败；超时（5~10s）即失败。未知 provider 由 {@link ModelProvider#of} fast fail。
     */
    public ModelTestResult testConnectivity(String provider, String baseUrl, String apiKey, String modelName) {
        ModelProvider p = ModelProvider.of(provider);
        LocalDateTime now = LocalDateTime.now();
        try {
            HttpRequest request = buildProbeRequest(p, baseUrl, apiKey, modelName);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (isSuccessStatus(response.statusCode()) && hasValidResponseStructure(p, response.body())) {
                return new ModelTestResult(ModelTestResult.STATUS_SUCCESS, now, null);
            }
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now,
                "HTTP " + response.statusCode() + ": " + truncate(response.body()));
        } catch (HttpTimeoutException e) {
            log.error("model connectivity test timeout, code={}, provider={}", "MODEL-TEST-TIMEOUT", p.getCode(), e);
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now, "连通性测试超时（>" + testTimeout.getSeconds() + "s）");
        } catch (Exception e) {
            log.error("model connectivity test failed, code={}, provider={}", "MODEL-TEST-FAIL", p.getCode(), e);
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now, truncate(e.getMessage()));
        }
    }

    /**
     * 构建可直接注入 {@code ReActAgent.Builder#model(Model)} 的真实模型实例（动态智能体运行时用，
     * 区别于 {@link #testConnectivity}——后者是短生命周期的连通性探测请求）。
     *
     * <p>四家厂商全部走框架原生 ChatModel；未知 provider 由 {@link ModelProvider#of} fast fail。
     * 高级生成参数（温度/maxTokens）交给模型默认值，与 {@code ModelSaveRequest} 不暴露调参保持一致。</p>
     */
    public Model buildModel(String provider, String baseUrl, String apiKey, String modelName) {
        ModelProvider p = ModelProvider.of(provider);
        GenerateOptions options = GenerateOptions.builder().build();
        switch (p) {
            case DASHSCOPE: {
                DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(true).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    builder.baseUrl(baseUrl);
                }
                return builder.build();
            }
            case ANTHROPIC: {
                AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(true).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    builder.baseUrl(baseUrl);
                }
                return builder.build();
            }
            case GEMINI: {
                GeminiChatModel.Builder builder = GeminiChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).streamEnabled(true).defaultOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    builder.baseUrl(baseUrl);
                }
                return builder.build();
            }
            case OPENAI:
            default: {
                OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                    .apiKey(apiKey).modelName(modelName).stream(true).generateOptions(options);
                if (StringUtils.hasText(baseUrl)) {
                    builder.baseUrl(baseUrl);
                }
                return builder.build();
            }
        }
    }

    // ==================== 各厂商探活请求构建 ====================

    private HttpRequest buildProbeRequest(ModelProvider provider, String baseUrl, String apiKey, String modelName)
            throws Exception {
        switch (provider) {
            case DASHSCOPE:
                return dashScopeProbe(baseUrl, apiKey, modelName);
            case ANTHROPIC:
                return anthropicProbe(baseUrl, apiKey, modelName);
            case GEMINI:
                return geminiProbe(baseUrl, apiKey, modelName);
            case OPENAI:
            default:
                return openAiProbe(baseUrl, apiKey, modelName);
        }
    }

    /** OpenAI 兼容：POST {base}/chat/completions，Bearer 鉴权，成功响应含 choices 数组。 */
    private HttpRequest openAiProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", modelName,
            "messages", List.of(Map.of("role", "user", "content", TEST_PROMPT)),
            "max_tokens", TEST_MAX_TOKENS));
        return baseRequest(appendPath(baseUrl, OPENAI_CHAT_COMPLETIONS_PATH), body)
            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
            .build();
    }

    /** 百炼 DashScope 原生：POST {base}/api/v1/.../generation，Bearer 鉴权，成功响应含 output 对象。 */
    private HttpRequest dashScopeProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "model", modelName,
            "input", Map.of("messages", List.of(Map.of("role", "user", "content", TEST_PROMPT))),
            "parameters", Map.of("max_tokens", TEST_MAX_TOKENS, "result_format", "message")));
        return baseRequest(appendPath(baseUrl, DASHSCOPE_TEXT_GENERATION_PATH), body)
            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
            .build();
    }

    /** Anthropic 原生：POST {base}/v1/messages，x-api-key + anthropic-version 鉴权，成功响应含 content 数组。 */
    private HttpRequest anthropicProbe(String baseUrl, String apiKey, String modelName) throws Exception {
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
    private HttpRequest geminiProbe(String baseUrl, String apiKey, String modelName) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", TEST_PROMPT))))));
        String path = String.format(GEMINI_GENERATE_CONTENT_PATH_TEMPLATE, modelName);
        return baseRequest(appendPath(baseUrl, path), body)
            .header(HEADER_GEMINI_API_KEY, apiKey)
            .build();
    }

    /** 公共请求骨架：JSON POST + 超时。鉴权头由各厂商分支追加。 */
    private HttpRequest.Builder baseRequest(String url, String body) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(testTimeout)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    // ==================== 各厂商成功响应结构校验 ====================

    private boolean hasValidResponseStructure(ModelProvider provider, String responseBody) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            switch (provider) {
                case DASHSCOPE:
                    // 原生响应体形如 {"output":{...},"usage":{...}}
                    return node.has("output") && node.get("output").isObject();
                case ANTHROPIC:
                    // 原生响应体形如 {"content":[...],"role":"assistant"}
                    return node.has("content") && node.get("content").isArray();
                case GEMINI:
                    // 原生响应体形如 {"candidates":[...]}
                    return node.has("candidates") && node.get("candidates").isArray();
                case OPENAI:
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

    private String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() > MAX_ERROR_MESSAGE_LENGTH ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : text;
    }
}
