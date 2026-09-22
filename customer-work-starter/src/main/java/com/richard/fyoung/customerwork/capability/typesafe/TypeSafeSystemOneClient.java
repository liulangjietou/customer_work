package com.richard.fyoung.customerwork.capability.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.constant.HttpAuthConstants;
import com.richard.fyoung.customerwork.core.constant.MetricTags;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TypeSafe System One 的 HTTP 直连实现：{@code POST /v1/systemone}，Bearer API Key 鉴权。
 *
 * <p>官方只提供 Python / JS SDK，Java 侧按 HTTP API 文档直连，复用 JDK 内置 HttpClient，不引入新依赖。</p>
 *
 * <p><b>用 {@code sendAsync} 而不是 {@code send}</b>：主链路的 Agent 调用本身就跑在
 * {@code boundedElastic} 上。若这里用阻塞调用再切到同一个线程池，Jev 一变慢就会占满它，
 * 主链路的 Agent 调用随之排队——一个增强能力拖垮整个客服。异步调用不占任何 Reactor 线程。</p>
 *
 * <p>失败口径见 {@link SystemOneClient}：一律 empty + 日志 + 指标。日志只记状态码与耗时，
 * <b>不记被判定的内容</b>——那是用户原话与回复正文，含个人信息。</p>
 */
public class TypeSafeSystemOneClient implements SystemOneClient {

    private static final Logger log = LoggerFactory.getLogger(TypeSafeSystemOneClient.class);

    private static final String SYSTEM_ONE_PATH = "/v1/systemone";
    private static final String CODE_CALL_FAIL = "TYPESAFE-CALL-FAIL";
    private static final String CODE_CIRCUIT_OPEN = "TYPESAFE-CIRCUIT-OPEN";

    static final String M_CALLS = "customerwork.typesafe.calls";
    static final String M_LATENCY = "customerwork.typesafe.latency";
    static final String RESULT_SUCCESS = "success";
    static final String RESULT_HTTP_ERROR = "http_error";
    static final String RESULT_TIMEOUT = "timeout";
    static final String RESULT_INVALID = "invalid";
    static final String RESULT_ERROR = "error";
    static final String RESULT_CIRCUIT_OPEN = "circuit_open";

