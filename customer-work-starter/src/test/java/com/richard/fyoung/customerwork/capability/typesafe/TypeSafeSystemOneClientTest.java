package com.richard.fyoung.customerwork.capability.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 HttpServer 校验请求契约（路径 / Bearer / 请求体）与响应解析，完全离线。
 * 请求与响应的字段格式对照 https://docs.typesafe.ai/api 。
 */
class TypeSafeSystemOneClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private HttpServer server;
    private TypeSafeProperties props;
    private SimpleMeterRegistry registry;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delayMs;
    private volatile String responseJson = "{}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        props = new TypeSafeProperties();
        props.setApiKey("k-test");
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求体符合官方契约：路径、Bearer、三种问题的字段形态")
    void sendsDocumentedRequest() throws Exception {
        responseJson = "{\"model\":\"jev-1.13.0\",\"answers\":{\"i\":{\"type\":\"choice\",\"choice\":\"order\","
            + "\"confidence\":0.9}}}";
        Map<String, SystemOneQuestion> questions = new LinkedHashMap<>();
        questions.put("i", new SystemOneQuestion.Choice("意图？", Map.of("order", "订单", "other", "其他")));
        questions.put("s", new SystemOneQuestion.Score("情绪？", List.of("平稳", "不满", "愤怒")));
        questions.put("n", new SystemOneQuestion.Noul("到账？", "是", "否"));

        client().ask("我的订单呢", questions, TIMEOUT).block();

        assertEquals("/v1/systemone", path.get());
        assertEquals("Bearer k-test", auth.get());
        JsonNode req = new ObjectMapper().readTree(body.get());
        assertEquals("我的订单呢", req.get("state").asText());
        assertEquals("jev-latest", req.get("model").asText());
        assertEquals("choice", req.at("/questions/i/type").asText());
        assertEquals("订单", req.at("/questions/i/criteria/order").asText());
        assertEquals("score", req.at("/questions/s/type").asText());
        assertTrue(req.at("/questions/s/criteria").isArray(), "Score 的 criteria 是有序数组");
        assertEquals("愤怒", req.at("/questions/s/criteria/2").asText());
        assertEquals("noul", req.at("/questions/n/type").asText());
        assertEquals("是", req.at("/questions/n/criteria/true").asText());
    }

    @Test
    @DisplayName("三种答案按官方字段解析；Score 的概率键是档位编号字符串")
    void parsesAllAnswerTypes() {
        responseJson = "{\"model\":\"jev-1.13.0\",\"answers\":{"
            + "\"i\":{\"type\":\"choice\",\"choice\":\"order\",\"confidence\":0.91},"
            + "\"s\":{\"type\":\"score\",\"score\":1.43,\"confidence\":0.35,"
            + "\"probabilities\":{\"0\":0.0,\"1\":0.57,\"2\":0.43}},"
            + "\"n\":{\"type\":\"noul\",\"noul\":0.95}}}";

        SystemOneResult result = client().ask("x", threeQuestions(), TIMEOUT).block();

        assertEquals("jev-1.13.0", result.model());
        assertEquals(new SystemOneAnswer.Choice("order", 0.91), result.choice("i").orElseThrow());
        SystemOneAnswer.Score score = result.score("s").orElseThrow();
        assertEquals(1.43, score.score());
        assertEquals(1, score.mostLikelyLevel(), "期望值 1.43，但最可能的档位是 1");
        assertEquals(0.95, result.noul("n").orElseThrow().probability());
    }

    @Test
    @DisplayName("单个问题解析失败只丢弃它，其余照常返回")
    void keepsValidAnswersWhenOneIsBroken() {
        responseJson = "{\"answers\":{"
            + "\"i\":{\"choice\":\"hacked\",\"confidence\":0.99},"
            + "\"n\":{\"noul\":0.2}}}";

        SystemOneResult result = client().ask("x", threeQuestions(), TIMEOUT).block();

        assertTrue(result.choice("i").isEmpty(), "选项不在我们给出的 criteria 里，宁可当作没答");
        assertEquals(0.2, result.noul("n").orElseThrow().probability());
    }

    @Test
    @DisplayName("HTTP 错误、非法响应、超时：一律返回空，不抛错")
    void failuresBecomeEmpty() {
        status = 401;
        assertNull(client().ask("x", threeQuestions(), TIMEOUT).block());

        status = 200;
        responseJson = "not json";
        assertNull(client().ask("x", threeQuestions(), TIMEOUT).block());

        responseJson = "{\"answers\":{}}";
        assertNull(client().ask("x", threeQuestions(), TIMEOUT).block(), "一个答案都解析不出来算整体失败");

        delayMs = 800;
        responseJson = "{\"answers\":{\"n\":{\"noul\":0.5}}}";
        assertNull(client().ask("x", threeQuestions(), Duration.ofMillis(200)).block());
        assertEquals(1.0, registry.counter(TypeSafeSystemOneClient.M_CALLS, "result",
            TypeSafeSystemOneClient.RESULT_TIMEOUT).count());
    }

    /**
     * 熔断存在的意义：Jev 挂掉之后不再每轮都去碰它。
     * 用服务端收到的请求数断言——只看返回值的话，「照样发请求、失败后返回空」也会是绿的。
     */
    @Test
    @DisplayName("连续失败后熔断打开，之后的调用根本不发请求")
    void circuitOpensAndStopsCalling() {
        props.getCircuitBreaker().setFailureThreshold(3);
        status = 529;
        TypeSafeSystemOneClient client = client();

        for (int i = 0; i < 3; i++) {
            client.ask("x", threeQuestions(), TIMEOUT).block();
        }
        int hitsWhenOpened = hits.get();
        for (int i = 0; i < 5; i++) {
            assertNull(client.ask("x", threeQuestions(), TIMEOUT).block());
        }

        assertEquals(3, hitsWhenOpened);
        assertEquals(hitsWhenOpened, hits.get(), "熔断打开后仍在发请求");
        assertEquals(5.0, registry.counter(TypeSafeSystemOneClient.M_CALLS, "result",
            TypeSafeSystemOneClient.RESULT_CIRCUIT_OPEN).count());
    }

    @Test
    @DisplayName("开启了却没配 Key：构造即失败，不留到运行期静默退回")
    void missingApiKeyFailsFast() {
        props.setApiKey(" ");
        assertThrows(IllegalStateException.class, this::client);
    }

    @Test
    @DisplayName("空内容或空问题不发请求")
    void blankInputSkipsCall() {
        assertNull(client().ask(" ", threeQuestions(), TIMEOUT).block());
        assertNull(client().ask("x", Map.of(), TIMEOUT).block());
        assertEquals(0, hits.get());
    }

    private TypeSafeSystemOneClient client() {
        return new TypeSafeSystemOneClient(props, registry);
    }

    private static Map<String, SystemOneQuestion> threeQuestions() {
        Map<String, SystemOneQuestion> questions = new LinkedHashMap<>();
        questions.put("i", new SystemOneQuestion.Choice("意图？", Map.of("order", "订单", "other", "其他")));
        questions.put("s", new SystemOneQuestion.Score("情绪？", List.of("平稳", "不满", "愤怒")));
        questions.put("n", new SystemOneQuestion.Noul("到账？", "是", "否"));
        return questions;
    }
}
