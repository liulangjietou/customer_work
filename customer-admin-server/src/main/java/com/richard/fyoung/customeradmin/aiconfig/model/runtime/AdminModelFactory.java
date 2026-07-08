package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
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
 * 模型运行时构建（当前用途：连通性测试）。
 *
 * <p>不复用 {@code customer-work-spring-boot-starter} 的 {@code ModelConfig}——那是"应用启动时读 yml
 * 建一个默认 Model Bean"的模式；这里是"按 {@code ai_model_config} 任意一行现场构建"的动态场景，
 * 且需要连通性测试用的短生命周期请求。当前仅支持 OpenAI 标准接口（需求文档 3.1），用 JDK 内置
 * {@code java.net.http.HttpClient} 直连，不引入额外 HTTP 客户端依赖。</p>
 *
 * <p>批次四（智能体运行时）会在本类补充"构建真实 AgentScope {@code Model} 实例"的方法，
 * 复用同一个 provider 分支判断逻辑。</p>
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
     * 发一条固定 prompt 到 {@code {baseUrl}/chat/completions}，验证连通性。
     * HTTP 2xx 且返回结构含 {@code choices} 数组 → 成功；否则失败；超时（5~10s）即失败。
     */
    public ModelTestResult testConnectivity(String baseUrl, String apiKey, String modelName) {
        LocalDateTime now = LocalDateTime.now();
        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", TEST_PROMPT)),
                "max_tokens", TEST_MAX_TOKENS));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveChatCompletionsUrl(baseUrl)))
                .timeout(testTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (isSuccessStatus(response.statusCode()) && hasValidChoicesStructure(response.body())) {
                return new ModelTestResult(ModelTestResult.STATUS_SUCCESS, now, null);
            }
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now,
                "HTTP " + response.statusCode() + ": " + truncate(response.body()));
        } catch (HttpTimeoutException e) {
            log.error("model connectivity test timeout, code={}", "MODEL-TEST-TIMEOUT", e);
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now, "连通性测试超时（>" + testTimeout.getSeconds() + "s）");
        } catch (Exception e) {
            log.error("model connectivity test failed, code={}", "MODEL-TEST-FAIL", e);
            return new ModelTestResult(ModelTestResult.STATUS_FAILED, now, truncate(e.getMessage()));
        }
    }

    /**
     * 构建可直接注入 {@code ReActAgent.Builder#model(Model)} 的真实模型实例（动态智能体运行时用，
     * 区别于 {@link #testConnectivity}——后者是短生命周期的连通性探测请求）。
     *
     * <p>当前仅接受 {@code provider=openai}，与需求文档 3.1 及 {@code ModelConfigService} 的默认
     * provider 保持一致；非 openai 直接报业务错误，不做插件化过度设计（实施计划 3.2 节）。</p>
     */
    public Model buildModel(String provider, String baseUrl, String apiKey, String modelName) {
        if (!"openai".equalsIgnoreCase(provider)) {
            throw new BizException(ResultCode.AGENT_CAPABILITY_NOT_SUPPORTED,
                "暂不支持的模型 provider: " + provider + "（当前仅支持 openai）");
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .stream(true)
            .generateOptions(GenerateOptions.builder().build());
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private boolean isSuccessStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 返回结构合法性：含 choices 数组（OpenAI 标准响应结构的最小校验）。 */
    private boolean hasValidChoicesStructure(String responseBody) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            return node.has("choices") && node.get("choices").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveChatCompletionsUrl(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
    }

    private String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() > MAX_ERROR_MESSAGE_LENGTH ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : text;
    }
}