    private final TypeSafeProperties properties;
    private final MeterRegistry meterRegistry;
    private final JevCircuitBreaker breaker;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param properties    Jev 配置；开启时 apiKey 为空直接抛错（fast fail）
     * @param meterRegistry 可为 null（未接入 Micrometer 时只打日志）
     */
    public TypeSafeSystemOneClient(TypeSafeProperties properties, MeterRegistry meterRegistry) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                "customer-work.typesafe.enabled=true but api-key is empty (set env CUSTOMER_WORK_TYPESAFE_API_KEY)");
        }
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.breaker = new JevCircuitBreaker(properties.getCircuitBreaker().getFailureThreshold(),
            properties.getCircuitBreaker().getOpenDurationMs(), System::currentTimeMillis);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
    }

    @Override
    public Mono<SystemOneResult> ask(String state, Map<String, SystemOneQuestion> questions, Duration timeout) {
        if (!StringUtils.hasText(state) || CollectionUtils.isEmpty(questions)) {
            return Mono.empty();
        }
        if (!breaker.allowRequest()) {
            count(RESULT_CIRCUIT_OPEN);
            return Mono.empty();
        }
        return Mono.defer(() -> {
                long startNanos = System.nanoTime();
                return Mono.fromFuture(httpClient.sendAsync(buildRequest(state, questions, timeout),
                        HttpResponse.BodyHandlers.ofString()))
                    .timeout(timeout)
                    .map(response -> toResult(response, questions, startNanos));
            })
            .doOnNext(result -> {
                breaker.onSuccess();
                count(RESULT_SUCCESS);
                if (meterRegistry != null) {
                    meterRegistry.timer(M_LATENCY).record(result.latencyMs(), TimeUnit.MILLISECONDS);
                }
            })
            .onErrorResume(error -> {
                String result = classify(error);
                count(result);
                log.error("typesafe call failed, code={}, result={}, reason={}", CODE_CALL_FAIL, result,
                    error.getMessage());
                if (breaker.onFailure()) {
                    log.error("typesafe circuit opened, code={}, skipDurationMs={}", CODE_CIRCUIT_OPEN,
                        properties.getCircuitBreaker().getOpenDurationMs());
                }
                return Mono.empty();
            })
            .doOnCancel(breaker::onAbandoned);
    }

    /** 当前熔断状态，供单测与排查。 */
    JevCircuitBreaker breaker() {
        return breaker;
    }

    private HttpRequest buildRequest(String state, Map<String, SystemOneQuestion> questions, Duration timeout) {
        Map<String, Object> payloads = new LinkedHashMap<>();
        questions.forEach((id, question) -> payloads.put(id, question.toPayload()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", properties.getModel());
        body.put("questions", payloads);
        try {
            return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + SYSTEM_ONE_PATH))
                .timeout(timeout)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, HttpAuthConstants.BEARER_PREFIX + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        } catch (Exception e) {
            throw new IllegalStateException("typesafe request build failed", e);
        }
    }

    private SystemOneResult toResult(HttpResponse<String> response, Map<String, SystemOneQuestion> questions,
                                     long startNanos) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HttpStatusException(response.statusCode());
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return parse(response.body(), questions, latencyMs);
    }

    /**
     * 解析响应。单个问题解析失败只丢弃该问题，其余照常返回；一个都解析不出来才算整体失败。
     * 包级可见便于离线单测。
     */
    SystemOneResult parse(String responseBody, Map<String, SystemOneQuestion> questions, long latencyMs) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            throw new InvalidResponseException("response is not json");
        }
        JsonNode answersNode = root.path("answers");
        Map<String, SystemOneAnswer> answers = new LinkedHashMap<>();
        questions.forEach((id, question) -> {
            SystemOneAnswer answer = parseAnswer(question, answersNode.path(id));
            if (answer != null) {
                answers.put(id, answer);
            }
        });
        if (answers.isEmpty()) {
            throw new InvalidResponseException("no valid answer in response");
        }
        return new SystemOneResult(root.path("model").asText(properties.getModel()), answers, latencyMs);
    }

    private SystemOneAnswer parseAnswer(SystemOneQuestion question, JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        if (question instanceof SystemOneQuestion.Choice choice) {
            String selected = node.path("choice").asText(null);
            // 选项必须来自我们给出的 criteria：模型返回意料之外的值时宁可当作没答
            if (!StringUtils.hasText(selected) || !choice.criteria().containsKey(selected)
                || !isProbability(node.path("confidence"))) {
                return null;
            }
            return new SystemOneAnswer.Choice(selected, node.path("confidence").asDouble());
        }
        if (question instanceof SystemOneQuestion.Score score) {
            Map<Integer, Double> probabilities = parseProbabilities(node.path("probabilities"),
                score.levels().size());
            if (probabilities.isEmpty() || !node.path("score").isNumber() || !isProbability(node.path("confidence"))) {
                return null;
            }
            return new SystemOneAnswer.Score(node.path("score").asDouble(), node.path("confidence").asDouble(),
                probabilities);
        }
        if (question instanceof SystemOneQuestion.Noul) {
            return isProbability(node.path("noul")) ? new SystemOneAnswer.Noul(node.path("noul").asDouble()) : null;
        }
        return null;
    }

    /** 概率键是档位编号字符串（"0"、"1"…），越界或非数值的条目整体视为非法。 */
    private Map<Integer, Double> parseProbabilities(JsonNode node, int levelCount) {
        Map<Integer, Double> probabilities = new LinkedHashMap<>();
        if (!node.isObject()) {
            return probabilities;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            int level;
            try {
                level = Integer.parseInt(field.getKey());
            } catch (NumberFormatException e) {
                return Map.of();
            }
            if (level < 0 || level >= levelCount || !isProbability(field.getValue())) {
                return Map.of();
            }
            probabilities.put(level, field.getValue().asDouble());
        }
        return probabilities;
    }

    private static boolean isProbability(JsonNode node) {
        return node.isNumber() && node.asDouble() >= 0 && node.asDouble() <= 1;
    }

    /**
     * 失败分类决定运维看到的是「Jev 变慢」还是「Jev 坏了」，两者的处置完全不同。
     *
     * <p>超时有两个来源且都要认：请求自带的超时由 HttpClient 抛 {@link HttpTimeoutException}，
     * 它往往比 Reactor 的 {@code timeout()} 先触发；异步 future 的异常还可能被包在
     * {@link CompletionException} 里。</p>
     */
    private static String classify(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
            return RESULT_TIMEOUT;
        }
        if (cause instanceof HttpStatusException) {
            return RESULT_HTTP_ERROR;
        }
        if (cause instanceof InvalidResponseException) {
            return RESULT_INVALID;
        }
        return RESULT_ERROR;
    }

    private void count(String result) {
        if (meterRegistry != null) {
            meterRegistry.counter(M_CALLS, MetricTags.RESULT, result).increment();
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 非 2xx：含 401（Key 无效）、422（请求非法）、429（限流）、529（过载），全部计入熔断。 */
    static final class HttpStatusException extends RuntimeException {
        HttpStatusException(int status) {
            super("http status " + status);
        }
    }

    static final class InvalidResponseException extends RuntimeException {
        InvalidResponseException(String message) {
            super(message);
        }
    }
}
